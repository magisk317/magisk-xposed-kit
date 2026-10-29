package io.github.magisk317.xposed.capability.hook.permission

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.capability.constant.PermConst.PACKAGE_PERMISSIONS
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.capability.helper.MethodHookWrapper
import io.github.magisk317.xposed.HookHelpers
import io.github.magisk317.xposed.BaseSubHook
import io.github.magisk317.xposed.MethodHook
import io.github.magisk317.xposed.HookEnv
import java.lang.reflect.Method
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.logging.MagiskOtel

/**
 * Since Android P(API 28)<br/>
 * Hook com.android.server.pm.permission.PermissionManagerService
 */
class PermissionManagerServiceHook(classLoader: ClassLoader) : BaseSubHook(classLoader) {

    @RequiresApi(Build.VERSION_CODES.P)
    override fun startHook() {
        try {
            hookGrantPermissions()
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "ok",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to "installed",
                    "source" to "PermissionManagerServiceHook",
                ),
                statusOk = true,
            )
        } catch (e: ReflectiveOperationException) {
            emitPermissionInstallError(e)
            XLog.e("Failed to hook PermissionManagerService", e)
        } catch (e: IllegalArgumentException) {
            emitPermissionInstallError(e)
            XLog.e("Failed to hook PermissionManagerService", e)
        } catch (e: IllegalStateException) {
            emitPermissionInstallError(e)
            XLog.e("Failed to hook PermissionManagerService", e)
        } catch (e: SecurityException) {
            emitPermissionInstallError(e)
            XLog.e("Failed to hook PermissionManagerService", e)
        } catch (e: UnsupportedOperationException) {
            emitPermissionInstallError(e)
            XLog.e("Failed to hook PermissionManagerService", e)
        }
    }

    private fun emitPermissionInstallError(error: Throwable) {
        MagiskOtel.event(
            name = "hook.load",
            attributes = mapOf(
                "result" to "error",
                "duration_ms" to "0",
                "process" to "system_server",
                "stage" to "permission_grant",
                "reason" to "install_failed",
                "source" to "PermissionManagerServiceHook",
                "error_class" to error.javaClass.simpleName,
            ),
            statusOk = false,
        )
    }

    private fun hookGrantPermissions() {
        XLog.d("Hooking grantPermissions() for Android 28+")
        val method = findTargetMethod()
        if (method == null) {
            XLog.e("Cannot find the method to grant relevant permission")
            return
        }
        HookEnv.api.hookMethod(
            method,
            object : MethodHookWrapper(LogRoute.PERMISSION_HOOK) {
                @Throws(Throwable::class)
                override fun after(param: MethodHookParam) {
                    afterGrantPermissionsSinceP(param)
                }
            },
        )
    }

    private fun findTargetMethod(): Method? {
        val pmsClass = HookHelpers.findClass(CLASS_PERMISSION_MANAGER_SERVICE, mClassLoader)
        val packageClass = HookHelpers.findClass(CLASS_PACKAGE_PARSER_PACKAGE, mClassLoader)
        var callbackClass = HookHelpers.findClassIfExists(CLASS_PERMISSION_CALLBACK, mClassLoader)
        if (callbackClass == null) {
            // Android Q PermissionCallback 不一样
            callbackClass = runCatching { HookHelpers.findClass(CLASS_PERMISSION_CALLBACK_Q, mClassLoader) }.getOrNull()
        }

        var method = HookHelpers.findMethodExactIfExists(
            pmsClass,
            "grantPermissions",
            /* PackageParser.Package pkg   */
            packageClass,
            /* boolean replace             */
            Boolean::class.javaPrimitiveType,
            /* String packageOfInterest    */
            String::class.java,
            /* PermissionCallback callback */
            callbackClass,
        )

        if (method == null) { // method grantPermissions() not found
            // Android Q
            method = HookHelpers.findMethodExactIfExists(
                pmsClass,
                "restorePermissionState",
                /* PackageParser.Package pkg   */
                packageClass,
                /* boolean replace             */
                Boolean::class.javaPrimitiveType,
                /* String packageOfInterest    */
                String::class.java,
                /* PermissionCallback callback */
                callbackClass,
            )

            if (method == null) { // method restorePermissionState() not found
                val methods = HookHelpers.findMethodsByExactParameters(
                    pmsClass,
                    Void.TYPE,
                    /* PackageParser.Package pkg   */
                    packageClass,
                    /* boolean replace             */
                    Boolean::class.javaPrimitiveType,
                    /* String packageOfInterest    */
                    String::class.java,
                    /* PermissionCallback callback */
                    callbackClass,
                )
                if (methods.isNotEmpty()) {
                    method = methods[0]
                }
            }
        }
        if (method == null) {
            PermissionDebugProbe.dumpClass("PermissionManagerServiceHook missing grant/restore", pmsClass)
        }
        return method
    }

    private fun afterGrantPermissionsSinceP(param: MethodHookParam) {
        // android.content.pm.PackageParser.Package 对象
        val pkg = param.args[0]
        val packageNameInPkg = HookHelpers.getObjectField(pkg, "packageName") as String

        for (packageName in PACKAGE_PERMISSIONS.keys) {
            if (packageName == packageNameInPkg) {
                XLog.d(message = "PackageName: %s", packageName)
                val extras = HookHelpers.getObjectField(pkg, "mExtras")
                val permissionsState = HookHelpers.callMethod(extras, "getPermissionsState")
                val requestedPermissions = HookHelpers.getObjectField(pkg, "requestedPermissions") as? List<*>
                val settings = HookHelpers.getObjectField(param.thisObject, "mSettings")
                val permissions = HookHelpers.getObjectField(settings, "mPermissions")

                val permissionsToGrant = PACKAGE_PERMISSIONS[packageName] ?: continue
                var grantedCount = 0
                var alreadyCount = 0
                for (permissionToGrant in permissionsToGrant) {
                    if (requestedPermissions?.contains(permissionToGrant) != true) {
                        val granted = HookHelpers.callMethod(
                            permissionsState,
                            "hasInstallPermission",
                            permissionToGrant,
                        ) as Boolean
                        if (!granted) {
                            val bpToGrant = HookHelpers.callMethod(permissions, "get", permissionToGrant)
                            val result = HookHelpers.callMethod(
                                permissionsState,
                                "grantInstallPermission",
                                bpToGrant,
                            ) as Int
                            XLog.d("Add $bpToGrant; result = $result")
                            grantedCount += 1
                        } else {
                            XLog.d("Already have $permissionToGrant permission")
                            alreadyCount += 1
                        }
                    }
                }
                MagiskOtel.event(
                    name = "prefs.access",
                    attributes = mapOf(
                        "result" to "ok",
                        "duration_ms" to "0",
                        "process" to "system_server",
                        "stage" to "permission_grant",
                        "reason" to "grant_apply",
                        "target_package" to packageName,
                        "found_count" to grantedCount.toString(),
                        "change_count" to (grantedCount + alreadyCount).toString(),
                    ),
                    statusOk = true,
                )
            }
        }
    }

    companion object {
        private const val CLASS_PERMISSION_MANAGER_SERVICE = "com.android.server.pm.permission.PermissionManagerService"
        private const val CLASS_PERMISSION_CALLBACK = "com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback"
        private const val CLASS_PACKAGE_PARSER_PACKAGE = "android.content.pm.PackageParser.Package"
        private const val CLASS_PERMISSION_CALLBACK_Q = "com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback"
    }
}
