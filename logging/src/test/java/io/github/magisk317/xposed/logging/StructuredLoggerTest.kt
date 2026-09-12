package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredLoggerTest {
    private class FakeClock(
        var wall: Long = 100L,
        var monotonic: Long = 100L,
    ) : LogClock {
        override fun wallTimeMs(): Long = wall
        override fun monotonicTimeMs(): Long = monotonic
    }

    @Test
    fun `scope preserves route and per call tag while retaining structured throwable`() {
        val records = mutableListOf<LogRecord>()
        val clock = FakeClock()
        val logger = StructuredLogger.create(
            source = "test",
            tag = "root",
            sink = StructuredLogSink(records::add),
            route = "hook",
            clock = clock,
            eventIdFactory = { "event-1" },
        ).withTag("NmsHook").withRoute("nms_hook")

        val failure = IllegalStateException("boom")
        assertTrue(logger.error("hook failed", cause = failure))

        val record = records.single()
        assertEquals("event-1", record.eventId)
        assertEquals(LogLevel.ERROR, record.level)
        assertEquals("NmsHook", record.tag)
        assertEquals("nms_hook", record.route)
        assertEquals("hook failed", record.message)
        assertEquals("test", record.runtimeContext.source)
        assertEquals(clock.wall, record.timestampMs)
        assertNotNull(record.error)
        assertEquals(IllegalStateException::class.java.name, record.error?.type)
        assertTrue(record.error?.stacktrace.orEmpty().contains("boom"))
        assertTrue(record.force)
    }

    @Test
    fun `min level filters debug but force keeps warning`() {
        val records = mutableListOf<LogRecord>()
        val logger = StructuredLogger.create(
            source = "test",
            tag = "test",
            sink = StructuredLogSink(records::add),
            minLevel = LogLevel.INFO,
        )

        assertFalse(logger.debug("hidden"))
        assertTrue(logger.warn("visible"))
        assertEquals(listOf(LogLevel.WARN), records.map(LogRecord::level))
    }

    @Test
    fun `producer limiter returns suppressed count and keeps key set bounded`() {
        val records = mutableListOf<LogRecord>()
        val clock = FakeClock()
        val limiter = FixedWindowLogLimiter(maxEvents = 1, windowMs = 1_000L, maxTrackedKeys = 2)
        val logger = StructuredLogger.create(
            source = "test",
            tag = "test",
            sink = StructuredLogSink(records::add),
            limiter = limiter,
            clock = clock,
        )

        assertTrue(logger.debugLimited("a", "first"))
        assertFalse(logger.debugLimited("a", "suppressed"))
        assertTrue(logger.debugLimited("b", "second"))
        assertTrue(logger.debugLimited("c", "evicts a"))
        assertEquals(2, limiter.trackedKeyCount())

        clock.monotonic = 1_100L
        assertTrue(logger.debugLimited("a", "new window"))
        assertEquals(4, records.size)
    }

    @Test
    fun `preserves unicode message and attributes as structured values`() {
        val records = mutableListOf<LogRecord>()
        val logger = StructuredLogger.create(
            source = "test",
            tag = "test",
            sink = StructuredLogSink(records::add),
        )

        assertTrue(
            logger.info(
                message = "验证码已转发：短信",
                attributes = mapOf("渠道" to "中国移动"),
            ),
        )

        assertEquals("验证码已转发：短信", records.single().message)
        assertEquals("中国移动", records.single().attributes["渠道"])
    }
}
