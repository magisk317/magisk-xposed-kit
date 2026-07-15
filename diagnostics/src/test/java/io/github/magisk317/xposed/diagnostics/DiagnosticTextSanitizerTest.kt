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

        DiagnosticTextSanitizer.sanitizeDirectory(tempDir, sanitizeText = { it.replace("secret", "***") })

        assertEquals("token=***", log.readText())
        assertEquals("token=secret", binary.readText())
    }

    @Test
    fun `removes oversized files and reports missing inputs`() {
        val oversized = File(tempDir, "large.log").apply { writeText("secret") }
        val warnings = mutableListOf<String>()
        assertTrue(
            DiagnosticTextSanitizer.sanitizeTextLogFile(
                file = oversized,
                sanitizeText = { "***" },
                maxFileBytes = oversized.length() - 1,
                onWarning = warnings::add,
            ),
        )
        assertFalse(oversized.exists())
        assertTrue(warnings.single().startsWith("Removed oversized diagnostic text file:"))

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
