package io.github.magisk317.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.magisk317.xposed.logging.DefaultLogSanitizer
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/**
 * Central runtime managing the active [XposedModule] instance, hook API, and logging.
 * Installed by [BaseLibXposedEntry] during module loading.
 */
object XposedRuntime {
    @Volatile
    internal var module: XposedModule? = null
        private set

    @Volatile
    internal var hookApi: LibXposedHookApi? = null
        private set

    fun install(module: XposedModule, hookApi: LibXposedHookApi) {
        this.module = module
        this.hookApi = hookApi
    }

    fun install(module: XposedModule, hookIdPrefix: String = "xposed-kit") {
        install(module, LibXposedHookApi(module, hookIdPrefix))
    }

    fun hook(executable: Executable, methodHook: MethodHook): HookHandle {
        if (executable is Method && (Modifier.isAbstract(executable.modifiers) || Modifier.isNative(executable.modifiers))) {
            return HookHandle(null)
        }
        val activeHookApi = hookApi ?: throw IllegalStateException("libxposed runtime is not installed")
        executable.isAccessible = true
        val handleRef = AtomicReference<XposedInterface.HookHandle?>()
        val hooker = MethodHookerImpl(methodHook, handleRef)
        val handle = activeHookApi.hookExecutable(executable, methodHook, hooker)
        handleRef.set(handle)
        return HookHandle(handle)
    }

    internal fun invokeOriginal(member: Member, thisObject: Any?, args: Array<out Any?>): Any? {
        val activeModule = module ?: return invokeMemberRaw(member, thisObject, args)
        return when (member) {
            is Method -> activeModule.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *args)
            is Constructor<*> -> activeModule.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .newInstance(*args)
            else -> throw IllegalArgumentException("Unsupported member: $member")
        }
    }

    fun log(priority: Int, tag: String?, message: String, throwable: Throwable? = null) {
        val activeModule = module
        val safeThrowable = throwable?.let(::sanitizeThrowable)
        if (activeModule == null) {
            if (safeThrowable == null) {
                Log.println(priority, tag ?: "XposedKit", message)
            } else {
                Log.println(priority, tag ?: "XposedKit", "$message\n${Log.getStackTraceString(safeThrowable)}")
            }
            return
        }
        if (safeThrowable == null) {
            activeModule.log(priority, tag, message)
        } else {
            activeModule.log(priority, tag, message, safeThrowable)
        }
    }

    private fun sanitizeThrowable(throwable: Throwable): Throwable {
        val safeText = DefaultLogSanitizer.sanitizeIfEnabled(throwable.stackTraceToString())
        return Throwable(safeText).apply {
            // Keep the platform output bounded and prevent the original throwable from being
            // retained as a cause or rendered again by the Xposed logger.
            stackTrace = emptyArray()
        }
    }

    internal fun resetForTest() {
        module = null
        hookApi = null
    }

    private class MethodHookerImpl(
        private val methodHook: MethodHook,
        private val handleRef: AtomicReference<XposedInterface.HookHandle?>,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            return methodHook.intercept(chain, handleRef)
        }
    }

    private fun invokeMemberRaw(member: Member, thisObject: Any?, args: Array<out Any?>): Any? {
        return when (member) {
            is Method -> {
                member.isAccessible = true
                member.invoke(thisObject, *args)
            }
            is Constructor<*> -> {
                member.isAccessible = true
                member.newInstance(*args)
            }
            else -> throw IllegalArgumentException("Unsupported member: $member")
        }
    }
}
