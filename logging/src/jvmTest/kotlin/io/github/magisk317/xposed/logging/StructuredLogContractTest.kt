package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StructuredLogContractTest {
    @Test
    fun `sanitizer redacts message attributes and structured throwable`() {
        MagiskLogPolicy.install(isDiagnosticBuild = false)
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
    fun `diagnostic sanitizer keeps bounded plaintext`() {
        try {
            MagiskLogPolicy.install(isDiagnosticBuild = true)
            val value = "token=plaintext-secret " + "x".repeat(200)
            val record = LogRecord(
                eventId = "diagnostic",
                timestampMs = 1L,
                level = LogLevel.INFO,
                tag = "test",
                message = value,
                attributes = mapOf("authorization" to value),
                error = LogError("E", value, value),
                runtimeContext = LogRuntimeContext(source = "test"),
            )

            val sanitized = StructuredLogSanitizer().sanitize(record)

            assertTrue(sanitized.message.startsWith("token=plaintext-secret"))
            assertTrue(sanitized.message.length <= DefaultLogSanitizer.LOG_MAX_LENGTH)
            assertTrue(sanitized.attributes.getValue("authorization").startsWith("token=plaintext-secret"))
            assertTrue(sanitized.error?.stacktrace.orEmpty().startsWith("token=plaintext-secret"))
        } finally {
            MagiskLogPolicy.install(isDiagnosticBuild = false)
        }
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
        MagiskLogPolicy.install(isDiagnosticBuild = true)
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val records = mutableListOf<LogRecord>()
        val dispatcher = LogDispatcher(
            sinks = listOf(StructuredLogSink { record ->
                synchronized(records) { records += record }
                sinkEntered.countDown()
                assertTrue(releaseSink.await(5, TimeUnit.SECONDS))
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
            assertTrue(sinkEntered.await(5, TimeUnit.SECONDS))
            // Enqueueing must return while the sink is blocked, before delivery is counted.
            assertEquals(0L, dispatcher.snapshot().deliveredCount)
            releaseSink.countDown()
            // A callback can signal before dispatch increments its counter. Wait for
            // the public terminal state instead of racing the tail of the callback.
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                while (dispatcher.snapshot().deliveredCount == 0L) {
                    Thread.sleep(1)
                }
            }
            assertEquals(1L, dispatcher.snapshot().deliveredCount)
            assertEquals(0L, dispatcher.snapshot().sinkFailureCount)
            assertEquals("hello", synchronized(records) { records.single().message })
        } finally {
            releaseSink.countDown()
            dispatcher.close()
            MagiskLogPolicy.install(isDiagnosticBuild = false)
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
    fun `dispatcher drops records when logging is disabled`() {
        MagiskLogPolicy.install(isDiagnosticBuild = false)
        var sinkCalls = 0
        val dispatcher = LogDispatcher(
            sinks = listOf(StructuredLogSink { sinkCalls += 1 }),
            queueCapacity = 1,
        )
        try {
            val result = dispatcher.dispatch(
                LogRecord(
                    eventId = "disabled",
                    timestampMs = 1L,
                    level = LogLevel.INFO,
                    tag = "test",
                    message = "token=plaintext-secret",
                    runtimeContext = LogRuntimeContext(source = "test"),
                ),
            )

            assertEquals(DeliveryResult.DISABLED, result)
            assertEquals(0, sinkCalls)
            assertEquals(1L, dispatcher.snapshot().droppedCount)
        } finally {
            dispatcher.close()
        }
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
