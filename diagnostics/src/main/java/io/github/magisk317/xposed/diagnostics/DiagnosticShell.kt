package io.github.magisk317.xposed.diagnostics

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Shared root/shell primitives for diagnostic bundle collectors.
 *
 * Extracted from the per-app `LogBundleExporter` implementations (smscode-core
 * runtime and MiPushFramework xmsf/shell) so that su execution, shell quoting,
 * LSPosed log collection and logcat capture exist exactly once. Product policy
 * (which directories, which include filters, whether a root fallback is
 * allowed) stays with each caller through parameters.
 */
object DiagnosticShell {

    private const val SU_TIMEOUT_SEC = 10L
    private const val DUMP_TIMEOUT_SEC = 6L
    /** Bytes of child output kept; a wedged `su` must not be able to grow this without bound. */
    private const val SU_OUTPUT_CAP = 512 * 1024
    /** Grace given to a stream reader after the deadline or after the child exits. */
    private const val READER_JOIN_MS = 2_000L
    private const val BOUNDED_LOGCAT_WINDOW_HOURS = 24L
    private const val BOUNDED_LOGCAT_BUFFERS = "main,system,crash"

    /**
     * LSPosed keeps rotating logs next to the live one, so a bundle that claims to carry
     * framework logs has to carry both or say that it did not look.
     */
    val lsposedLogDirs: List<String> = listOf(
        "/data/adb/lspd/log",
        "/data/adb/lspd/log.old",
    )

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /** POSIX single-quote escaping: wrap in quotes and escape embedded quotes. */
    fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Run [command] through `su -c`, forcibly killing it when [timeoutSec] elapses.
     *
     * Three rules, all of them learned the hard way:
     *
     *  - The child's stdin is closed immediately. `su` falls back to an interactive
     *    password prompt when it is called by a user that is not root, and that prompt
     *    reads from stdin. Left open, the child sits waiting for a password, its stdout
     *    never reaches EOF, and every read below blocks forever. This is what stalled the
     *    GitHub CI test job on 2026-10-24: a Gradle unit test exercises this very path,
     *    GitHub-hosted runners execute as uid 1001 and ship /usr/bin/su, while the GitLab
     *    containers and the Debian build host run as root, where su never asks.
     *  - Both streams are drained concurrently. Reading stdout to EOF before touching
     *    stderr deadlocks any child that writes more than a pipe buffer (~64 KiB) to
     *    stderr while we sit on stdout.
     *  - The timeout is enforced from the caller's thread, not after the reads. The
     *    previous implementation awaited the streams first and only then called
     *    waitFor(timeout), so the deadline could not be reached while a read was blocked
     *    and destroyForcibly was dead code.
     */
    fun runSuCommand(command: String, timeoutSec: Long = SU_TIMEOUT_SEC): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            return ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
        // Already closed or already gone: either way there is nothing left to prompt on.
        runCatching { process.outputStream.close() }.onFailure { }

        val stdout = BoundedCapture(SU_OUTPUT_CAP)
        val stderr = BoundedCapture(SU_OUTPUT_CAP)
        val outReader = streamReader(process.inputStream, stdout)
        val errReader = streamReader(process.errorStream, stderr)
        outReader.start()
        errReader.start()

