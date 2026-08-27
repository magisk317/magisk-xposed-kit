package io.github.magisk317.xposed.logging

import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class XposedLogClientDeliveryTest {
    private lateinit var zygoteBuffer: LinkedBlockingQueue<XposedLogEvent>

    @BeforeEach
    fun setUp() {
        XposedLogClient.resetForTest()
        LogSanitizerConfig.setEnabled(false)
        zygoteBuffer = privateField("zygoteBuffer")
        setPrivateField("contextLookupFailed", true)
    }

    @AfterEach
    fun tearDown() {
        LookupTarget.context = null
        XposedLogClient.resetForTest()
        LogSanitizerConfig.setEnabled(false)
    }

    @Test
    fun `zygote overflow drops oldest event and records reason`() {
        repeat(129) { index ->
            XposedLogClient.send(event("event-$index"))
        }

        val snapshot = XposedLogClient.snapshot()
        assertEquals(128, snapshot.zygoteBufferDepth)
        assertEquals(1, snapshot.droppedCount)
        assertEquals(1, snapshot.droppedZygoteOverflowCount)
        assertEquals("event-1", zygoteBuffer.peek()?.message)
    }

    @Test
    fun `provider backoff drop is recorded without queueing`() {
        setPrivateField("localContext", TestContext())
        setPrivateField("providerUnavailableUntilMs", Long.MAX_VALUE)

        XposedLogClient.send(event("during-backoff"))

        val snapshot = XposedLogClient.snapshot()
        assertEquals(1, snapshot.droppedCount)
        assertEquals(1, snapshot.droppedBackoffCount)
        assertEquals(0, snapshot.queueDepth)
    }

    @Test
    fun `successful reflected context is cached for the fast path`() {
        val context = TestContext()
        LookupTarget.context = context
        setPrivateField(
            "cachedCurrentApplication",
            LookupTarget::class.java.getDeclaredMethod("currentApplicationForTest"),
        )
        setPrivateField("reflectionResolved", true)
        setPrivateField("contextLookupFailed", false)
        setPrivateField("providerUnavailableUntilMs", Long.MAX_VALUE)

        XposedLogClient.send(event("cache-context"))

        assertSame(context, privateField<Context>("localContext"))
        assertEquals(1, XposedLogClient.snapshot().droppedBackoffCount)
    }

    private fun event(message: String) = XposedLogEvent(
        source = "delivery-test",
        level = "D",
        tag = "DeliveryTest",
        message = message,
        throwable = "",
        route = null,
        force = false,
        sensitive = false,
        packageName = "",
        processName = "",
        pid = 1,
        uid = 2,
        sanitized = false,
    )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> privateField(name: String): T {
        val field = XposedLogClient::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(XposedLogClient) as T
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = XposedLogClient::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(XposedLogClient, value)
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private object LookupTarget {
        @JvmStatic
        var context: Context? = null

        @JvmStatic
        fun currentApplicationForTest(): Context? = context
    }
}
