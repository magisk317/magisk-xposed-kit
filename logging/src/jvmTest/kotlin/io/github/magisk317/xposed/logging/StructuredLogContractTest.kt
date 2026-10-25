package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StructuredLogContractTest {
    @Test
    fun `sanitizer redacts message attributes and structured throwable`() {
        val record = LogRecord(
            eventId = "event",
            timestampMs = 1L,
            level = LogLevel.ERROR,
            tag = "test",
            message = "token=plaintext-secret",
            attributes = mapOf("password" to "plaintext-secret"),
            error = LogError(
                type = "IllegalStateException",
                message = "token=plaintext-secret",
                stacktrace = "token=plaintext-secret",
            ),
            runtimeContext = LogRuntimeContext(source = "test"),
        )

        val sanitized = StructuredLogSanitizer().sanitize(record)
        assertFalse(sanitized.message.contains("plaintext-secret"))
        assertFalse(sanitized.attributes.getValue("password").contains("plaintext-secret"))
        assertFalse(sanitized.error?.stacktrace.orEmpty().contains("plaintext-secret"))
    }

    @Test
    fun `sanitizer truncates UTF-8 without splitting a code point`() {
        val value = "短信验证码".repeat(100)
        val result = StructuredLogSanitizer.truncateUtf8(value, 17)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 17)
        assertFalse(result.contains('�'))
    }

    @Test
    fun `dispatcher is asynchronous and exposes delivery counters`() {
        val delivered = CountDownLatch(1)
        val records = mutableListOf<LogRecord>()
        val dispatcher = LogDispatcher(
            sinks = listOf(StructuredLogSink { record ->
                synchronized(records) { records += record }
                delivered.countDown()
            }),
            queueCapacity = 4,
        )
        try {
            val result = dispatcher.dispatch(
                LogRecord(
                    eventId = "event",
                    timestampMs = 1L,
                    level = LogLevel.INFO,
                    tag = "test",
                    message = "hello",
                    runtimeContext = LogRuntimeContext(source = "test"),
                ),
            )
            assertEquals(DeliveryResult.ENQUEUED, result)
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, dispatcher.snapshot().deliveredCount)
            assertEquals("hello", synchronized(records) { records.single().message })
        } finally {
            dispatcher.close()
        }
        assertEquals(DeliveryResult.CLOSED, dispatcher.dispatch(
            LogRecord(
                eventId = "closed",
                timestampMs = 1L,
                level = LogLevel.INFO,
                tag = "test",
                message = "closed",
                runtimeContext = LogRuntimeContext(source = "test"),
            ),
        ))
    }

    @Test
    fun `jsonl encoder preserves structured error and attributes`() {
        val json = LogRecordJsonLineEncoder.encode(
            LogRecord(
                eventId = "event",
                timestampMs = 1L,
                level = LogLevel.ERROR,
                tag = "test",
                route = "nms_hook",
                message = "failed",
                attributes = mapOf("provider" to "nms"),
                error = LogError("E", "boom", "stack"),
                runtimeContext = LogRuntimeContext(source = "test"),
            ),
        )
        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"attributes\":{\"provider\":\"nms\"}"))
        assertTrue(json.contains("\"error\":{\"type\":\"E\""))
    }
}