        val finished = try {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return ShellResult(-1, stdout.snapshot(), "interrupted")
        }
        if (!finished) {
            process.destroyForcibly()
            outReader.join(READER_JOIN_MS)
            errReader.join(READER_JOIN_MS)
            return ShellResult(-1, stdout.snapshot(), "su timeout")
        }
        outReader.join(READER_JOIN_MS)
        errReader.join(READER_JOIN_MS)
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        return ShellResult(exitCode, stdout.snapshot(), stderr.snapshot())
    }

    /**
     * Captures one stream of a child we do not trust. Daemon threads, because the deadline
     * above is the only thing allowed to end a read: destroyForcibly closes the descriptors,
     * the read returns, and the thread is already irrelevant by then.
     */
    private fun streamReader(source: InputStream, sink: BoundedCapture): Thread =
        Thread {
            try {
                source.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.append(buffer, read)
                    }
                }
            } catch (_: IOException) {
                // The process was destroyed on purpose, or the deadline passed and the
                // descriptor went away under us; whatever was captured is the answer.
                // Deliberately no process.destroy() here: a child that closes its output
                // pipes is not necessarily finished, and only the deadline may kill it.
            }
        }.apply {
            isDaemon = true
            name = "diagnostics-su-stream-reader"
        }

    /** Output of a child we do not trust: keep at most [cap] bytes and count the rest. */
    private class BoundedCapture(private val cap: Int) {
        private val text = StringBuilder()
        private var overflow = 0

        @Synchronized
        fun append(chunk: ByteArray, length: Int) {
            if (length <= 0 || text.length >= cap) {
                overflow += length
                return
            }
            val room = cap - text.length
            val take = minOf(room, length)
            text.append(String(chunk, 0, take, StandardCharsets.UTF_8))
            overflow += length - take
        }

        @Synchronized
        fun snapshot(): String =
            if (overflow > 0) text.append("… +$overflow bytes discarded").toString()
            else text.toString()
    }

    /**
     * Delete [target] recursively, falling back to `su rm -rf` when plain
     * deletion fails. [rootAvailable] gates the su fallback for hosts that
     * track root grants explicitly; [onWarning] receives failure details.
     */
    fun deleteRecursivelyWithSuFallback(
        target: File,
        rootAvailable: () -> Boolean = { true },
        onWarning: (String) -> Unit = {},
    ): Boolean {
        if (!target.exists()) return true
        if (target.deleteRecursively()) return true
        if (!rootAvailable()) {
            onWarning("Skip su rm fallback because root is not granted: ${target.absolutePath}")
            return !target.exists()
        }
        val suResult = runSuCommand("rm -rf ${shQuote(target.absolutePath)}")
        val deleted = !target.exists()
        if (!deleted) {
            onWarning(
                "su rm fallback failed: path=${target.absolutePath} exit=${suResult.exitCode} " +
                    "stderr=${suResult.stderr} stdout=${suResult.stdout}",
            )
        }
        return deleted
    }

    /**
     * Copy [lsposedLogDirs] into `<stagingDir>/lsposed`, but only for builds that may carry
     * root-heavy diagnostics.
     *
     * The directory list and the gate both live here so sibling products cannot collect
     * different slices of `/data/adb/lspd` while describing the bundle the same way.
     */
    fun copyLsposedLogs(
        stagingDir: File,
        details: MutableList<String>,
        mode: DiagnosticExportMode,
        includeFile: (File) -> Boolean = { true },
        onWarning: (String) -> Unit = {},
    ) {
        if (!mode.capturesRootHeavyDiagnostics) {
            details += "lsposed: skipped by export mode ${mode.name}"
            return
        }
        copyLsposedLogsFrom(
            dirs = lsposedLogDirs,
            stagingDir = stagingDir,
            details = details,
            includeFile = includeFile,
            onWarning = onWarning,
        )
    }

    /**
     * Copy LSPosed log [dirs] into `<stagingDir>/lsposed`, preferring a direct
     * copy and falling back to a single `su` shell command
     * (mkdir/cp/chmod/chown) when the directories are not readable in-process.
     */
    fun copyLsposedLogsFrom(
        dirs: List<String>,
        stagingDir: File,
        details: MutableList<String>,
        includeFile: (File) -> Boolean = { true },
        onWarning: (String) -> Unit = {},
    ) {
        val lsposedTargetRoot = File(stagingDir, "lsposed")
        var copied = false
        dirs.forEach { path ->
            val src = File(path)
            if (src.exists() && src.canRead()) {
                val target = File(lsposedTargetRoot, src.name)
                DiagnosticArchive.copyDirectory(src, target, includeFile, onWarning)
                if (target.walkTopDown().any { it.isFile }) {
                    details += "lsposed direct: $path"
                    copied = true
                }
            }
        }
        if (copied) return
        val targetPath = lsposedTargetRoot.absolutePath
        val targetUid = runCatching { android.os.Process.myUid() }.getOrDefault(-1)
        val shellCmd = buildString {
            append("mkdir -p ${shQuote(targetPath)}; ")
            dirs.forEach { path ->
                val name = File(path).name
                append("if [ -d ${shQuote(path)} ]; then ")
                append("cp -R ${shQuote(path)} ${shQuote("$targetPath/")} && ")
                append("chmod -R a+rX ${shQuote("$targetPath/$name")} ; ")
                if (targetUid > 0) {
                    append("chown -R $targetUid:$targetUid ${shQuote("$targetPath/$name")} ; ")
                }
                append("fi; ")
            }
        }
        val suResult = runSuCommand(shellCmd)
        if (suResult.exitCode == 0 && lsposedTargetRoot.walkTopDown().any { it.isFile }) {
            details += "lsposed copied via su"
            return
        }
        details += "lsposed su failed: ${suResult.stderr.ifBlank { suResult.stdout }.ifBlank { "unknown" }}"
    }

    /**
     * `logcat -t` accepts an absolute timestamp, not a duration, so the bounded
     * window is rendered as device-local `yyyy-MM-dd HH:mm:ss.SSS`.
     */
    internal fun boundedLogcatSinceArg(
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        format.timeZone = timeZone
        return format.format(Date(nowMillis - TimeUnit.HOURS.toMillis(BOUNDED_LOGCAT_WINDOW_HOURS)))
    }

    /**
     * Dump `logcat -d` into `<stagingDir>/logcat/logcat_all.txt`. Bounded modes
     * capture the last [BOUNDED_LOGCAT_WINDOW_HOURS] of the main/system/crash
     * buffers; full modes capture all buffers and may fall back to a root logcat
     * via [suFallback] when the direct dump yields nothing.
     */
    fun captureLogcat(
        stagingDir: File,
        details: MutableList<String>,
        mode: DiagnosticExportMode,
        suFallback: ((File) -> Boolean)? = null,
        onWarning: (String) -> Unit = {},
    ) {
        val logcatDir = File(stagingDir, "logcat")
        if (!DiagnosticArchive.ensureDirectory(logcatDir, recreateWhenFile = true, onWarning = onWarning)) {
            return
        }
        val output = File(logcatDir, "logcat_all.txt")
        if (mode.usesBoundedLogcat) {
            val since = boundedLogcatSinceArg()
            val bounded = dumpCommandOutput(
                listOf("logcat", "-d", "-v", "threadtime", "-t", since, "-b", BOUNDED_LOGCAT_BUFFERS),
                output,
            )
            details += if (bounded) {
                "logcat: direct bounded $BOUNDED_LOGCAT_BUFFERS since $since"
            } else {
                "logcat: direct bounded $BOUNDED_LOGCAT_BUFFERS since $since failed"
            }
            return
        }
        val direct = dumpCommandOutput(listOf("logcat", "-d", "-v", "threadtime", "-b", "all"), output)
        if (direct) {
            details += "logcat: direct"
            return
        }
        if (suFallback != null && suFallback(output)) {
            details += "logcat: su"
        }
    }

    /** Run [command] writing merged stdout/stderr into [output]; true when non-empty. */
    fun dumpCommandOutput(command: List<String>, output: File): Boolean {
        return runCatching {
            val parent = output.parentFile ?: return@runCatching false
            if (!DiagnosticArchive.ensureDirectory(parent, recreateWhenFile = true)) return@runCatching false
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(output))
                .start()
            if (!process.waitFor(DUMP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching false
            }
            output.exists() && output.length() > 0
        }.getOrDefault(false)
    }
}
