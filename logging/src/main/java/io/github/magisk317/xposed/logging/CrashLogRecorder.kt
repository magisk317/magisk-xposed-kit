package io.github.magisk317.xposed.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions into a host-supplied crash directory so an exported bundle
 * actually contains a `crash/` payload instead of an empty folder.
 *
 * The previous default handler is always chained, so the platform still crashes,
 * anr's and reports the process exactly as before.
 */
object CrashLogRecorder {
    private const val FILE_PREFIX = "Crash_"
    private const val DATE_PATTERN = "yyyy-MM-dd"
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val DATE_IN_NAME_LENGTH = 10

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var crashDirProvider: (() -> File?)? = null

    @Volatile
    private var installed = false

    /**
     * Take over the default uncaught-exception handler. [prune] runs before each write so the
     * host keeps crash files under the same retention it applies to its other logs.
     */
    @JvmOverloads
    fun install(
        dirProvider: () -> File?,
        prune: (File) -> Unit = {},
    ) {
        if (installed) return
        installed = true
        crashDirProvider = dirProvider
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error, prune) }.onFailure {
                // A crash recorder that throws would swallow the very crash it is writing.
                // The previous handler still receives the error on the next line.
            }
            previousHandler?.uncaughtException(thread, error)
        }
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        crashDirProvider = null
        previousHandler = null
    }

    /** Append [error] to today's crash file; returns null when nothing could be written. */
    fun record(thread: Thread, error: Throwable, prune: (File) -> Unit = {}): File? {
        val dir = crashDirProvider?.invoke() ?: return null
        return writeCrash(dir, thread, error, prune)
    }

    /**
     * Delete crash files whose date is older than the retention window and return how many
     * were removed. The date is read from the file name, so a bundle written yesterday is
     * pruned by age rather than by the time it happened to be copied.
     */
    @JvmOverloads
    fun pruneExpired(dir: File, retentionDays: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = dayStamp(nowMs - MagiskLogPolicy.retentionDays(retentionDays) * DAY_MILLIS)
        var deleted = 0
        dir.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (!file.isFile || !name.startsWith(FILE_PREFIX)) return@forEach
            if (name.length < FILE_PREFIX.length + DATE_IN_NAME_LENGTH) return@forEach
            val stamped = name.substring(FILE_PREFIX.length, FILE_PREFIX.length + DATE_IN_NAME_LENGTH)
            if (stamped < cutoff && runCatching { file.delete() }.getOrDefault(false)) {
                deleted += 1
            }
        }
        return deleted
    }

    private fun dayStamp(timeMs: Long): String =
        SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date(timeMs))

    internal fun writeCrash(
        dir: File,
        thread: Thread,
        error: Throwable,
        prune: (File) -> Unit,
    ): File? = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return null
        prune(dir)
        val stamp = SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp.txt")
        val time = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date())
        val text = buildString {
            appendLine("$time [ERROR] Uncaught on ${thread.name}: ${error.javaClass.name}: ${error.message}")
            appendLine(error.stackTraceToString())
        }
        val payload = if (MagiskLogPolicy.current().sanitizesText) {
            DefaultLogSanitizer.sanitizeUnbounded(text)
        } else {
            text
        }
        file.appendText(payload)
        file
    }.getOrNull()
}
