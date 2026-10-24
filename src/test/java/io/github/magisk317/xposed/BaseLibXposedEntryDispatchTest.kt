package io.github.magisk317.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method as ReflectMethod
import java.lang.reflect.Proxy

/**
 * Covers dispatch dedup and provisional-classLoader handling.
 *
 * The regression these guard against: dedup keyed on package alone let a dispatch that used a
 * fallback classLoader consume the key, so the later callback carrying the real loader was
 * skipped as a duplicate and the package silently never got working hooks.
 */
class BaseLibXposedEntryDispatchTest {

    private lateinit var entry: TestEntry
    private lateinit var hook: RecordingHook

    @BeforeEach
    fun setup() {
        // Route the entry's logging through a module so it does not hit android.util.Log stubs.
        XposedRuntime.install(TestModule().apply { attachFramework(loggingFramework(), Runnable {}) })
        hook = RecordingHook()
        entry = TestEntry(hook)
    }

    @AfterEach
    fun teardown() {
        XposedRuntime.resetForTest()
    }

    @Test
    fun `dispatch with a different classLoader is not deduped away`() {
        val provisional = ClassLoader.getSystemClassLoader()
        val real = TestClassLoader()

        entry.dispatch("com.android.phone", provisional, phase = "packageLoaded")
        entry.dispatch("com.android.phone", real, phase = "packageReady")

        assertEquals(listOf(provisional, real), hook.loaders)
    }

    @Test
    fun `repeat dispatch with the same classLoader is skipped`() {
        val loader = TestClassLoader()

        entry.dispatch("com.android.phone", loader, phase = "packageLoaded")
        entry.dispatch("com.android.phone", loader, phase = "packageReady")

        assertEquals(listOf<ClassLoader>(loader), hook.loaders)
    }

    @Test
    fun `re-dispatch is bounded per package`() {
        repeat(6) { entry.dispatch("com.android.phone", TestClassLoader(), phase = "packageLoaded") }

        assertEquals(3, hook.loaders.size, "dispatch count must stay bounded")
    }

    @Test
    fun `dispatch limit is tracked per package not globally`() {
        repeat(4) { entry.dispatch("com.android.phone", TestClassLoader(), phase = "packageLoaded") }
        val mmsLoader = TestClassLoader()
        entry.dispatch("com.android.mms", mmsLoader, phase = "packageLoaded")

        assertTrue(hook.loaders.contains(mmsLoader), "a different package must still dispatch")
    }

    @Test
    fun `package ready supersedes a provisional package loaded entry`() {
        // In a JVM test ActivityThread reflection cannot resolve, so onPackageLoaded degrades to
        // the provisional context classLoader — the exact path that used to poison the dedup key.
        entry.onPackageLoaded(packageLoadedParam("com.android.phone", defaultClassLoader = null))
        val provisionalCount = hook.loaders.size

        val real = TestClassLoader()
        entry.onPackageReady(packageReadyParam("com.android.phone", real))

        assertTrue(provisionalCount >= 1, "provisional dispatch should have happened")
        assertSame(real, hook.loaders.last(), "the authoritative loader must reach the hooks")
        assertSame(real, entry.recordedLoader("com.android.phone"), "recorded loader must be replaced")
    }

    @Test
    fun `package loaded survives a hook that throws`() {
        val throwing = ThrowingHook()
        val throwingEntry = TestEntry(throwing)

        // Must not propagate: this callback runs in the target process, where an escaping
        // throwable is fatal to the host app.
        throwingEntry.onPackageLoaded(packageLoadedParam("com.android.phone", defaultClassLoader = null))

        assertTrue(throwing.called, "hook should still have been invoked")
    }

    @Test
    fun `deferred hook skips packageLoaded and runs once at packageReady`() {
        val deferred = DeferredRecordingHook()
        val deferredEntry = TestEntry(deferred)
        val loader = TestClassLoader()

        deferredEntry.dispatch("com.taobao.idlefish", loader, phase = "packageLoaded")
        assertTrue(deferred.loaders.isEmpty(), "deferred hook must not run inside createAppFactory")

        deferredEntry.dispatch("com.taobao.idlefish", loader, phase = "packageReady")
        deferredEntry.dispatch("com.taobao.idlefish", loader, phase = "packageReady")

        assertEquals(listOf<ClassLoader>(loader), deferred.loaders)
    }

