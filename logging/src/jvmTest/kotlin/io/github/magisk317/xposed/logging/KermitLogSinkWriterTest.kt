package io.github.magisk317.xposed.logging

import co.touchlab.kermit.Severity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KermitLogSinkWriterTest {
    @AfterEach
    fun tearDown() {
        LogSanitizerConfig.setEnabled(false)
    }

    @Test
    fun `writer sanitizes message and throwable before sink`() {
        LogSanitizerConfig.setEnabled(true)
        val token = "writer-token-secret-123456"
        val bearer = "writer-bearer-secret-123456"
        val phone = "13800138000"
        val events = mutableListOf<LogEvent>()
        val writer = KermitLogSinkWriter(
            sink = LogSink { events += it },
        )

        writer.log(
            severity = Severity.Error,
            message = "token=$token Authorization: Bearer $bearer phone=$phone",
            tag = "SanitizerContract",
            throwable = IllegalStateException(
                "stack token=$token Authorization: Bearer $bearer phone=$phone",
            ),
        )

        val event = events.single()
        assertFalse(event.message.contains(token))
        assertFalse(event.message.contains(bearer))
        assertFalse(event.message.contains(phone))
        assertNull(event.throwable)
        val throwableText = requireNotNull(event.throwableText)
        assertFalse(throwableText.contains(token))
        assertFalse(throwableText.contains(bearer))
        assertFalse(throwableText.contains(phone))
        assertTrue(throwableText.contains("IllegalStateException"))
    }
}
