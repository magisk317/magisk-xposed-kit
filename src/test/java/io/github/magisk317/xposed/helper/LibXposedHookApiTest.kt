package io.github.magisk317.xposed.helper

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.magisk317.xposed.LibXposedHookApi
import io.github.magisk317.xposed.MethodHook
import java.lang.reflect.Executable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method as ReflectMethod
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibXposedHookApiTest {
    @Test
    fun hookUsesHookIdEnhancedInstall() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")

        val handle = api.hookMethod(targetMethod, MethodHook())

        assertNotNull(handle)
        assertEquals(1, framework.hookCalls)
        assertEquals(1, framework.builders.single().setIdCalls)
        assertTrue(framework.builders.single().id.orEmpty().startsWith("smscode:"))
        assertEquals(1, framework.builders.single().interceptCalls)
    }

    @Test
    fun hotReloadReplacesOldHookById() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")
        val callback = MethodHook()
        api.hookMethod(targetMethod, callback)
        val oldHandle = RecordingHookHandle(targetMethod, id = framework.builders.single().id)

        api.beginHotReload(listOf(oldHandle))
        val replacement = api.hookMethod(targetMethod, callback)

        assertNotNull(replacement)
        assertEquals(1, oldHandle.replaceCalls)
        assertEquals(1, framework.hookCalls)
        assertEquals(0, api.finishHotReload())
    }

    @Test
    fun hotReloadReplacesOldHookByExecutableSignatureWhenCallbackIdChanged() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")
        api.hookMethod(targetMethod, MethodHook())
        val currentId = framework.builders.single().id.orEmpty()
        framework.builders.clear()
        framework.hookCalls = 0
        val oldHandle = RecordingHookHandle(
            targetMethod,
            id = "smscode:old.module.Callback@${currentId.substringAfter('@')}",
        )

        api.beginHotReload(listOf(oldHandle))
        val replacement = api.hookMethod(targetMethod, MethodHook())

        assertNotNull(replacement)
        assertEquals(1, oldHandle.replaceCalls)
        assertEquals(0, framework.hookCalls)
        assertEquals(0, api.finishHotReload())
    }

    @Test
    fun hotReloadReplacesOldHookByLegacyCallbackClassWhenStableIdIsAdded() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")
        val oldCallback = StableTestHook()
        api.hookMethod(targetMethod, oldCallback)
        val oldHandle = RecordingHookHandle(targetMethod, id = framework.builders.single().id)
        framework.builders.clear()
        framework.hookCalls = 0

        api.beginHotReload(listOf(oldHandle))
        val replacement = api.hookMethod(
            targetMethod,
            StableTestHook("stable.callback.id"),
        )

        assertNotNull(replacement)
        assertEquals(1, oldHandle.replaceCalls)
        assertEquals(0, framework.hookCalls)
        assertEquals(0, api.finishHotReload())
    }

    private class StableTestHook(override val stableId: String? = null) : MethodHook()

    @Test
    fun beginHotReloadHandlesEmptyId() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")
        val oldHandle = RecordingHookHandle(targetMethod, id = null)

        api.beginHotReload(listOf(oldHandle))
        val replacement = api.hookMethod(targetMethod, MethodHook()) // Different hook, shouldn't reuse empty ID handle anyway

        assertNotNull(replacement)
        assertEquals(0, oldHandle.replaceCalls)
        assertEquals(1, api.finishHotReload()) // The empty ID handle should be stale and unhooked
        assertEquals(1, oldHandle.unhookCalls)
    }

    @Test
    fun finishHotReloadIgnoresUnhookFailure() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")
        val oldHandle = object : RecordingHookHandle(targetMethod, id = "some-id") {
            override fun unhook() {
                super.unhook()
                throw RuntimeException("Simulated unhook failure")
            }
        }

        api.beginHotReload(listOf(oldHandle))
        assertEquals(1, api.finishHotReload()) // Should still return 1 removed handle and not crash
        assertEquals(1, oldHandle.unhookCalls)
    }

    @Test
    fun hotReloadHandlesDuplicateIdsCorrectly() {
        val framework = RecordingFramework()
        val api = LibXposedHookApi(testModule(framework), "smscode")
        val callback = MethodHook()
        // Simulate hooking same method twice to generate duplicate IDs
        api.hookMethod(targetMethod, callback)
        api.hookMethod(targetMethod, callback)
        val hookId = framework.builders.first().id
        
        val oldHandle1 = RecordingHookHandle(targetMethod, id = hookId)
        val oldHandle2 = RecordingHookHandle(targetMethod, id = hookId)

        api.beginHotReload(listOf(oldHandle1, oldHandle2))
        
        val replacement1 = api.hookMethod(targetMethod, callback)
        val replacement2 = api.hookMethod(targetMethod, callback)
        val replacement3 = api.hookMethod(targetMethod, callback) // 3rd call should create a new hook

        assertNotNull(replacement1)
        assertNotNull(replacement2)
        assertNotNull(replacement3)
        assertEquals(1, oldHandle1.replaceCalls)
        assertEquals(1, oldHandle2.replaceCalls)
        // Hook was called 2 times originally, 3rd call inside replacement creates a new one
        assertEquals(3, framework.hookCalls) 
        assertEquals(0, api.finishHotReload())
    }

    private fun testModule(framework: RecordingFramework): XposedModule {
        return TestModule().apply {
            attachFramework(framework.proxy, Runnable {})
        }
    }

    private class TestModule : XposedModule()

    private class RecordingFramework : InvocationHandler {
        val proxy: XposedInterface = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
            this,
        ) as XposedInterface
        val builders = mutableListOf<RecordingHookBuilder>()
        var hookCalls = 0

        override fun invoke(proxy: Any, method: ReflectMethod, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "hook" -> {
                    hookCalls += 1
                    RecordingHookBuilder(args?.first() as Executable).also(builders::add)
                }
                "getFrameworkName" -> "libxposed"
                "getFrameworkVersion" -> "test"
                "getFrameworkVersionCode" -> 1L
                "getFrameworkProperties" -> 0L
                "log" -> Unit
                "toString" -> "RecordingFramework"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
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

    private class RecordingHookBuilder(
        private val executable: Executable,
    ) : XposedInterface.HookBuilder {
        var id: String? = null
        var setIdCalls = 0
        var interceptCalls = 0

        override fun setPriority(priority: Int): XposedInterface.HookBuilder = this

        override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder = this

        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            interceptCalls += 1
            return RecordingHookHandle(executable, id)
        }

        override fun setId(id: String?): XposedInterface.HookBuilder {
            setIdCalls += 1
            this.id = id
            return this
        }
    }

    private open class RecordingHookHandle(
        private val executable: Executable,
        private val id: String?,
    ) : XposedInterface.HookHandle {
        var replaceCalls = 0
        var unhookCalls = 0

        override fun getExecutable(): Executable = executable

        override fun unhook() {
            unhookCalls += 1
        }

        override fun getId(): String? = id

        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            replaceCalls += 1
            return RecordingHookHandle(executable, id)
        }
    }

    private class Target {
        @Suppress("unused")
        fun target(value: String): String = value
    }

    private companion object {
        val targetMethod: ReflectMethod = Target::class.java.getDeclaredMethod("target", String::class.java)
    }
}
