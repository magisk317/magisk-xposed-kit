package io.github.magisk317.xposed.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CrashLogRecorderTest {
    @TempDir
    lateinit var tempDir: File

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var pruneCalls = 0

    @BeforeEach
    fun captureHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashLogRecorder.uninstall()
    }

    @AfterEach
    fun restoreHandler() {
        CrashLogRecorder.uninstall()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        MagiskLogPolicy.install(isDiagnosticBuild = false)
    }

    private fun recorderFor(dir: File, prune: (File) -> Unit = {}) {
        CrashLogRecorder.install(dirProvider = { dir }, prune = prune)
    }

    @Test
    fun `release policy does not install or write crash logs`() {
        MagiskLogPolicy.install(isDiagnosticBuild = false)
        val dir = File(tempDir, "crash")
        recorderFor(dir)

        val file = CrashLogRecorder.record(
            thread = Thread.currentThread(),
            error = IllegalStateException("content=你的验证码是 123456 from=13800138000"),
        )

        assertEquals(null, file)
        assertFalse(dir.exists())
    }

    @Test
    fun `verbose policy keeps the crash payload raw`() {
        MagiskLogPolicy.install(isDiagnosticBuild = true)
        recorderFor(File(tempDir, "raw"))

        val file = CrashLogRecorder.record(
            thread = Thread.currentThread(),
            error = IllegalStateException("content=你的验证码是 123456 from=13800138000"),
        )

        assertNotNull(file)
        val lines = file!!.readLines()
        assertTrue(lines.any { it.contains("13800138000") })
        assertTrue(lines.all { it.length <= DefaultLogSanitizer.LOG_MAX_LENGTH })
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test
    fun `the handler chains the previous default handler and prunes first`() {
        MagiskLogPolicy.install(isDiagnosticBuild = true)
        var chained = 0
        val dir = File(tempDir, "chained")
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained += 1 }
        recorderFor(dir, prune = { pruneCalls += 1 })

        Thread.getDefaultUncaughtExceptionHandler()
            ?.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(1, chained)
        assertEquals(1, pruneCalls)
        assertTrue(dir.walkTopDown().any { it.isFile })
    }

    @Test
    fun `no crash directory means no file`() {
        MagiskLogPolicy.install(isDiagnosticBuild = true)
        CrashLogRecorder.install(dirProvider = { null })

        assertEquals(null, CrashLogRecorder.record(Thread.currentThread(), IllegalStateException("boom")))
    }

    @Test
    fun `crash files age out on the name date, not on the copy time`() {
        val now = System.currentTimeMillis()
        val dir = File(tempDir, "aged").apply { mkdirs() }
        val today = File(dir, "Crash_${day(now)}.txt").apply { writeText("today") }
        val stale = File(dir, "Crash_${day(now - 10L * DAY_MILLIS)}.txt").apply { writeText("stale") }
        val foreign = File(dir, "notes.txt").apply { writeText("keep") }
        val unstamped = File(dir, "Crash_not-a-date.txt").apply { writeText("keep") }

        val deleted = CrashLogRecorder.pruneExpired(dir, retentionDays = 2, nowMs = now)

        assertEquals(1, deleted)
        assertTrue(today.isFile)
        assertFalse(stale.isFile)
        assertTrue(foreign.isFile)
        assertTrue(unstamped.isFile)
    }

    private fun day(timeMs: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMs))

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
