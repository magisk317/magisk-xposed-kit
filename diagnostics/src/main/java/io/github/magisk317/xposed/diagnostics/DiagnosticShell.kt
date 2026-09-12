package io.github.magisk317.xposed.diagnostics

import java.io.File
import java.io.IOException
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
    private const val BOUNDED_LOGCAT_WINDOW = "24h"
    private const val BOUNDED_LOGCAT_BUFFERS = "main,system,crash"

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /** POSIX single-quote escaping: wrap in quotes and escape embedded quotes. */
    fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** Run [command] through `su -c`, forcibly killing it when [timeoutSec] elapses. */
    fun runSuCommand(command: String, timeoutSec: Long = SU_TIMEOUT_SEC): ShellResult = try {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ShellResult(-1, "", "su timeout")
        } else {
            ShellResult(process.exitValue(), stdout, stderr)
        }
    } catch (e: IOException) {
        ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ShellResult(-1, "", "interrupted")
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
     * Copy LSPosed log [dirs] into `<stagingDir>/lsposed`, preferring a direct
     * copy and falling back to a single `su` shell command
     * (mkdir/cp/chmod/chown) when the directories are not readable in-process.
     */
    fun copyLsposedLogs(
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
     * Dump `logcat -d` into `<stagingDir>/logcat/logcat_all.txt`. Bounded modes
     * capture the last [BOUNDED_LOGCAT_WINDOW] of the main/system/crash buffers;
     * full modes capture all buffers and may fall back to a root logcat via
     * [suFallback] when the direct dump yields nothing.
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
            val bounded = dumpCommandOutput(
                listOf("logcat", "-d", "-v", "threadtime", "-t", BOUNDED_LOGCAT_WINDOW, "-b", BOUNDED_LOGCAT_BUFFERS),
                output,
            )
            if (bounded) {
                details += "logcat: direct bounded $BOUNDED_LOGCAT_BUFFERS $BOUNDED_LOGCAT_WINDOW"
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
