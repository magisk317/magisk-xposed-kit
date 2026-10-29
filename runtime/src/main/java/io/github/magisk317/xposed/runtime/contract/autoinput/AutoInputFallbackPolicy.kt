package io.github.magisk317.xposed.runtime.contract.autoinput

import java.util.Locale

object AutoInputFallbackPolicy {
    fun shouldRetryAccessibilityResult(
        success: Boolean,
        reason: String,
        windowPackage: String,
        modulePackage: String,
        homePackages: Set<String> = emptySet(),
    ): Boolean {
        if (success) return false
        return when (reason) {
            "no_active_window" -> true
            "no_editable_node" -> isFallbackUnsafeWindow(
                windowPackage = windowPackage,
                modulePackage = modulePackage,
                homePackages = homePackages,
            )
            else -> false
        }
    }

    fun shouldSkipFallbackAfterAccessibility(
        success: Boolean,
        reason: String,
        windowPackage: String,
        modulePackage: String,
        homePackages: Set<String> = emptySet(),
    ): Boolean {
        if (success) return true
        return when (reason) {
            "no_active_window" -> true
            "no_editable_node" -> isFallbackUnsafeWindow(
                windowPackage = windowPackage,
                modulePackage = modulePackage,
                homePackages = homePackages,
            )
            else -> false
        }
    }

    fun <T> runWithRetries(
        maxAttempts: Int,
        delayMs: Long,
        perform: (attemptIndex: Int) -> T,
        shouldRetry: (T) -> Boolean,
        sleeper: (Long) -> Unit,
    ): T {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        var lastResult = perform(0)
        for (attempt in 1 until maxAttempts) {
            if (!shouldRetry(lastResult)) {
                return lastResult
            }
            sleeper(delayMs)
            lastResult = perform(attempt)
        }
        return lastResult
    }

    private fun isFallbackUnsafeWindow(
        windowPackage: String,
        modulePackage: String,
        homePackages: Set<String>,
    ): Boolean {
        val normalizedWindow = windowPackage.trim().lowercase(Locale.ROOT)
        if (normalizedWindow.isBlank()) return true
        if (normalizedWindow == "android" || normalizedWindow == "com.android.systemui") return true
        if (homePackages.any { it.trim().lowercase(Locale.ROOT) == normalizedWindow }) return true
        if (normalizedWindow.contains("launcher") || normalizedWindow.contains("home")) return true
        return false
    }
}
