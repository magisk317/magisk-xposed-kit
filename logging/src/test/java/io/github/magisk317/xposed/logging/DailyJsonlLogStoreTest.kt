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
    fun `fileDateStartMs parses the encoded date or returns null`() {
        val now = System.currentTimeMillis()
        val name = "runtime.${dateText(now)}.jsonl"
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateText(now))?.time

        assertEquals(expected, store.fileDateStartMs(name))
        assertNull(store.fileDateStartMs("runtime.jsonl"))
        assertNull(store.fileDateStartMs("runtime.2026-13-99.jsonl"))
    }
}
