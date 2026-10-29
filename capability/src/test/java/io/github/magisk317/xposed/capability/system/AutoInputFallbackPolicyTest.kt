package io.github.magisk317.xposed.capability.system

import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputFallbackPolicy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoInputFallbackPolicyTest {

    @Test
    fun shouldSkipFallbackAfterAccessibility_skipsUnsafeWindows() {
        assertFalse(
            AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
                success = false,
                reason = "no_editable_node",
                windowPackage = "io.github.magisk317.xinyi.relay",
                modulePackage = "io.github.magisk317.xinyi.relay",
            ),
        )
        assertTrue(
            AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
                success = false,
                reason = "no_editable_node",
                windowPackage = "com.miui.home",
                modulePackage = "io.github.magisk317.xinyi.relay",
                homePackages = setOf("com.miui.home"),
            ),
        )
        assertTrue(
            AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
                success = false,
                reason = "no_active_window",
                windowPackage = "",
                modulePackage = "io.github.magisk317.xinyi.relay",
            ),
        )
    }

    @Test
    fun shouldSkipFallbackAfterAccessibility_keepsAppFallbackForThirdPartyWindow() {
        assertFalse(
            AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
                success = false,
                reason = "no_editable_node",
                windowPackage = "com.larus.nova",
                modulePackage = "io.github.magisk317.xinyi.relay",
            ),
        )
        assertTrue(
            AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
                success = false,
                reason = "no_editable_node",
                windowPackage = "com.larus.nova",
                modulePackage = "io.github.magisk317.xinyi.relay",
                homePackages = setOf("com.larus.nova"),
            ),
        )
        assertTrue(
            AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
                success = true,
                reason = "ok",
                windowPackage = "com.larus.nova",
                modulePackage = "io.github.magisk317.xinyi.relay",
            ),
        )
    }

    @Test
    fun runWithRetries_retriesLauncherUntilTargetWindowAppears() {
        data class Attempt(
            val success: Boolean,
            val reason: String,
            val windowPackage: String,
        )
        val attempts = listOf(
            Attempt(success = false, reason = "no_editable_node", windowPackage = "com.larus.nova"),
            Attempt(success = true, reason = "ok", windowPackage = "com.target.app"),
        )
        var invocation = 0

        val result = AutoInputFallbackPolicy.runWithRetries(
            maxAttempts = 3,
            delayMs = 1L,
            perform = { attempts[invocation++] },
            shouldRetry = { attempt ->
                AutoInputFallbackPolicy.shouldRetryAccessibilityResult(
                    success = attempt.success,
                    reason = attempt.reason,
                    windowPackage = attempt.windowPackage,
                    modulePackage = "io.github.magisk317.xinyi.relay",
                    homePackages = setOf("com.larus.nova"),
                )
            },
            sleeper = {},
        )

        assertTrue(result.success)
        assertEquals(2, invocation)
    }

    @Test
    fun normalizeIpcToken_trimsAndDropsBlankValues() {
        assertEquals("token", SystemInputInjectorHook.normalizeIpcToken(" token "))
        assertEquals(null, SystemInputInjectorHook.normalizeIpcToken(""))
        assertEquals(null, SystemInputInjectorHook.normalizeIpcToken("   "))
        assertEquals(null, SystemInputInjectorHook.normalizeIpcToken(null))
    }

    @Test
    fun shouldAllowAutoInputSenderUid_allowsScopedSmsPackages() {
        assertTrue(
            SystemInputInjectorHook.shouldAllowAutoInputSenderUid(
                sendingUid = 10148,
                systemContextUid = 1000,
                senderPackages = listOf("com.android.mms"),
                modulePackageName = "com.github.tianma8023.xposed.smscode",
            ),
        )
        assertTrue(
            SystemInputInjectorHook.shouldAllowAutoInputSenderUid(
                sendingUid = 10149,
                systemContextUid = 1000,
                senderPackages = listOf("com.xiaomi.phone"),
                modulePackageName = "com.github.tianma8023.xposed.smscode",
            ),
        )
        assertTrue(
            SystemInputInjectorHook.shouldAllowAutoInputSenderUid(
                sendingUid = 10150,
                systemContextUid = 1000,
                senderPackages = listOf("com.google.android.apps.messaging"),
                modulePackageName = "com.github.tianma8023.xposed.smscode",
            ),
        )
        assertTrue(
            SystemInputInjectorHook.shouldAllowAutoInputSenderUid(
                sendingUid = 10151,
                systemContextUid = 1000,
                senderPackages = listOf("com.github.tianma8023.xposed.smscode"),
                modulePackageName = "com.github.tianma8023.xposed.smscode",
            ),
        )
    }

    @Test
    fun shouldAllowAutoInputSenderUid_rejectsUnscopedPackages() {
        assertFalse(
            SystemInputInjectorHook.shouldAllowAutoInputSenderUid(
                sendingUid = 10148,
                systemContextUid = 1000,
                senderPackages = listOf("com.example.untrusted"),
                modulePackageName = "com.github.tianma8023.xposed.smscode",
            ),
        )
    }
}
