package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `buildPayload includes service commit when set`() {
        val payload = MagiskOtel.buildPayload(
            config =
                MagiskOtel.Config(
                    enabled = true,
                    serviceName = "mipushframework",
                    serviceVersion = "1.0.5",
                    serviceCommit = "8623e54f4",
                ),
            name = "push.receive",
            attributes = mapOf("result" to "ok"),
            statusOk = true,
        )

        assertTrue(payload.contains("\"service.commit\""))
        assertTrue(payload.contains("8623e54f4"))
    }

    @Test
    fun `buildPayload omits blank service commit`() {
        val payload = MagiskOtel.buildPayload(
            config = MagiskOtel.Config(enabled = true, serviceName = "test"),
            name = "app.boot",
            attributes = emptyMap(),
            statusOk = true,
        )

        assertFalse(payload.contains("service.commit"))
    }

    private fun config(
        names: Set<String> = emptySet(),
        results: Set<String> = emptySet(),
    ) = MagiskOtel.Config(
        enabled = true,
        serviceName = "mipushframework",
        suppressedEventNames = names,
        suppressedResultValues = results,
    )

    @Test
    fun `suppressed span names are dropped`() {
        assertTrue(MagiskOtel.isSuppressed(config(names = setOf("push.keepalive")), "push.keepalive", emptyMap()))
        assertFalse(MagiskOtel.isSuppressed(config(names = setOf("push.keepalive")), "push.register", emptyMap()))
    }

    @Test
    fun `suppressed result values drop success noise only`() {
        val cfg = config(results = setOf("ok"))
        assertTrue(MagiskOtel.isSuppressed(cfg, "push.dispatch", mapOf("result" to "ok")))
        assertFalse(MagiskOtel.isSuppressed(cfg, "push.dispatch", mapOf("result" to "skip")))
        assertFalse(MagiskOtel.isSuppressed(cfg, "push.dispatch", mapOf("result" to "error")))
        assertFalse(MagiskOtel.isSuppressed(cfg, "push.dispatch", emptyMap()))
    }

    @Test
    fun `empty suppression sets keep every span`() {
        assertFalse(MagiskOtel.isSuppressed(config(), "push.keepalive", mapOf("result" to "ok")))
    }

    @Test
    fun `buildPayload includes device attributes when set`() {
        val payload =
            MagiskOtel.buildPayload(
                config =
                    MagiskOtel.Config(
                        enabled = true,
                        serviceName = "xposedsmscode",
                        serviceVersion = "3.3.6",
                        deviceAttributes =
                            mapOf(
                                "device.manufacturer" to "Xiaomi",
                                "device.model" to "MI 9",
                                "os.version" to "14",
                            ),
                    ),
                name = "sms.record",
                attributes = mapOf("result" to "error", "reason" to "insert_failed"),
                statusOk = false,
                nowNanos = 1_000_000_000L,
            )

        assertTrue(payload.contains("\"device.manufacturer\""))
        assertTrue(payload.contains("Xiaomi"))
        assertTrue(payload.contains("\"device.model\""))
        assertTrue(payload.contains("MI 9"))
        assertTrue(payload.contains("\"os.version\""))
        assertTrue(payload.contains("\"14\""))
    }

    @Test
    fun `buildPayload omits blank device attributes`() {
        val payload =
            MagiskOtel.buildPayload(
                config =
                    MagiskOtel.Config(
                        enabled = true,
                        serviceName = "xposedsmscode",
                        serviceVersion = "3.3.6",
                        deviceAttributes =
                            mapOf(
                                "device.manufacturer" to "",
                                "device.model" to "  ",
                            ),
                    ),
                name = "sms.record",
                attributes = emptyMap(),
                statusOk = true,
                nowNanos = 1_000_000_000L,
            )

        assertFalse(payload.contains("device.manufacturer"))
        assertFalse(payload.contains("device.model"))
    }

    @Test
    fun `reason tokens survive sanitization`() {
        val attrs = MagiskOtel.sanitizeAttributes(
            mapOf("reason" to "zygisk_config_read_failed", "result" to "error"),
        )

        assertEquals("zygisk_config_read_failed", attrs["reason"])
        assertEquals("error", attrs["result"])
    }

    @Test
    fun `class name reasons survive sanitization`() {
        assertEquals("MessagingException", MagiskOtel.sanitizeReason("MessagingException"))
    }

    @Test
    fun `raw stderr reasons fold to unclassified`() {
        val stderr = "cat: /data/adb/mipush_zygisk/app.conf: No such file or directory"

        assertEquals("unclassified", MagiskOtel.sanitizeReason(stderr))
    }

    @Test
    fun `cjk and multiline reasons fold to unclassified`() {
        assertEquals("unclassified", MagiskOtel.sanitizeReason("force register needs\nRoot permission"))
        assertEquals("unclassified", MagiskOtel.sanitizeReason("\u5f3a\u5236\u6ce8\u518c\u9700\u8981 Root \u6743\u9650"))
    }

    @Test
    fun `blank and over-long reasons fold to unclassified`() {
        assertEquals("unclassified", MagiskOtel.sanitizeReason("   "))
        assertEquals(
            "unclassified",
            MagiskOtel.sanitizeReason("a".repeat(MagiskOtel.REASON_MAX_CHARS + 1)),
        )
    }

    @Test
    fun `non-reason values are flattened and truncated`() {
        val attrs = MagiskOtel.sanitizeAttributes(
            mapOf(
                "detail" to "line1\nline2",
                "big" to "x".repeat(MagiskOtel.ATTRIBUTE_MAX_BYTES + 64),
            ),
        )

        assertEquals("line1 line2", attrs["detail"])
        assertEquals(MagiskOtel.ATTRIBUTE_MAX_BYTES, attrs["big"]!!.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `blank attribute keys are dropped and keys are trimmed`() {
        val attrs = MagiskOtel.sanitizeAttributes(mapOf("  " to "v", " reason " to "insert_failed"))

        assertFalse(attrs.containsKey(""))
        assertFalse(attrs.containsKey("  "))
        assertEquals("insert_failed", attrs["reason"])
    }
}
