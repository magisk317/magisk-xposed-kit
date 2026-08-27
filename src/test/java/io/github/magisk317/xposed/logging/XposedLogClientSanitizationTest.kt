package io.github.magisk317.xposed.logging

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class XposedLogClientSanitizationTest {
    private lateinit var zygoteBuffer: LinkedBlockingQueue<XposedLogEvent>

    @BeforeEach
    fun setUp() {
        LogSanitizerConfig.setEnabled(true)
        zygoteBuffer = privateField("zygoteBuffer")
        zygoteBuffer.clear()
        setPrivateField("localContext", null)
        setPrivateField("contextLookupFailed", true)
    }

    @AfterEach
    fun tearDown() {
        zygoteBuffer.clear()
        setPrivateField("localContext", null)
        setPrivateField("contextLookupFailed", false)
        LogSanitizerConfig.setEnabled(false)
    }

    @Test
    fun `send sanitizes before buffering the Xposed transport event`() {
        val token = "xposed-token-secret-123456"
        val bearer = "xposed-bearer-secret-123456"
        val phone = "13800138000"
        XposedLogClient.configure("com.example.log", "SanitizerContract")

        XposedLogClient.send(
            XposedLogEvent(
                source = "contract",
                level = "E",
                tag = "XposedContract",
                message = "token=$token Authorization: Bearer $bearer phone=$phone",
                throwable = "stack token=$token phone=$phone",
                pid = 1,
                uid = 2,
            ),
        )

        val event = zygoteBuffer.poll(1, TimeUnit.SECONDS)
        assertNotNull(event)
        assertTrue(event!!.sanitized)
        assertFalse(event.message.contains(token))
        assertFalse(event.message.contains(bearer))
        assertFalse(event.message.contains(phone))
        assertFalse(event.throwable.contains(token))
        assertFalse(event.throwable.contains(phone))
    }

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
}
