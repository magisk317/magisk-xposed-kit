package io.github.magisk317.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method as ReflectMethod
import java.lang.reflect.Proxy

/**
 * Guards that [BaseLibXposedEntry] installs [XposedRuntime] itself, in both lifecycle entry points.
 *
 * Where this came from: the contract said the consumer's `installModuleRuntime` installs
 * HookEnv *and* XposedRuntime, and only MiPushFramework read it that way. xinyi-relay wires
 * HookEnv alone, so the kit's own `hook()` DSL -- used by the keep-alive module's OomAdjuster hook
 * -- ran against an uninstalled runtime, and system_server logged
 * `IllegalStateException: libxposed runtime is not installed` instead of installing the hook.
 * The runtime is the base class's object, so the base class installs it.
 */
class BaseLibXposedEntryRuntimeInstallTest {

    @BeforeEach
    fun setup() {
        XposedRuntime.resetForTest()
    }

    @AfterEach
    fun teardown() {
        XposedRuntime.resetForTest()
    }

    @Test
    fun `moduleLoaded installs the runtime before the consumer wires its own`() {
        val entry = HookEnvOnlyEntry()
        entry.attachFramework(framework(apiVersion = 102), Runnable {})

        entry.onModuleLoaded(moduleLoadedParam("io.github.magisk317.relay.test"))

        assertSame(entry, XposedRuntime.module, "the entry is the module the runtime hooks with")
        assertNotNull(entry.hookApiSeenByInstaller, "installModuleRuntime must receive an api")
        assertSame(entry.hookApiSeenByInstaller, XposedRuntime.hookApi, "the runtime must carry the api handed to the consumer")
        assertSame(XposedRuntime.hookApi, runtimeApiDuringInstall(entry), "the runtime has to be live before installModuleRuntime runs")
    }

    // The api gate at the top of onModuleLoaded stays uncovered on purpose: getApiVersion on
    // XposedInterface is a default method returning the compile-time constant, so a JVM test cannot
    // attach a framework that reports an older api -- only a real framework can.
    @Test
    fun `hotReload replaces the runtime with the new api`() {
        val entry = HookEnvOnlyEntry()
        entry.attachFramework(framework(apiVersion = 102), Runnable {})
        entry.onModuleLoaded(moduleLoadedParam("io.github.magisk317.relay.test"))
        val firstApi = XposedRuntime.hookApi

        entry.onHotReloaded(hotReloadedParam())

        assertNotNull(XposedRuntime.hookApi, "hot reload must leave the runtime installed")
        assertNotNull(entry.hookApiSeenByInstaller)
        assertSame(entry.hookApiSeenByInstaller, XposedRuntime.hookApi)
        assertNotNull(firstApi)
    }

    /** Records what the consumer saw, without doing what xinyi does wrong (wiring only HookEnv). */
    private class HookEnvOnlyEntry : BaseLibXposedEntry() {
        override val hooks: List<BaseHook> = emptyList()

        var hookApiSeenByInstaller: LibXposedHookApi? = null
        var runtimeApiDuringInstall: LibXposedHookApi? = null

        override fun resolveCurrentProcessTargets(
            param: ModuleLoadedParam,
            oldHookHandles: List<XposedInterface.HookHandle>,
        ): Map<String, ClassLoader> = emptyMap()

        override fun installModuleRuntime(module: XposedModule, hookApi: LibXposedHookApi) {
            hookApiSeenByInstaller = hookApi
            runtimeApiDuringInstall = XposedRuntime.hookApi
            HookEnv.init(hookApi)
        }

        fun runtimeApiDuringInstall(): LibXposedHookApi? = runtimeApiDuringInstall
    }

    private fun runtimeApiDuringInstall(entry: HookEnvOnlyEntry): LibXposedHookApi? = entry.runtimeApiDuringInstall()

    private fun moduleLoadedParam(processName: String): ModuleLoadedParam {
        return proxyParam(ModuleLoadedParam::class.java) { method ->
            when (method.name) {
                "isSystemServer" -> false
                "getProcessName" -> processName
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun hotReloadedParam(): HotReloadedParam {
        return proxyParam(HotReloadedParam::class.java) { method ->
            when (method.name) {
                "getOldHookHandles" -> emptyList<XposedInterface.HookHandle>()
                "getSavedInstanceState" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun framework(apiVersion: Int): XposedInterface {
        return Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getApiVersion" -> apiVersion
                    "getFrameworkName" -> "libxposed"
                    "getFrameworkVersion" -> "test"
                    "getFrameworkVersionCode" -> 1L
                    "getFrameworkProperties" -> 0L
                    "log" -> Unit
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "TestFramework"
                    else -> defaultValue(method.returnType)
                }
            },
        ) as XposedInterface
    }

    private fun <T> proxyParam(type: Class<T>, handler: (ReflectMethod) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            InvocationHandler { _, method, _ -> handler(method) },
        ) as T
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Void.TYPE -> Unit
            else -> null
        }
    }
}
