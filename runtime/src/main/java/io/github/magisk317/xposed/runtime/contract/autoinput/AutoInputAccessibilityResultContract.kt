package io.github.magisk317.xposed.runtime.contract.autoinput

import android.os.Bundle

object AutoInputAccessibilityResultContract {
    const val EXTRA_HANDLED = "accessibility_handled"
    const val EXTRA_SUCCESS = "accessibility_success"
    const val EXTRA_REASON = "accessibility_reason"
    const val EXTRA_STRATEGY = "accessibility_strategy"
    const val EXTRA_WINDOW_PACKAGE = "accessibility_window_package"

    data class OrderedResult(
        val success: Boolean,
        val strategy: String,
        val reason: String,
        val windowPackage: String,
    )

    fun putOrderedResult(
        extras: Bundle,
        result: OrderedResult,
    ): Bundle {
        putOrderedResult(
            putBoolean = extras::putBoolean,
            putString = extras::putString,
            result = result,
        )
        return extras
    }

    fun putOrderedResult(
        putBoolean: (String, Boolean) -> Unit,
        putString: (String, String) -> Unit,
        result: OrderedResult,
    ) {
        putBoolean(EXTRA_HANDLED, true)
        putBoolean(EXTRA_SUCCESS, result.success)
        putString(EXTRA_REASON, result.reason)
        putString(EXTRA_STRATEGY, result.strategy)
        putString(EXTRA_WINDOW_PACKAGE, result.windowPackage)
    }

    fun readOrderedResult(extras: Bundle): OrderedResult? {
        return readOrderedResult(
            getBoolean = extras::getBoolean,
            getString = extras::getString,
        )
    }

    fun readOrderedResult(
        getBoolean: (String, Boolean) -> Boolean,
        getString: (String) -> String?,
    ): OrderedResult? {
        if (!getBoolean(EXTRA_HANDLED, false)) return null
        return OrderedResult(
            success = getBoolean(EXTRA_SUCCESS, false),
            strategy = getString(EXTRA_STRATEGY).orEmpty(),
            reason = getString(EXTRA_REASON).orEmpty(),
            windowPackage = getString(EXTRA_WINDOW_PACKAGE).orEmpty(),
        )
    }
}
