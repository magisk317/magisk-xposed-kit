package io.github.magisk317.xposed.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A parsed daily file name: the [base] stem, the encoded [dateText], and the 1-based [segment]. */
data class DailyFileRef(
    val base: String,
    val dateText: String,
    val segment: Int,
)

/**
 * Daily-rotating JSONL file persistence shared by all host repos.
 *
 * Owns the file mechanics only: daily file naming (`<stem>.<yyyy-MM-dd>.jsonl`), size-based
 * segment rotation inside a day (`<stem>.<yyyy-MM-dd>-N.jsonl`), append with parent-directory
 * creation, retention pruning driven by the date encoded in the file name, and date extraction
 * for sorting. The JSONL line schema stays with the caller - pass pre-encoded lines plus the
 * base file names (stems) the line belongs to, e.g. one primary stem plus one per-route
 * stem. Concrete directories, retention sources, and post-write hooks (e.g. relaxing
 * permission bits for cross-UID readers) stay in the host.
 *
 * Rotation is per stem and per day: the first segment of a day keeps the historical unsuffixed
 * name, the next one is opened once the active segment reached [SEGMENT_MAX_BYTES], and a day
 * never holds more than [MAX_SEGMENTS_PER_DAY] segments - the oldest segment is deleted first
 * and the segment being written is never a candidate. A single line larger than the segment
 * target still lands in the active segment: dropping log data to honour a size target is not an
 * option, and no empty segment is opened just to fit it. Cross-day cleanup stays with
 * [pruneExpired]; there is no cross-day byte pool, so yesterday's files can no longer squeeze
 * out today's writes.
 *
 * Append failures are swallowed per file to preserve the fire-and-forget sink contract; hosts
 * count write failures in their own diagnostics if needed.
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
     * Appends [line] to the active segment of every stem derived from [baseFileNames]. A trailing
     * newline is added when missing; a trailing `.jsonl`/`.log` suffix is stripped before the
     * date is inserted.
     */
    fun appendLine(
        logDir: File,
        baseFileNames: Collection<String>,
        timestampMs: Long,
        line: String,
        segmentMaxBytes: Long = SEGMENT_MAX_BYTES,
        maxSegmentsPerDay: Int = MAX_SEGMENTS_PER_DAY,
    ) {
        val date = dateText(timestampMs)
        val fullLine = if (line.endsWith("\n")) line else "$line\n"
        val lineBytes = fullLine.toByteArray(Charsets.UTF_8).size.toLong()
        baseFileNames.forEach { base ->
            runCatching {
                val stem = stemOf(base)
                val segments = logDir.listFiles()
                    .orEmpty()
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        parseDailyFileName(file.name)
                            ?.takeIf { it.base == stem && it.dateText == date }
                            ?.let { SegmentFile(it, file, file.length()) }
                    }
                    .sortedBy { it.ref.segment }
                val target = resolveSegmentFile(logDir, stem, date, segments, lineBytes, segmentMaxBytes)
                evictOverflowSegments(segments, target, maxSegmentsPerDay)
                target.parentFile?.takeIf { !it.exists() }?.mkdirs()
                target.appendText(fullLine)
                onFileWritten(target)
            }.onFailure {
                // Fire-and-forget by contract (class KDoc): a disk write must never fail the
                // caller that only wanted to record a line.
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
     * Start-of-day epoch ms encoded in a `<stem>.<yyyy-MM-dd>[-N].jsonl` name,
     * or null when the name carries no date (e.g. the bare stem file).
     */
    fun fileDateStartMs(fileName: String): Long? {
        val value = DAILY_FILE_REGEX.matchEntire(fileName)?.groupValues?.getOrNull(2) ?: return null
        return runCatching { dateFormat.get()?.parse(value)?.time }.getOrNull()
    }

    /**
     * Splits a daily file name into stem, encoded date and 1-based segment, or null when the name
     * is not a daily file. Segment 1 is reported for the unsuffixed historical name.
     */
    fun parseDailyFile(fileName: String): DailyFileRef? = parseDailyFileName(fileName)

    /**
     * Daily file name for [baseFileName] on [dateText]; [segment] 1 keeps the unsuffixed
     * historical name and higher segments carry a `-N` suffix.
     */
    fun dailyFileName(baseFileName: String, dateText: String, segment: Int = FIRST_SEGMENT): String {
        val stem = stemOf(baseFileName)
        return if (segment <= FIRST_SEGMENT) {
            "$stem.$dateText$JSONL_SUFFIX"
        } else {
            "$stem.$dateText$SEGMENT_SEPARATOR$segment$JSONL_SUFFIX"
        }
    }

    /**
     * Picks the file [line] belongs to: the active segment while it still has room for the line,
     * otherwise the next segment of the day. An empty active segment absorbs a line larger than
     * [segmentMaxBytes] instead of opening a segment that would start out oversized.
     */
    private fun resolveSegmentFile(
        logDir: File,
        stem: String,
        date: String,
        segments: List<SegmentFile>,
        lineBytes: Long,
        segmentMaxBytes: Long,
    ): File {
        val active = segments.lastOrNull()
            ?: return File(logDir, dailyFileName(stem, date, FIRST_SEGMENT))
        val rolls = segmentMaxBytes > 0L &&
            active.sizeBytes > 0L &&
            active.sizeBytes + lineBytes > segmentMaxBytes
        if (rolls) {
            return File(logDir, dailyFileName(stem, date, active.ref.segment + 1))
        }
        return active.file
    }

    /**
     * Keeps the day at [maxSegmentsPerDay] segments by deleting the oldest ones. [keep] is never a
     * candidate, so a day that just rolled to segment N+1 drops segment 1 instead of the live
     * segment; a segment that refuses to delete simply keeps its slot until the next sweep.
     */
    private fun evictOverflowSegments(
        segments: List<SegmentFile>,
        keep: File,
        maxSegmentsPerDay: Int,
    ) {
        if (maxSegmentsPerDay <= 0) return
        var count = segments.size
        if (segments.none { it.file == keep }) {
            count += 1
        }
        if (count <= maxSegmentsPerDay) return
        segments.forEach { segment ->
            if (count <= maxSegmentsPerDay) return
            if (segment.file == keep) return@forEach
            if (segment.file.delete()) {
                count -= 1
            }
        }
    }

    private fun dateText(timestampMs: Long): String =
        dateFormat.get()?.format(Date(timestampMs)) ?: timestampMs.toString()

    private fun stemOf(baseFileName: String): String =
        baseFileName.removeSuffix(JSONL_SUFFIX).removeSuffix(LOG_SUFFIX)

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

    private data class SegmentFile(
        val ref: DailyFileRef,
        val file: File,
        val sizeBytes: Long,
    )

    companion object {
        const val JSONL_SUFFIX: String = ".jsonl"
        const val LOG_SUFFIX: String = ".log"
        const val FILE_DATE_PATTERN: String = "yyyy-MM-dd"
        const val MIN_RETENTION_DAYS: Int = 1

        /** Segment 1 keeps the historical unsuffixed name; higher segments carry a `-N` suffix. */
        const val FIRST_SEGMENT: Int = 1
        private const val SEGMENT_SEPARATOR: String = "-"

        /** Default per-segment size target; a full segment rolls to `<stem>.<date>-N.jsonl`. */
        const val SEGMENT_MAX_BYTES: Long = 8L * 1024L * 1024L

        /** Default per-stem segment budget for one day; the oldest segment goes when exceeded. */
        const val MAX_SEGMENTS_PER_DAY: Int = 10

        /**
         * Matches `<stem>.<yyyy-MM-dd>.jsonl` with an optional `-N` segment suffix;
         * group 1 carries the stem, group 2 the encoded date, group 3 the segment.
         */
        val DAILY_FILE_REGEX: Regex = Regex("""^(.+)\.(\d{4}-\d{2}-\d{2})(?:-(\d+))?\.jsonl$""")

        /**
         * Splits a daily file name into stem, encoded date and 1-based segment, or null when the
         * name is not a daily file. Segment 1 is reported for the unsuffixed historical name.
         */
        fun parseDailyFileName(fileName: String): DailyFileRef? {
            val match = DAILY_FILE_REGEX.matchEntire(fileName) ?: return null
            val segment = match.groupValues.getOrNull(3)?.toIntOrNull() ?: FIRST_SEGMENT
            return DailyFileRef(
                base = match.groupValues[1],
                dateText = match.groupValues[2],
                segment = segment,
            )
        }

        /**
         * Orders daily file names by encoded date and then by segment number, so `-10` sorts
         * after `-2` instead of lexicographically before it.
         */
        val DAILY_FILE_ORDER: Comparator<String> =
            compareBy<String> { parseDailyFileName(it)?.dateText ?: it }
                .thenBy { parseDailyFileName(it)?.segment ?: FIRST_SEGMENT }
    }
}
