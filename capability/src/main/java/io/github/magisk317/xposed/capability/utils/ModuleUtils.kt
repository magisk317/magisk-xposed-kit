package io.github.magisk317.xposed.capability.utils

import io.github.magisk317.xposed.logging.XLog

/**
 * 当前Xposed模块相关工具类
 */
object ModuleUtils {
    @Volatile
    private var runtimeActivated = false
    @Volatile
    private var moduleVersionFailureLogged = false
    @Volatile
    var moduleVersionResolverForTesting: (() -> Int)? = null

    /**
     * 标记当前运行时是否已连接到 Xposed Service。
     */
    @JvmStatic
    fun setRuntimeActivated(activated: Boolean) {
        runtimeActivated = activated
    }

    @JvmStatic
    fun isRuntimeActivated(): Boolean = runtimeActivated

    /**
     * 返回模块版本 <br/>
     * 注意：该方法被本模块Hook住，返回的值是 BuildConfig.MODULE_VERSION，如果没被Hook则返回-1
     */
    @JvmStatic
    fun getModuleVersion(): Int {
        XLog.d("getModuleVersion()")
        return -1
    }

    private fun resolveModuleVersionSafely(): Int {
        val resolver = moduleVersionResolverForTesting ?: ::getModuleVersion
        return runCatching { resolver() }
            .onFailure { throwable ->
                if (!moduleVersionFailureLogged) {
                    moduleVersionFailureLogged = true
                    XLog.w(
                        message = "getModuleVersion() failed, treating module as disabled: %s",
                        throwable.message ?: throwable.javaClass.simpleName,
                        throwable,
                    )
                }
            }
            .getOrDefault(-1)
    }

    /**
     * 当前模块是否在XposedInstaller中被启用
     */
    @JvmStatic
    fun isModuleEnabled(): Boolean = resolveModuleVersionSafely() > 0

    /**
     * 模块是否已激活（兼容新 API 静态作用域场景）
     */
    @JvmStatic
    fun isModuleActivated(context: android.content.Context): Boolean {
        return isModuleEnabled() || runtimeActivated || ModuleActivationStore.isActivatedRecently(context)
    }
}
