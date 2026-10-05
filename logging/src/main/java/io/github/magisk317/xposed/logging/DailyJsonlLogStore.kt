package io.github.magisk317.xposed.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Daily-rotating JSONL file persistence shared by all host repos.
 *
 * Owns the file mechanics only: daily file naming (`<stem>.<yyyy-MM-dd>.jsonl`),
 * append with parent-directory creation, retention pruning driven by the date
 * encoded in the file name, and date extraction for sorting. The JSONL line
 * schema stays with the caller - pass pre-encoded lines plus the base file
 * names (stems) the line belongs to, e.g. one primary stem plus one per-route
 * stem. Concrete directories, retention sources, and post-write hooks (e.g.
 * relaxing permission bits for cross-UID readers) stay in the host.
 *
 * Append failures are swallowed per file to preserve the fire-and-forget sink
 * contract; hosts count write failures in their own diagnostics if needed.
 */
class DailyJsonlLogStore(
    private val onFileWritten: (File) -> Unit = {},
) {
    // SimpleDateFormat is not thread-safe; append/prune may run on any thread.
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat(FILE_DATE_PATTERN, Locale.US).apply { isLenient = false }
    }

    /**
     * Appends [line] to the daily file of every stem derived from
     * [baseFileNames]. A trailing newline is added when missing; a trailing
     * `.jsonl`/`.log` suffix is stripped before the date is inserted.
     */
    fun appendLine(logDir: File, baseFileNames: Collection<String>, timestampMs: Long, line: String) {
        val date = dateText(timestampMs)
        val fullLine = if (line.endsWith("\n")) line else "$line\n"
        baseFileNames.forEach { base ->
            runCatching {
                val file = File(logDir, dailyFileName(base, date))
                file.parentFile?.takeIf { !it.exists() }?.mkdirs()
                file.appendText(fullLine)
                onFileWritten(file)
            }
        }
    }

    /** Deletes managed files whose encoded date (or lastModified fallback) is before the retention cutoff. */
    fun pruneExpired(logDir: File, nowMs: Long, retentionDays: Int, isManagedFile: (String) -> Boolean): Int {
        val cutoff = retentionCutoffStartMs(nowMs, retentionDays)
        var deleted = 0
        logDir.listFiles()
            .orEmpty()
            .filter { it.isFile && isManagedFile(it.name) }
            .forEach { file ->
                val fileTime = fileDateStartMs(file.name) ?: file.lastModified()
                if (fileTime > 0L && fileTime < cutoff) {
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        deleted += 1
                    }
                }
            }
        return deleted
    }

    /**
     * Start-of-day epoch ms encoded in a `<stem>.<yyyy-MM-dd>.jsonl` name,
     * or null when the name carries no date (e.g. the bare stem file).
     */
    fun fileDateStartMs(fileName: String): Long? {
        val value = FILE_DATE_SUFFIX_REGEX.matchEntire(fileName)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { dateFormat.get()?.parse(value)?.time }.getOrNull()
    }

    fun dailyFileName(baseFileName: String, dateText: String): String {
        val stem = baseFileName
            .removeSuffix(JSONL_SUFFIX)
            .removeSuffix(LOG_SUFFIX)
        return "$stem.$dateText$JSONL_SUFFIX"
    }

    private fun dateText(timestampMs: Long): String =
        dateFormat.get()?.format(Date(timestampMs)) ?: timestampMs.toString()

    private fun retentionCutoffStartMs(nowMs: Long, retentionDays: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(retentionDays.coerceAtLeast(MIN_RETENTION_DAYS) - 1))
        }.timeInMillis
    }

    companion object {
        const val JSONL_SUFFIX: String = ".jsonl"
        const val LOG_SUFFIX: String = ".log"
        const val FILE_DATE_PATTERN: String = "yyyy-MM-dd"
        const val MIN_RETENTION_DAYS: Int = 1

        /** Matches `<stem>.<yyyy-MM-dd>.jsonl`; group 1 carries the encoded date. */
        val FILE_DATE_SUFFIX_REGEX: Regex = Regex("""^(?:.+)\.(\d{4}-\d{2}-\d{2})\.jsonl$""")
    }
}
