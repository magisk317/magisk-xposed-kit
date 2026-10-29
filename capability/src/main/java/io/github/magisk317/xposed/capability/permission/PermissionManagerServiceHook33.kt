package io.github.magisk317.xposed.capability.hook.permission

import android.os.Build
import android.os.UserHandle
import androidx.annotation.RequiresApi
import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.capability.constant.PermConst.PACKAGE_PERMISSIONS
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.capability.helper.MethodHookWrapper
import io.github.magisk317.xposed.BaseSubHook
import io.github.magisk317.xposed.MethodHook
import io.github.magisk317.xposed.HookEnv
import io.github.magisk317.xposed.HookHelpers
import java.lang.reflect.Method
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.logging.MagiskOtel

/**
 * Since Android 13(API 33+)<br/>
 * Hook com.android.server.pm.permission.PermissionManagerService
 */
class PermissionManagerServiceHook33(classLoader: ClassLoader) : BaseSubHook(classLoader) {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun startHook() {
        try {
            if (hookGrantPermissions()) {
                MagiskOtel.event(
                    name = "hook.load",
                    attributes = mapOf(
                        "result" to "ok",
                        "duration_ms" to "0",
                        "process" to "system_server",
                        "stage" to "permission_grant",
                        "reason" to "installed",
                        "source" to "PermissionManagerServiceHook33",
                    ),
                    statusOk = true,
                )
            }
        } catch (e: ReflectiveOperationException) {
            XLog.e("Failed to hook PermissionManagerService", e)
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "error",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to e.javaClass.simpleName,
                    "source" to "PermissionManagerServiceHook33",
                ),
                statusOk = false,
            )
        } catch (e: IllegalArgumentException) {
            XLog.e("Failed to hook PermissionManagerService", e)
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "error",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to e.javaClass.simpleName,
                    "source" to "PermissionManagerServiceHook33",
                ),
                statusOk = false,
            )
        } catch (e: IllegalStateException) {
            XLog.e("Failed to hook PermissionManagerService", e)
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "error",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to e.javaClass.simpleName,
                    "source" to "PermissionManagerServiceHook33",
                ),
                statusOk = false,
            )
        } catch (e: SecurityException) {
            XLog.e("Failed to hook PermissionManagerService", e)
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "error",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to e.javaClass.simpleName,
                    "source" to "PermissionManagerServiceHook33",
                ),
                statusOk = false,
            )
        } catch (e: UnsupportedOperationException) {
            XLog.e("Failed to hook PermissionManagerService", e)
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "error",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to e.javaClass.simpleName,
                    "source" to "PermissionManagerServiceHook33",
                ),
                statusOk = false,
            )
        }
    }

    private fun hookGrantPermissions(): Boolean {
        XLog.d("Hooking grantPermissions() for Android 33+")
        val method = findTargetMethod()
        if (method == null) {
            XLog.e("Cannot find the method to grant relevant permission")
            MagiskOtel.event(
                name = "hook.load",
                attributes = mapOf(
                    "result" to "skip",
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to "permission_grant",
                    "reason" to "no_target",
                    "source" to "PermissionManagerServiceHook33",
                ),
                statusOk = true,
            )
            return false
        }
        HookEnv.api.hookMethod(
            method,
            object : MethodHookWrapper(LogRoute.PERMISSION_HOOK) {
                @Throws(Throwable::class)
                override fun after(param: MethodHookParam) {
                    afterRestorePermissionStateSinceAndroid13(param)
                }
            },
        )
        return true
    }

    private fun findTargetMethod(): Method? {
        val pmsClass = HookHelpers.findClass(CLASS_PERMISSION_MANAGER_SERVICE, mClassLoader)
        val androidPackageClass = HookHelpers.findClass(CLASS_ANDROID_PACKAGE, mClassLoader)
        val callbackClass = HookHelpers.findClassIfExists(CLASS_PERMISSION_CALLBACK, mClassLoader)

        // 精确匹配
        var method = HookHelpers.findMethodExactIfExists(
            pmsClass,
            "restorePermissionState",
            /* AndroidPackage pkg          */
            androidPackageClass,
            /* boolean replace             */
            Boolean::class.javaPrimitiveType,
            /* String packageOfInterest    */
            String::class.java,
            /* PermissionCallback callback */
            callbackClass,
            /* int filterUserId            */
            Int::class.javaPrimitiveType,
        )

        if (method == null) { // method restorePermissionState() not found
            // 参数类型精确匹配
            val methods = HookHelpers.findMethodsByExactParameters(
                pmsClass,
                Void.TYPE,
                /* AndroidPackage pkg          */
                androidPackageClass,
                /* boolean replace             */
                Boolean::class.javaPrimitiveType,
                /* String packageOfInterest    */
                String::class.java,
                /* PermissionCallback callback */
                callbackClass,
                /* int filterUserId            */
                Int::class.javaPrimitiveType,
            )
            if (methods.isNotEmpty()) {
                method = methods[0]
            }
        }
        if (method == null) {
            PermissionDebugProbe.dumpClass("PermissionManagerServiceHook33 missing restorePermissionState", pmsClass)
        }
        return method
    }

    private fun afterRestorePermissionStateSinceAndroid13(param: MethodHookParam) {
        // com.android.server.pm.parsing.pkg.AndroidPackage 对象
        val pkg = param.args[0]
        val packageNameInPkg = HookHelpers.callMethod(pkg, "getPackageName") as String

        for (packageName in PACKAGE_PERMISSIONS.keys) {
            if (packageName == packageNameInPkg) {
                XLog.d(message = "PackageName: %s", packageName)

                // PermissionManagerServiceImpl 对象
                val pmsImpl = param.thisObject

                // UserHandle.USER_ALL
                val filterUserId = param.args[4] as Int
                val userAll = HookHelpers.getStaticIntField(UserHandle::class.java, "USER_ALL")
                val userIds = if (filterUserId == userAll) {
                    HookHelpers.callMethod(pmsImpl, "getAllUserIds") as IntArray
                } else {
                    intArrayOf(filterUserId)
                }

                val permissionsToGrant = PACKAGE_PERMISSIONS[packageName] ?: continue

                // PackageManagerInternal 对象 mPackageManagerInt
                val mPackageManagerInt = HookHelpers.getObjectField(pmsImpl, "mPackageManagerInt")

                // PackageStateInternal 对象 ps
                val ps = HookHelpers.callMethod(mPackageManagerInt, "getPackageStateInternal", packageName)

                // Manifest.xml 中声明的permission列表
                val requestedPermissions = HookHelpers.callMethod(pkg, "getRequestedPermissions") as? List<*>

                // com.android.server.pm.permission.DevicePermissionState 对象
                val mState = HookHelpers.getObjectField(pmsImpl, "mState")

                // com.android.server.pm.permission.PermissionRegistry 对象
                val mRegistry = HookHelpers.getObjectField(pmsImpl, "mRegistry")

                for (userId in userIds) {
                    // com.android.server.pm.permission.UserPermissionState 对象
                    val userState = HookHelpers.callMethod(mState, "getOrCreateUserState", userId)
                    val appId = HookHelpers.callMethod(ps, "getAppId") as Int
                    //  com.android.server.pm.permission.UidPermissionState 对象
                    val uidState = HookHelpers.callMethod(userState, "getOrCreateUidState", appId)

                    for (permissionToGrant in permissionsToGrant) {
                        if (requestedPermissions?.contains(permissionToGrant) != true) {
                            val granted = HookHelpers.callMethod(
                                uidState,
                                "isPermissionGranted",
                                permissionToGrant,
                            ) as Boolean
                            if (!granted) {
                                // permission not grant before
                                val bpToGrant = HookHelpers.callMethod(mRegistry, "getPermission", permissionToGrant)
                                val result = HookHelpers.callMethod(uidState, "grantPermission", bpToGrant) as Boolean
                                XLog.d("Add $permissionToGrant; result = $result")
                            } else {
                                // permission has been granted already
                                XLog.d("Already have $permissionToGrant permission")
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val CLASS_PERMISSION_MANAGER_SERVICE = "com.android.server.pm.permission.PermissionManagerServiceImpl"
        private const val CLASS_ANDROID_PACKAGE = "com.android.server.pm.parsing.pkg.AndroidPackage"
        private const val CLASS_PERMISSION_CALLBACK = "$CLASS_PERMISSION_MANAGER_SERVICE.PermissionCallback"
    }
}
