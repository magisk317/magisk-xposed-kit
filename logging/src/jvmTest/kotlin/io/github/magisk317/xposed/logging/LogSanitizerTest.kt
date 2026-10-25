package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogSanitizerTest {
    @Test
    fun `sanitizes phone numbers`() {
        val result = DefaultLogSanitizer.sanitize("联系 13800138000 完成验证")
        assertFalse(result.contains("13800138000"))
        assertTrue(result.contains("138"))
    }

    @Test
    fun `sanitizes secrets`() {
        val result = DefaultLogSanitizer.sanitize("token=abcdef123456")
        assertTrue(result.contains("token="))
        assertFalse(result.contains("abcdef123456"))
    }

    @Test
    fun `truncates overly long messages`() {
        val result = DefaultLogSanitizer.sanitize("x".repeat(2000))
        assertTrue(result.length <= 900)
    }

    @Test
    fun `masks push identifier fields`() {
        val result = DefaultLogSanitizer.sanitize(
            "regId=abc123def456 appKey=topsecretkey accessId=1234567890 imei=356938035643809",
        )
        assertFalse(result.contains("abc123def456"))
        assertFalse(result.contains("topsecretkey"))
        assertFalse(result.contains("1234567890"))
        assertFalse(result.contains("356938035643809"))
        assertTrue(result.contains("regId="))
        assertTrue(result.contains("appKey="))
    }

    @Test
    fun `redactArg edge-masks high-entropy bare values`() {
        val result = DefaultLogSanitizer.redactArg("abcdefghijklmnopqrstuvwxyz")
        assertFalse(result.contains("abcdefghijklmnopqrstuvwxyz"))
        assertTrue(result.startsWith("abcd"))
        assertTrue(result.endsWith("wxyz"))
    }

    @Test
    fun `redactArg keeps short or spaced values readable`() {
        assertEquals("hello world here", DefaultLogSanitizer.redactArg("hello world here"))
        assertEquals("short", DefaultLogSanitizer.redactArg("short"))
        assertEquals("true", DefaultLogSanitizer.redactArg(true))
    }

    @Test
    fun `sanitizeIfEnabled respects the shared switch`() {
        try {
            LogSanitizerConfig.setEnabled(false)
            assertEquals("token=abcdef123456", DefaultLogSanitizer.sanitizeIfEnabled("token=abcdef123456"))

            LogSanitizerConfig.setEnabled(true)
            assertFalse(DefaultLogSanitizer.sanitizeIfEnabled("token=abcdef123456").contains("abcdef123456"))
        } finally {
            LogSanitizerConfig.setEnabled(false)
        }
    }

    @Test
    fun `sanitizeIfEnabled caps plaintext when sanitization is disabled`() {
        try {
            LogSanitizerConfig.setEnabled(false)
            val message = "token=abcdef123456 " + "x".repeat(2000)

            val result = DefaultLogSanitizer.sanitizeIfEnabled(message)

            assertTrue(result.startsWith("token=abcdef123456 "))
            assertFalse(result.contains("x".repeat(801)))
            assertTrue(result.endsWith("...(len=${message.length})"))
        } finally {
            LogSanitizerConfig.setEnabled(false)
        }
    }

    @Test
    fun `a verbose build turns redaction off`() {
        try {
            MagiskLogPolicy.install(isDebugBuild = true)

            assertFalse(LogSanitizerConfig.isEnabled())
        } finally {
            LogSanitizerConfig.setEnabled(true)
        }
    }

    @Test
    fun `a release build keeps redaction on`() {
        try {
            MagiskLogPolicy.install(isDebugBuild = false)

            assertTrue(LogSanitizerConfig.isEnabled())
        } finally {
            LogSanitizerConfig.setEnabled(true)
        }
    }

    @Test
    fun `the switch alone decides whether sanitizeIfEnabled redacts`() {
        LogSanitizerConfig.setEnabled(false)

        val sanitized = DefaultLogSanitizer.sanitizeIfEnabled("from=13800138000")

        assertEquals("from=13800138000", sanitized)
        LogSanitizerConfig.setEnabled(true)
        assertFalse(DefaultLogSanitizer.sanitizeIfEnabled("from=13800138000").contains("13800138000"))
    }
}
