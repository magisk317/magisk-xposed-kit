package io.github.magisk317.xposed.capability.me

import io.github.magisk317.xposed.capability.utils.ModuleUtils
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.BaseHook
import io.github.magisk317.xposed.HookEnv
import io.github.magisk317.xposed.HookHelpers
import io.github.magisk317.xposed.LoadParam
import io.github.magisk317.xposed.MethodHook
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.logging.MagiskOtel

open class ModuleUtilsHook(
    private val targetPackage: String,
    private val moduleVersion: Int,
) : BaseHook() {

    @Throws(Throwable::class)
    override fun onLoadPackage(param: LoadParam) {
        if (targetPackage == param.packageName) {
            try {
                XLog.i("Hooking current Xposed module status...")
                hookModuleUtils(param)
                MagiskOtel.event(
                    name = "hook.load",
                    attributes = mapOf(
                        "result" to "ok",
                        "duration_ms" to "0",
                        "process" to "app",
                        "stage" to "module_status",
                        "reason" to "version_hook_installed",
                        "target_package" to targetPackage,
                    ),
                    statusOk = true,
                )
            } catch (e: ReflectiveOperationException) {
                emitInstallError(e)
                XLog.e("Failed to hook current Xposed module status.")
            } catch (e: IllegalArgumentException) {
                emitInstallError(e)
                XLog.e("Failed to hook current Xposed module status.")
            } catch (e: IllegalStateException) {
                emitInstallError(e)
                XLog.e("Failed to hook current Xposed module status.")
            } catch (e: SecurityException) {
                emitInstallError(e)
                XLog.e("Failed to hook current Xposed module status.")
            } catch (e: UnsupportedOperationException) {
                emitInstallError(e)
                XLog.e("Failed to hook current Xposed module status.")
            }
        }
    }

    @Throws(Throwable::class)
    private fun hookModuleUtils(lpparam: LoadParam) {
        val className = ModuleUtils::class.java.name
        val clazz = HookHelpers.findClass(className, lpparam.classLoader)
        val method = HookHelpers.findMethodExactIfExists(clazz, "getModuleVersion")
        method?.let {
            HookEnv.api.hookMethod(it, object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = moduleVersion
                }
            })
        }
    }

    private fun emitInstallError(error: Throwable) {
        MagiskOtel.event(
            name = "hook.load",
            attributes = mapOf(
                "result" to "error",
                "duration_ms" to "0",
                "process" to "app",
                "stage" to "module_status",
                "reason" to "version_hook_failed",
                "target_package" to targetPackage,
                "error_class" to error.javaClass.simpleName,
            ),
            statusOk = false,
        )
    }
}
