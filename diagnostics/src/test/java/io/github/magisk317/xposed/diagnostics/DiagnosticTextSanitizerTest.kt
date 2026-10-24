package io.github.magisk317.xposed.diagnostics

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagnosticTextSanitizerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `sanitizes supported text logs and skips binary files`() {
        val log = File(tempDir, "runtime.jsonl").apply { writeText("token=secret") }
        val binary = File(tempDir, "image.png").apply { writeText("token=secret") }

        val redacted = DiagnosticTextSanitizer.sanitizeDirectory(tempDir, sanitizeText = { it.replace("secret", "***") })

        assertEquals(1, redacted)
        assertEquals("token=***\n", log.readText())
        assertEquals("token=secret", binary.readText())
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.startsWith(".sanitize_") })
    }

    @Test
    fun `keeps full oversized files and reports missing inputs`() {
        val oversized = File(tempDir, "large.log").apply { writeText("token=secret-full") }
        val warnings = mutableListOf<String>()
        assertTrue(
            DiagnosticTextSanitizer.sanitizeTextLogFile(
                file = oversized,
                sanitizeText = { it.replace("secret", "***") },
                maxFileBytes = 4L,
                onWarning = warnings::add,
            ),
        )
        assertTrue(oversized.exists())
        assertEquals("token=***-full\n", oversized.readText())
        assertTrue(warnings.any { it.contains("without truncation") })

        warnings.clear()
        val unreadable = File(tempDir, "missing.log")
        assertFalse(
            DiagnosticTextSanitizer.sanitizeTextLogFile(
                file = unreadable,
                sanitizeText = { "***" },
                onWarning = warnings::add,
            ),
        )
        assertTrue(warnings.single().startsWith("Skip missing diagnostic text file:"))
    }
}
