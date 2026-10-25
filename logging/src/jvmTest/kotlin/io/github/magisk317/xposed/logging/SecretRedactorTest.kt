package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretRedactorTest {
    @Test
    fun `secret redactor covers structured credentials without summarizing payloads`() {
        val input =
                "url=https://user:pass@example.test/hook?api-key=abc123 " +
                "json={\"token\":\"json-secret\",\"message\":\"keep me\"} " +
                "Cookie=session-value Proxy-Authorization=BasicValue " +
                "Authorization: Bearer bearer-secret Proxy-Authorization: Basic YmFzaWM="

        val result = SecretRedactor.redact(input)

        assertFalse(result.contains("user:pass"))
        assertFalse(result.contains("abc123"))
        assertFalse(result.contains("json-secret"))
        assertFalse(result.contains("session-value"))
        assertFalse(result.contains("BasicValue"))
        assertFalse(result.contains("bearer-secret"))
        assertFalse(result.contains("YmFzaWM="))
        assertTrue(result.contains("\"message\":\"keep me\""))
    }
}
