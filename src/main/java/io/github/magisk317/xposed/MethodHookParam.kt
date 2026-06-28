package io.github.magisk317.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Member

/**
 * Unified method hook parameter. Provides access to the hooked method, arguments,
 * result, and the ability to invoke the original method or unhook.
 */
class MethodHookParam(
    val method: Member,
    var thisObject: Any?,
    var args: Array<Any?>,
    private val chain: XposedInterface.Chain?,
    private val handleProvider: () -> XposedInterface.HookHandle?,
) {
    /** Convenience constructor for testing (no chain/handle). */
    constructor(method: Member, thisObject: Any?, args: Array<Any?>) : this(method, thisObject, args, null, { null })

    var returnEarly: Boolean = false

    var result: Any? = null
        set(value) {
            field = value
            throwable = null
            returnEarly = true
        }

    var throwable: Throwable? = null
        set(value) {
            field = value
            returnEarly = true
        }

    fun hasThrowable(): Boolean = throwable != null

    /** Invoke the original (un-hooked) method. */
    fun invokeOriginal(): Any? {
        val member = method
        val module = XposedRuntime.module ?: return invokeMemberRaw(member, thisObject, args)
        return when (member) {
            is Method -> module.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *args)
            is Constructor<*> -> module.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .newInstance(*args)
            else -> throw IllegalArgumentException("Unsupported member: $member")
        }
    }

    /** Proceed to the next hook in the chain (or the original method). */
    internal fun proceed(): Any? {
        return chain?.proceed(args)
    }

    /** Unhook the current hook handle. */
    fun unhook() {
        handleProvider()?.unhook()
    }

    companion object {
        /** Convenience factory for testing. */
        @JvmStatic
        fun simple(method: Member, thisObject: Any?, args: Array<Any?> = emptyArray()): MethodHookParam {
            return MethodHookParam(method, thisObject, args)
        }
    }

    private fun invokeMemberRaw(member: Member, thisObject: Any?, args: Array<Any?>): Any? {
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

/** Backward-compatible alias for creating a [MethodHookParam] without chain/handle (for testing). */
fun SimpleMethodHookParam(method: Member, thisObject: Any?, args: Array<Any?> = emptyArray()): MethodHookParam {
    return MethodHookParam(method, thisObject, args)
}
