package io.github.magisk317.xposed.capture

import android.app.Notification
import android.content.Context
import io.github.magisk317.xposed.BaseHook
import io.github.magisk317.xposed.HookEnv
import io.github.magisk317.xposed.HookHelpers
import io.github.magisk317.xposed.HookHostCapabilities
import io.github.magisk317.xposed.LoadParam
import io.github.magisk317.xposed.MethodHook
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.logging.MagiskOtel
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.runNonFatalCatching
import io.github.magisk317.xposed.runNonFatalOrNull
import io.github.magisk317.xposed.runtime.contract.logging.LogRoute

/**
 * Hooks the system notification queue and hands every usable notification to the
 * product. The hook owns plumbing only: method binding, argument extraction,
 * blank/invalid filtering and the shared observability events. Routing, policy
 * exemptions and delivery belong to the [onNotificationCaptured] implementation.
 */
abstract class NotificationCaptureHook(
    private val capabilities: HookHostCapabilities?,
) : BaseHook() {

    /** Implemented by the product: route, filter and deliver the capture. */
    protected abstract fun onNotificationCaptured(
        capture: CapturedNotification,
        systemContext: Context,
        emit: CaptureEmit,
    )

    /** Product hook for diagnostics on blank notifications; the capture is dropped either way. */
    protected open fun onBlankContent(capture: CapturedNotification, notification: Notification) {}

    override fun hookOnLoadPackage(): Boolean = true

    override fun onLoadPackage(param: LoadParam) {
        val isSystemPackage = param.packageName == "android" || param.packageName == "system"
        val isSystemProcess = param.processName == "system" ||
            param.processName == "android" ||
            param.processName == "system_server"
        if (!isSystemPackage || !isSystemProcess) return

        XLog.withRoute(LogRoute.NMS_HOOK.id) {
            onLoadPackageRouted(param)
        }
    }

    private fun onLoadPackageRouted(lpparam: LoadParam) {
        runNonFatalCatching {
            val nmsClass = HookHelpers.findClass(
                "com.android.server.notification.NotificationManagerService",
                lpparam.classLoader,
            )

            val methods = nmsClass.declaredMethods.filter { it.name == "enqueueNotificationInternal" }
            if (methods.isEmpty()) {
                XLog.w(TAG + ": no enqueueNotificationInternal method found")
                capabilities?.recordHookInstall(
                    context = null,
                    hook = HOOK_NAME,
                    status = "method_missing",
                    packageName = "android",
                    processName = "system",
                    detail = "enqueueNotificationInternal",
                    verboseLogging = false,
                )
                return
            }

            methods.forEach { method ->
                HookEnv.api.hookMethod(
                    method,
                    object : MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            XLog.withRoute(LogRoute.NMS_HOOK.id) {
                                runNonFatalCatching {
                                    handleEnqueueNotificationInternal(param)
                                }.onFailure { t ->
                                    XLog.e(message = "$TAG error", t)
                                }
                            }
                        }
                    },
                )
            }
            XLog.w(TAG + ": successfully hooked enqueueNotificationInternal")
            capabilities?.recordHookInstall(
                context = null,
                hook = HOOK_NAME,
                status = "installed",
                packageName = "android",
                processName = "system",
                detail = "enqueueNotificationInternal",
                verboseLogging = false,
            )
        }.onFailure { t ->
            XLog.e(message = TAG + ": failed to hook NotificationManagerService", t)
            capabilities?.recordHookInstall(
                context = null,
                hook = HOOK_NAME,
                status = "error",
                packageName = "android",
                processName = "system",
                detail = t.javaClass.simpleName,
                verboseLogging = false,
            )
        }
    }

    private fun handleEnqueueNotificationInternal(param: MethodHookParam) {
        val startedAt = System.nanoTime()
        val emit = CaptureEmit(startedAt)

        var pkg: String? = null
        var notification: Notification? = null

        for (arg in param.args) {
            if (arg is String && pkg == null) {
                pkg = arg
            } else if (arg is Notification) {
                notification = arg
            }
        }

        if (pkg.isNullOrEmpty() || notification == null) {
            XLog.d(
                message = TAG + ": skip invalid args. pkg=%s hasNotification=%s",
                pkg ?: "<null>",
                notification != null,
            )
            emit.skip(reason = "invalid_args", targetPackage = pkg)
            return
        }

        val systemContext = runNonFatalOrNull {
            HookHelpers.callMethod(param.thisObject, "getContext") as? Context
        }

        if (systemContext == null) {
            XLog.w(TAG + ": failed to get Context")
            emit.skip(reason = "no_context", targetPackage = pkg)
            return
        }

        val capture = CapturedNotification.from(pkg, notification)
        if (capture.isBlankContent) {
            onBlankContent(capture, notification)
            XLog.d(TAG + ": skip blank content. pkg=$pkg")
            emit.skip(reason = "blank_content", targetPackage = pkg)
            return
        }
        onNotificationCaptured(capture, systemContext, emit)
    }

    companion object {
        const val HOOK_NAME = "nms"
        const val TAG = "NotificationCaptureHook"
    }
}

/** Shared observability emitter for one intercepted notification. */
class NotificationCaptureEmit internal constructor(
    private val startedAt: Long,
) {
    fun skip(reason: String, targetPackage: String?, msgType: String? = null) {
        emit(result = "skip", reason = reason, targetPackage = targetPackage, msgType = msgType)
    }

    fun forwarded(targetPackage: String, msgType: String) {
        emit(result = "ok", reason = "forwarded", targetPackage = targetPackage, msgType = msgType)
    }

    private fun emit(
        result: String,
        reason: String,
        targetPackage: String?,
        msgType: String?,
    ) {
        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        val attrs = mutableMapOf(
            "result" to result,
            "duration_ms" to durationMs.toString(),
            "process" to "system",
            "source" to SOURCE,
        )
        if (reason.isNotBlank()) attrs["reason"] = reason
        if (!msgType.isNullOrBlank()) attrs["msg_type"] = msgType
        if (!targetPackage.isNullOrBlank()) attrs["target_package"] = targetPackage
        MagiskOtel.event(name = EVENT_NAME, attributes = attrs, statusOk = true)
    }

    companion object {
        internal const val SOURCE = "nms_hook"
        internal const val EVENT_NAME = "notify.intercept"
    }
}
