package io.github.magisk317.xposed.diagnostics

import io.github.magisk317.xposed.logging.MagiskLogPolicy
import java.io.File
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagnosticShellTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a release build never touches the root-heavy lsposed logs`() {
        val stagingDir = File(tempDir, "staging").apply { mkdirs() }
        val details = mutableListOf<String>()

        DiagnosticShell.copyLsposedLogs(
            stagingDir = stagingDir,
            details = details,
            mode = DiagnosticExportMode.of(
                MagiskLogPolicy.evaluate(isDebugBuild = false),
            ),
        )

        assertEquals(listOf("lsposed: skipped by export mode STANDARD"), details)
        assertFalse(File(stagingDir, "lsposed").exists())
    }

    @Test
    fun `rotated lsposed logs are part of the documented directory set`() {
        assertEquals(
            listOf("/data/adb/lspd/log", "/data/adb/lspd/log.old"),
            DiagnosticShell.lsposedLogDirs,
        )
    }

    @Test
    fun `unreachable lsposed directories are reported instead of silently empty`() {
        val stagingDir = File(tempDir, "staging").apply { mkdirs() }
        val details = mutableListOf<String>()

        DiagnosticShell.copyLsposedLogsFrom(
            dirs = listOf(File(tempDir, "absent").absolutePath),
            stagingDir = stagingDir,
            details = details,
        )

        assertTrue(details.single().startsWith("lsposed su failed:"))
    }

    @Test
    fun `bounded logcat window is an absolute device-local timestamp`() {
        val nowMillis = 1_792_713_600_000L

        assertEquals(
            "2026-10-22 00:00:00.000",
            DiagnosticShell.boundedLogcatSinceArg(nowMillis, TimeZone.getTimeZone("UTC")),
        )
        assertEquals(
            "2026-10-22 08:00:00.000",
            DiagnosticShell.boundedLogcatSinceArg(nowMillis, TimeZone.getTimeZone("Asia/Shanghai")),
        )
    }

    @Test
    fun `shell quoting cannot be escaped out of a single-quoted argument`() {
        assertEquals("''", DiagnosticShell.shQuote(""))
        assertEquals("""'a'"'"'b'""", DiagnosticShell.shQuote("a'b"))
        assertEquals("""'/data/adb/lspd/log'""", DiagnosticShell.shQuote("/data/adb/lspd/log"))
    }
}
