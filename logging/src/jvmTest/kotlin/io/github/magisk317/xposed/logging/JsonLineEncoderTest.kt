package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonLineEncoderTest {
    @Test
    fun `encodes ordered fields with JSON string escapes and optional fields`() {
        val line = JsonLineEncoder.encode(
            JsonLineField.string("time", "2026-08-21 12:34:56.789"),
            JsonLineField.string("message", "quote\" slash\\ line\n tab\t control\u0001"),
            JsonLineField.string("omitted", "not-written", include = false),
            JsonLineField.number("pid", 42),
        )

        assertEquals(
            """{"time":"2026-08-21 12:34:56.789","message":"quote\" slash\\ line\n tab\t control\u0001","pid":42}""",
            line,
        )
    }

    @Test
    fun `rejects non JSON numbers`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonLineField.number("value", Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonLineField.number("value", Double.POSITIVE_INFINITY)
        }
    }
}
