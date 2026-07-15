package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class XposedLogIngressPolicyTest {
    @Test
    fun `normalizes unknown and mixed case levels`() {
        assertEquals("W", XposedLogIngressPolicy.normalizeLevel("w"))
        assertEquals("I", XposedLogIngressPolicy.normalizeLevel("trace"))
        assertEquals("I", XposedLogIngressPolicy.normalizeLevel(null))
    }

    @Test
    fun `truncation preserves complete unicode code points`() {
        val result = XposedLogIngressPolicy.truncateUtf8("a中b", maxBytes = 4)

        assertEquals("a中", result)
        assertFalse(result.contains('\uFFFD'))
    }
}
