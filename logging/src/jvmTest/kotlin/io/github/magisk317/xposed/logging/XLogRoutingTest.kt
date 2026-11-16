package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class XLogRoutingTest {
    private val events = mutableListOf<LogEvent>()

    @BeforeEach
    fun setUp() {
        XLog.resetForTest()
        MagiskLogPolicy.install(isDiagnosticBuild = true)
        XLog.configure(
            tag = "host",
            logLevel = LogLevel.VERBOSE.priority,
            sink = LogSink { events += it },
            printEnabled = false,
        )
    }

    @AfterEach
    fun tearDown() {
        XLog.resetForTest()
        MagiskLogPolicy.install(isDiagnosticBuild = false)
    }

    @Test
    fun `test sink receives the formatted message and throwable`() {
        var captured: Pair<Int, String>? = null
        XLog.setTestSink { priority, message -> captured = priority to message }

        XLog.e(message = "hook failed: %s", "operation", IllegalStateException("boom"))

        assertEquals(LogLevel.ERROR.priority, captured?.first)
        assertTrue(captured?.second.orEmpty().contains("hook failed: operation"))
        assertTrue(captured?.second.orEmpty().contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `configured sink receives formatted messages without platform printing`() {
        XLog.i(message = "loaded %s", "component")

        val event = events.single()
        assertEquals(LogLevel.INFO, event.level)
        assertEquals("host", event.tag)
        assertEquals("loaded component", event.message)
        assertFalse(event.force)
        assertNull(event.route)
        assertTrue(event.sensitive)
    }

    @Test
    fun `route scope supplies the route while warning retains force policy`() {
        XLog.withRoute("route-a") {
            XLog.w("operation failed")
        }

        val event = events.single()
        assertEquals(LogLevel.WARN, event.level)
        assertEquals("route-a", event.route)
        assertTrue(event.force)
    }

    @Test
    fun `explicit route and force override the enclosing scope`() {
        XLog.withRoute("route-a") {
            XLog.log(LogLevel.INFO.priority, "route-b", false, true, "event")
        }

        val event = events.single()
        assertEquals(LogLevel.INFO, event.level)
        assertEquals("route-b", event.route)
        assertFalse(event.force)
    }

    @Test
    fun `two positional strings select the tag overload`() {
        XLog.w("format %s", "hello")

        val event = events.single()
        assertEquals("format %s", event.tag)
        assertEquals("hello", event.message)
    }

    @Test
    fun `a trailing throwable preserves positional tag overload resolution`() {
        XLog.e("hook failed: %s", "operation", IllegalStateException("boom"))

        val event = events.single()
        assertEquals("hook failed: %s", event.tag)
        assertTrue(event.message.startsWith("operation\n"))
        assertTrue(event.message.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `named message keeps multiple strings as format arguments`() {
        XLog.w(message = "format %s %s", "first", "second")

        val event = events.single()
        assertEquals("host", event.tag)
        assertEquals("format first second", event.message)
    }

    @Test
    fun `nested route scope restores the outer route and clears it on return`() {
        XLog.withRoute("route-a") {
            XLog.i("outer")
            XLog.withRoute("route-b") {
                XLog.i("inner")
            }
            XLog.i("outer again")
        }
        XLog.i("no route")

        assertEquals(listOf("route-a", "route-b", "route-a", null), events.map { it.route })
    }

    @Test
    fun `throwing nested scope restores the previous route`() {
        XLog.withRoute("route-a") {
            assertThrows(IllegalStateException::class.java) {
                XLog.withRoute("route-b") {
                    throw IllegalStateException("interrupted")
                }
            }
            XLog.i("outer after failure")
        }
        XLog.i("no route")

        assertEquals(listOf("route-a", null), events.map { it.route })
    }
}
