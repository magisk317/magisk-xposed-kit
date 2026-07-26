package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MagiskOtelTest {
    @Test
    fun `buildPayload includes resource and event attributes`() {
        val payload =
            MagiskOtel.buildPayload(
                config =
                    MagiskOtel.Config(
                        enabled = true,
                        serviceName = "xposedsmscode",
                        serviceVersion = "3.3.1",
                        projectId = "83955172",
                        projectName = "XposedSmsCode",
                        environment = "debug",
                        serviceInstanceId = "02c1f46d-581e-4f80-9747-9b9dcb99bc46",
                    ),
                name = "app.boot",
                attributes = mapOf("result" to "ok", "process" to "main"),
                statusOk = true,
                nowNanos = 1_000_000_000L,
            )

        assertTrue(payload.contains("\"service.name\""))
        assertTrue(payload.contains("xposedsmscode"))
        assertTrue(payload.contains("83955172"))
        assertTrue(payload.contains("service.instance.id"))
        assertTrue(payload.contains("02c1f46d-581e-4f80-9747-9b9dcb99bc46"))
        assertTrue(payload.contains("app.boot"))
        assertTrue(payload.contains("\"process\""))
        assertFalse(payload.contains("token="))
    }

    @Test
    fun `buildPayload omits invalid service instance id`() {
        val payload = MagiskOtel.buildPayload(
            config = MagiskOtel.Config(enabled = true, serviceName = "test", serviceInstanceId = "invalid"),
            name = "app.boot",
            attributes = emptyMap(),
            statusOk = true,
        )

        assertFalse(payload.contains("service.instance.id"))
    }
}
