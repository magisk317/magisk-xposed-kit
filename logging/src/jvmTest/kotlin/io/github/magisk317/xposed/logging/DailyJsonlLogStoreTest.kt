package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyJsonlLogStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val store = DailyJsonlLogStore()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Fixed instant so segment assertions do not depend on the wall clock. */
    private val fixedNow = 1_800_000_000_000L

    private fun dateText(ts: Long): String = dateFormat.format(Date(ts))

    @Test
    fun `appendLine strips suffixes and writes one line per stem`() {
        val written = mutableListOf<File>()
        val probe = DailyJsonlLogStore(onFileWritten = { written.add(it) })
        val now = System.currentTimeMillis()

        probe.appendLine(
            logDir = tempDir,
            baseFileNames = listOf("runtime.jsonl", "runtime.sms_hook.jsonl", "reader.log"),
            timestampMs = now,
            line = """{"timestamp":1}""",
        )

        val expectedDate = dateText(now)
        val primary = File(tempDir, "runtime.$expectedDate.jsonl")
        val route = File(tempDir, "runtime.sms_hook.$expectedDate.jsonl")
        val log = File(tempDir, "reader.$expectedDate.jsonl")
        assertEquals(listOf("""{"timestamp":1}"""), primary.readLines())
        assertEquals(listOf("""{"timestamp":1}"""), route.readLines())
        assertEquals(listOf("""{"timestamp":1}"""), log.readLines())
        assertEquals(setOf(primary, route, log), written.toSet())
    }

    @Test
    fun `appendLine rotates to a new file when the date changes`() {
        val now = System.currentTimeMillis()
        val nextDay = now + 26L * 60 * 60 * 1000

        store.appendLine(tempDir, listOf("runtime.jsonl"), now, "first")
        store.appendLine(tempDir, listOf("runtime.jsonl"), nextDay, "second")

        assertEquals(listOf("first"), File(tempDir, "runtime.${dateText(now)}.jsonl").readLines())
        assertEquals(listOf("second"), File(tempDir, "runtime.${dateText(nextDay)}.jsonl").readLines())
    }

    @Test
    fun `appendLine rolls to a numbered segment when the active segment is full`() {
        val line = "x".repeat(40)
        store.appendLine(tempDir, listOf("runtime.jsonl"), fixedNow, line, segmentMaxBytes = 48L)
        store.appendLine(tempDir, listOf("runtime.jsonl"), fixedNow, line, segmentMaxBytes = 48L)

        val date = dateText(fixedNow)
        val primary = File(tempDir, "runtime.$date.jsonl")
        val second = File(tempDir, "runtime.$date-2.jsonl")
        assertTrue(primary.isFile)
        assertTrue(second.isFile)
        assertEquals(41L, primary.length())
        assertEquals(41L, second.length())
    }

    @Test
    fun `appendLine evicts the oldest segments beyond the daily budget`() {
        val line = "y".repeat(40)
        repeat(5) {
            store.appendLine(
                logDir = tempDir,
                baseFileNames = listOf("runtime.jsonl"),
                timestampMs = fixedNow,
                line = line,
                segmentMaxBytes = 48L,
                maxSegmentsPerDay = 3,
            )
        }

        val date = dateText(fixedNow)
        val remaining = tempDir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(
            listOf("runtime.$date-3.jsonl", "runtime.$date-4.jsonl", "runtime.$date-5.jsonl"),
            remaining,
        )
    }

    @Test
    fun `appendLine keeps an oversized single line in the active segment`() {
        store.appendLine(tempDir, listOf("runtime.jsonl"), fixedNow, "z".repeat(200), segmentMaxBytes = 48L)
        store.appendLine(tempDir, listOf("runtime.jsonl"), fixedNow, "small", segmentMaxBytes = 48L)

        val date = dateText(fixedNow)
        val primary = File(tempDir, "runtime.$date.jsonl")
        assertTrue(primary.isFile)
        assertEquals(201L, primary.length())
        assertTrue(File(tempDir, "runtime.$date-2.jsonl").isFile)
    }

    @Test
    fun `appendLine rolls each stem independently`() {
        store.appendLine(tempDir, listOf("runtime.jsonl", "runtime.manager.jsonl"), fixedNow, "a".repeat(40), segmentMaxBytes = 48L)
        store.appendLine(tempDir, listOf("runtime.jsonl", "runtime.manager.jsonl"), fixedNow, "b".repeat(40), segmentMaxBytes = 48L)
        store.appendLine(tempDir, listOf("runtime.jsonl"), fixedNow, "c".repeat(40), segmentMaxBytes = 48L)

        val date = dateText(fixedNow)
        assertTrue(File(tempDir, "runtime.$date-2.jsonl").isFile)
        assertTrue(File(tempDir, "runtime.manager.$date-2.jsonl").isFile)
        assertEquals(
            listOf("a".repeat(40)),
            File(tempDir, "runtime.$date.jsonl").readLines(),
        )
    }

    @Test
    fun `pruneExpired deletes files older than the retention window`() {
        val now = System.currentTimeMillis()
        val staleDate = dateText(now - 400L * 24 * 60 * 60 * 1000)
        val staleTs = now - 400L * 24 * 60 * 60 * 1000

        val staleDated = File(tempDir, "runtime.$staleDate.jsonl").apply { writeText("old\n") }
        val bareStem = File(tempDir, "runtime.jsonl").apply {
            writeText("old\n")
            setLastModified(staleTs)
        }
        val today = File(tempDir, "runtime.${dateText(now)}.jsonl").apply { writeText("new\n") }
        val unmanaged = File(tempDir, "other.$staleDate.jsonl").apply { writeText("keep\n") }

        val deleted = store.pruneExpired(
            logDir = tempDir,
            nowMs = now,
            retentionDays = 1,
            isManagedFile = { it.startsWith("runtime") },
        )

        assertEquals(2, deleted)
        assertTrue(!staleDated.exists())
        assertTrue(!bareStem.exists())
        assertTrue(today.exists())
        assertTrue(unmanaged.exists())
    }

    @Test
    fun `pruneExpired reads the date out of segment names`() {
        val now = System.currentTimeMillis()
        val staleDate = dateText(now - 400L * 24 * 60 * 60 * 1000)

        val staleSegment = File(tempDir, "runtime.$staleDate-3.jsonl").apply { writeText("old\n") }
        val todaySegment = File(tempDir, "runtime.${dateText(now)}-2.jsonl").apply { writeText("new\n") }

        val deleted = store.pruneExpired(
            logDir = tempDir,
            nowMs = now,
            retentionDays = 2,
            isManagedFile = { it.startsWith("runtime") },
        )

        assertEquals(1, deleted)
        assertTrue(!staleSegment.exists())
        assertTrue(todaySegment.exists())
    }

    @Test
    fun `fileDateStartMs parses the encoded date or returns null`() {
        val now = System.currentTimeMillis()
        val name = "runtime.${dateText(now)}.jsonl"
        val segmentName = "runtime.${dateText(now)}-7.jsonl"
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateText(now))?.time

        assertEquals(expected, store.fileDateStartMs(name))
        assertEquals(expected, store.fileDateStartMs(segmentName))
        assertNull(store.fileDateStartMs("runtime.jsonl"))
        assertNull(store.fileDateStartMs("runtime.2026-13-99.jsonl"))
    }

    @Test
    fun `parseDailyFile reads stem, date and segment`() {
        val date = dateText(fixedNow)

        assertEquals(DailyFileRef("runtime", date, 1), store.parseDailyFile("runtime.$date.jsonl"))
        assertEquals(DailyFileRef("runtime", date, 2), store.parseDailyFile("runtime.$date-2.jsonl"))
        assertEquals(DailyFileRef("runtime", date, 10), store.parseDailyFile("runtime.$date-10.jsonl"))
        assertEquals(DailyFileRef("runtime.manager", date, 1), store.parseDailyFile("runtime.manager.$date.jsonl"))
        assertEquals(DailyFileRef("runtime.sms_hook", date, 3), store.parseDailyFile("runtime.sms_hook.$date-3.jsonl"))
        assertNull(store.parseDailyFile("runtime.jsonl"))
        assertNull(store.parseDailyFile("runtime.latest.jsonl"))
        assertNull(store.parseDailyFile("crash.log"))
    }

    @Test
    fun `daily file order sorts by date then numeric segment`() {
        val names = listOf(
            "runtime.2027-02-01-10.jsonl",
            "runtime.2027-02-01-2.jsonl",
            "runtime.2027-02-01.jsonl",
            "runtime.2027-01-31-5.jsonl",
        )

        assertEquals(
            listOf(
                "runtime.2027-01-31-5.jsonl",
                "runtime.2027-02-01.jsonl",
                "runtime.2027-02-01-2.jsonl",
                "runtime.2027-02-01-10.jsonl",
            ),
            names.sortedWith(DailyJsonlLogStore.DAILY_FILE_ORDER),
        )
    }

    @Test
    fun `dailyFileName keeps the first segment unsuffixed`() {
        assertEquals("runtime.2027-02-01.jsonl", store.dailyFileName("runtime.jsonl", "2027-02-01"))
        assertEquals("runtime.2027-02-01-2.jsonl", store.dailyFileName("runtime.jsonl", "2027-02-01", 2))
        assertEquals("runtime.manager.2027-02-01.jsonl", store.dailyFileName("runtime.manager.jsonl", "2027-02-01"))
    }
}
