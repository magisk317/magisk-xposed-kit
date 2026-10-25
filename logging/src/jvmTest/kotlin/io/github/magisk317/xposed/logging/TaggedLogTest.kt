package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * TaggedLog is pure sugar over XLog: every call must reach the sink as a
 * LogEvent with the handle's tag, the rendered message and the sanitized
 * throwable text, gated by the global level like a static call.
 */
class TaggedLogTest {

    @AfterEach
    fun reset() {
        XLog.resetForTest()
    }

    // The XLog singleton is shared across every test class in this JVM: a
    // previous class may have left a testSink installed, which would swallow
    // these events before the capturing sink sees them. Reset first.
    private fun resetAndConfigure(sink: LogSink) {
        XLog.resetForTest()
        XLog.configure(tag = "base", logLevel = LogLevel.VERBOSE.priority, sink = sink)
    }

    private class CapturingSink : LogSink {
        val events = mutableListOf<LogEvent>()
        override fun append(event: LogEvent) {
            events.add(event)
        }
    }

    @Test
    fun stringLambdaAndThrowableShapesAllDispatch() {
        val sink = CapturingSink()
        resetAndConfigure(sink)

        val log = XLog.tagged("Push")
        log.d("plain")
        log.i { "deferred" }
        log.w(IllegalStateException("boom")) { "failed" }

        assertEquals(3, sink.events.size)
        sink.events[0].let {
            assertEquals("Push", it.tag)
            assertEquals(LogLevel.DEBUG, it.level)
            assertEquals("plain", it.message)
            assertNull(it.throwableText)
        }
        assertEquals("deferred", sink.events[1].message)
        sink.events[2].let {
            assertEquals(LogLevel.WARN, it.level)
            assertTrue(it.message.startsWith("failed"))
            assertTrue(it.throwableText?.contains("boom") == true)
        }
    }

    @Test
    fun taggedCallsRespectTheGlobalLevelGate() {
        val sink = CapturingSink()
        resetAndConfigure(sink).let { XLog.setLogLevel(LogLevel.ERROR.priority) }

        XLog.tagged("Push").i { "filtered" }
        XLog.tagged("Push").e { "kept" }

        assertEquals(1, sink.events.size)
        assertEquals("kept", sink.events[0].message)
    }
}
