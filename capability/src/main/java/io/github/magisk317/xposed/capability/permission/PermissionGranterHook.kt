package io.github.magisk317.xposed.capability.hook.permission

import android.os.Build
import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.BaseHook
import io.github.magisk317.xposed.LoadParam
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.logging.MagiskOtel

/**
 * Hook com.android.server.pm.PackageManagerService to grant permissions.
 */
class PermissionGranterHook : BaseHook() {

    override fun onLoadPackage(param: LoadParam) {
        XLog.withRoute(LogRoute.PERMISSION_HOOK.id) {
            onLoadPackageRouted(param)
        }
    }

    private fun onLoadPackageRouted(lpparam: LoadParam) {
        if (
            ANDROID_PACKAGE == lpparam.packageName &&
            (ANDROID_PACKAGE == lpparam.processName || SYSTEM_SERVER_PROCESS == lpparam.processName)
        ) {
            val classLoader = lpparam.classLoader
            val hookImpl = when {
                Build.VERSION.SDK_INT >= ANDROID_16 -> "pms36"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "pms34"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "pms33"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "pms31"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "pms30"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> "pms"
                else -> "package_manager"
            }
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= ANDROID_16 -> { // Android 16+
                        PermissionManagerServiceHook36(classLoader).startHook()
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        PermissionManagerServiceHook34(classLoader).startHook()
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> { // Android 13+
                        PermissionManagerServiceHook33(classLoader).startHook()
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> { // Android 12~12L
                        PermissionManagerServiceHook31(classLoader).startHook()
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> { // Android 11
                        PermissionManagerServiceHook30(classLoader).startHook()
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> { // Android 9.0~10
                        PermissionManagerServiceHook(classLoader).startHook()
                    }

                    else -> { // Android 5.0 ~ 8.1
                        PackageManagerServiceHook(classLoader).startHook()
                    }
                }
            }.onSuccess {
                MagiskOtel.event(
                    name = "hook.load",
                    attributes = mapOf(
                        "result" to "ok",
                        "duration_ms" to "0",
                        "process" to "system_server",
                        "stage" to "permission_grant",
                        "reason" to "installed",
                        "source" to hookImpl,
                        "abi" to Build.VERSION.SDK_INT.toString(),
                    ),
                    statusOk = true,
                )
            }.onFailure { error ->
                MagiskOtel.event(
                    name = "hook.load",
                    attributes = mapOf(
                        "result" to "error",
                        "duration_ms" to "0",
                        "process" to "system_server",
                        "stage" to "permission_grant",
                        "reason" to "install_failed",
                        "source" to hookImpl,
                        "error_class" to error.javaClass.simpleName,
                        "abi" to Build.VERSION.SDK_INT.toString(),
                    ),
                    statusOk = false,
                )
                throw error
            }
        }
    }

    companion object {
        const val ANDROID_PACKAGE = "android"
        const val SYSTEM_SERVER_PROCESS = "system_server"
        const val ANDROID_16 = 36
    }
}
