package io.github.magisk317.xposed.capability.helper

import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.SafeMethodHook

/**
 * [SafeMethodHook] subclass that routes hook errors through [XLog] with
 * an optional [LogRoute] for structured logging.
 */
abstract class MethodHookWrapper(
    private val route: LogRoute? = null,
    stableId: String? = route?.id,
) : SafeMethodHook(stableId) {

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val activeRoute = route
        if (activeRoute != null) {
            XLog.withRoute(activeRoute.id) {
                super.beforeHookedMethod(param)
            }
        } else {
            super.beforeHookedMethod(param)
        }
    }

    @Throws(Throwable::class)
    override fun afterHookedMethod(param: MethodHookParam) {
        val activeRoute = route
        if (activeRoute != null) {
            XLog.withRoute(activeRoute.id) {
                super.afterHookedMethod(param)
            }
        } else {
            super.afterHookedMethod(param)
        }
    }

    override fun onHookError(param: MethodHookParam, error: Throwable, phase: String) {
        if (phase == "before") {
            XLog.d(message = "Error in hook %s", param.method.name, error)
        } else {
            XLog.e(message = "Error in hook %s", param.method.name, error)
        }
    }
}
