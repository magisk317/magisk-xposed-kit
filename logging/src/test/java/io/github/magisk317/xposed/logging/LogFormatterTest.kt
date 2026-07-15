package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogFormatterTest {

    @Test
    fun `formats message with arguments`() {
        val result = LogFormatter.formatArgs("value=%d", arrayOf(42))
        assertEquals("value=42", result)
    }

    @Test
    fun `returns original message when formatting fails`() {
        val result = LogFormatter.formatArgs("value=%d", arrayOf("not-a-number"))
        assertEquals("value=%d", result)
    }

    @Test
    fun `appends stack trace when throwable provided`() {
        val throwable = IllegalStateException("boom")
        val result = LogFormatter.formatArgs("failure", arrayOf(throwable))
        assertTrue(result.startsWith("failure\n"))
        assertTrue(result.contains("IllegalStateException"))
    }
}
