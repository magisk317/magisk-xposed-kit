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
        MagiskLogPolicy.install(isDiagnosticBuild = true)
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
        MagiskLogPolicy.install(isDiagnosticBuild = false)
    }

    @Test
    fun `diagnostic send keeps raw text within the shared limit`() {
        val token = "token-123"
        val bearer = "bearer-456"
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
        assertTrue(event.message.contains(token))
        assertTrue(event.message.contains(phone))
        assertTrue(event.message.length <= DefaultLogSanitizer.LOG_MAX_LENGTH)
        assertTrue(event.throwable.contains(token))
        assertTrue(event.throwable.contains(phone))
    }

    @Test
    fun `release send is dropped before buffering`() {
        MagiskLogPolicy.install(isDiagnosticBuild = false)

        XposedLogClient.send(
            XposedLogEvent(
                source = "release",
                level = "E",
                tag = "test",
                message = "secret",
                pid = 1,
                uid = 2,
            ),
        )

        assertTrue(zygoteBuffer.isEmpty())
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
