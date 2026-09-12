package io.github.magisk317.xposed.preferences

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedPreferencesSourceTest {

    @Test
    fun `reads primitives and preserves source attribution`() {
        val source = SharedPreferencesSource(
            sourceName = "remote",
            provider = { preferences(mapOf("enabled" to "yes", "count" to "7", "name" to 42)) },
        )

        assertEquals(PreferenceRead.Hit(true, "remote"), source.readBoolean("enabled", false))
        assertEquals(PreferenceRead.Hit(7, "remote"), source.readInt("count", 0))
        assertEquals(PreferenceRead.Hit("42", "remote"), source.readString("name", ""))
    }

    @Test
    fun `distinguishes missing from unavailable`() {
        val missing = SharedPreferencesSource("remote", { preferences(emptyMap()) })
        val unavailable = SharedPreferencesSource("remote", { null })

        assertEquals(PreferenceRead.Missing, missing.readBoolean("enabled", false))
        assertEquals(PreferenceRead.Unavailable, unavailable.readBoolean("enabled", false))
    }

    @Test
    fun `provider exception is contained and reported`() {
        val failures = AtomicInteger()
        val source = SharedPreferencesSource(
            sourceName = "remote",
            provider = { error("provider unavailable") },
            onError = { _, _ -> failures.incrementAndGet() },
        )

        assertEquals(PreferenceRead.Unavailable, source.readString("name", "fallback"))
        assertEquals(1, failures.get())
    }

    @Test
    fun `reads a LibXposed remote provider without adding write or file fallback semantics`() {
        val remote = preferences(mapOf("enabled" to true))
        val xposed = proxyXposed { method, _ ->
            if (method.name == "getRemotePreferences") remote else defaultValue(method.returnType)
        }
        val source = LibXposedPreferenceSources.remote(xposed, "module_runtime_preferences")

        assertEquals("remote_libxposed:module_runtime_preferences", source.sourceName)
        assertEquals(PreferenceRead.Hit(true, source.sourceName), source.readBoolean("enabled", false))
    }

    @Test
    fun `remote provider exceptions become unavailable`() {
        val failures = AtomicInteger()
        val xposed = proxyXposed { method, _ ->
            if (method.name == "getRemotePreferences") {
                throw UnsupportedOperationException("embedded framework")
            }
            defaultValue(method.returnType)
        }
        val source = LibXposedPreferenceSources.remote(
            xposed = xposed,
            group = "module_runtime_preferences",
            onError = { _, _ -> failures.incrementAndGet() },
        )

        assertEquals(PreferenceRead.Unavailable, source.readInt("count", 0))
        assertTrue(failures.get() == 1)
    }

    private fun preferences(values: Map<String, Any?>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getAll" -> values
                    "contains" -> values.containsKey(args?.firstOrNull() as? String)
                    else -> defaultValue(method.returnType)
                }
            },
        ) as SharedPreferences

    private fun proxyXposed(handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): XposedInterface =
        Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
            InvocationHandler { _, method, args -> handler(method, args) },
        ) as XposedInterface

    private fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
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