    @Test
    fun `deferred hook runs when the first dispatch is already a ready phase`() {
        val deferred = DeferredRecordingHook()
        val deferredEntry = TestEntry(deferred)
        val loader = TestClassLoader()

        deferredEntry.dispatch("com.taobao.idlefish", loader, phase = "moduleLoadedCurrentProcess")

        assertEquals(listOf<ClassLoader>(loader), deferred.loaders)
    }

    @Test
    fun `eager hooks keep running at packageLoaded when a deferred hook is present`() {
        val eager = RecordingHook()
        val deferred = DeferredRecordingHook()
        val mixedEntry = TestEntry(eager, deferred)
        val loader = TestClassLoader()

        mixedEntry.dispatch("com.taobao.idlefish", loader, phase = "packageLoaded")

        assertEquals(listOf<ClassLoader>(loader), eager.loaders)
        assertTrue(deferred.loaders.isEmpty(), "only the opted-in hook is deferred")

        mixedEntry.dispatch("com.taobao.idlefish", loader, phase = "packageReady")

        assertEquals(listOf<ClassLoader>(loader), eager.loaders, "eager hook must not run twice")
        assertEquals(listOf<ClassLoader>(loader), deferred.loaders)
    }

    @Test
    fun `deferred hook runs on packageReady after a provisional packageLoaded`() {
        val deferred = DeferredRecordingHook()
        val deferredEntry = TestEntry(deferred)

        deferredEntry.onPackageLoaded(packageLoadedParam("com.taobao.idlefish", defaultClassLoader = null))
        assertTrue(deferred.loaders.isEmpty())

        val real = TestClassLoader()
        deferredEntry.onPackageReady(packageReadyParam("com.taobao.idlefish", real))

        assertEquals(listOf<ClassLoader>(real), deferred.loaders)
    }

    // -- Helpers --

    private class TestEntry(vararg hooks: BaseHook) : BaseLibXposedEntry() {
        override val hooks: List<BaseHook> = hooks.toList()

        override fun installModuleRuntime(module: XposedModule, hookApi: LibXposedHookApi) {}

        fun dispatch(packageName: String, classLoader: ClassLoader, phase: String) {
            dispatchLoad(LoadParam(packageName, packageName, classLoader), phase)
        }

        fun recordedLoader(packageName: String): ClassLoader? = loadedPackages[packageName]
    }

    private class RecordingHook : BaseHook() {
        val loaders = mutableListOf<ClassLoader>()

        override fun onLoadPackage(param: LoadParam) {
            loaders += param.classLoader
        }
    }

    private class DeferredRecordingHook : BaseHook() {
        val loaders = mutableListOf<ClassLoader>()

        override fun deferUntilPackageReady(): Boolean = true

        override fun onLoadPackage(param: LoadParam) {
            loaders += param.classLoader
        }
    }

    private class ThrowingHook : BaseHook() {
        var called = false

        override fun onLoadPackage(param: LoadParam) {
            called = true
            throw IllegalStateException("boom")
        }
    }

    private class TestClassLoader : ClassLoader(getSystemClassLoader())

    private class TestModule : XposedModule()

    private fun loggingFramework(): XposedInterface {
        return Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "log" -> Unit
                    "getFrameworkName" -> "libxposed"
                    "getFrameworkVersion" -> "test"
                    "getFrameworkVersionCode" -> 1L
                    "getFrameworkProperties" -> 0L
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "LoggingFramework"
                    else -> defaultValue(method.returnType)
                }
            },
        ) as XposedInterface
    }

    private fun packageLoadedParam(
        packageName: String,
        defaultClassLoader: ClassLoader?,
    ): PackageLoadedParam {
        return proxyParam(PackageLoadedParam::class.java) { method ->
            when (method.name) {
                "getPackageName" -> packageName
                "getDefaultClassLoader", "getClassLoader" -> defaultClassLoader
                "isFirstPackage" -> true
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun packageReadyParam(packageName: String, classLoader: ClassLoader): PackageReadyParam {
        return proxyParam(PackageReadyParam::class.java) { method ->
            when (method.name) {
                "getPackageName" -> packageName
                "getClassLoader", "getDefaultClassLoader" -> classLoader
                "isFirstPackage" -> true
                else -> defaultValue(method.returnType)
            }
        }
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
