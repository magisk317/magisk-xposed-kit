package io.github.magisk317.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified method hook callback. Supports two styles:
 *
 * **Classic (subclass)**: Override [beforeHookedMethod] and [afterHookedMethod].
 * **DSL**: Pass a [HookCallback] lambda that calls [HookContext.doBefore], [HookContext.doAfter], [HookContext.replace].
 *
 * DSL style takes precedence: if a DSL callback configured a [HookContext], the classic overrides are ignored.
 */
open class MethodHook {

    /** Stable identity for hot-reload hook matching. Subclasses should override. */
    open val stableId: String? get() = hookIdentity

    /** Internal identity derived from callback class name. */
    internal var hookIdentity: String = javaClass.name

    /** DSL context, populated only for DSL-style hooks. */
    internal var dslContext: HookContext? = null

    /** For DSL-style construction. */
    constructor(callback: HookCallback) {
        this.hookIdentity = callback.javaClass.name
        this.dslContext = HookContext(this).apply(callback)
    }

    /** For classic-style construction (subclass). */
    constructor()

    /** Classic-style: called before the hooked method. */
    @Throws(Throwable::class)
    open fun beforeHookedMethod(param: MethodHookParam) {}

    /** Classic-style: called after the hooked method. */
    @Throws(Throwable::class)
    open fun afterHookedMethod(param: MethodHookParam) {}

    internal fun intercept(
        chain: XposedInterface.Chain,
        handleRef: AtomicReference<XposedInterface.HookHandle?>,
    ): Any? {
        val param = MethodHookParam(
            method = chain.executable,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray(),
            chain = chain,
            handleProvider = { handleRef.get() },
        )

        val ctx = dslContext
        if (ctx != null) {
            return interceptDsl(ctx, param)
        }
        return interceptClassic(param)
    }

    private fun interceptDsl(ctx: HookContext, param: MethodHookParam): Any? {
        val replaceAction = ctx.replaceAction
        if (replaceAction != null && ctx.needHook?.invoke() != false) {
            runCatching {
                param.result = replaceAction.invoke(param)
            }.onFailure {
                param.throwable = it
            }
            ctx.afterAction?.invoke(param)
            param.throwable?.let { throw it }
            return param.result
        }

        ctx.beforeAction?.invoke(param)
        if (!param.returnEarly) {
            try {
                param.result = param.proceed()
                param.returnEarly = false
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                param.throwable = t
            }
        }
        ctx.afterAction?.invoke(param)
        param.throwable?.let { throw it }
        return param.result
    }

    private fun interceptClassic(param: MethodHookParam): Any? {
        val beforeFailure = runCatching {
            beforeHookedMethod(param)
        }.exceptionOrNull()
        if (beforeFailure != null) {
            markInterruptedIfNeeded(beforeFailure)
            rethrowIfFatal(beforeFailure)
        }
        if (param.returnEarly) {
            val throwable = param.throwable
            if (throwable != null) throw throwable
            return param.result
        }

        var result: Any? = null
        var throwable: Throwable? = null
        val proceedFailure = runCatching {
            result = param.proceed()
        }.exceptionOrNull()
        if (proceedFailure != null) {
            markInterruptedIfNeeded(proceedFailure)
            rethrowIfFatal(proceedFailure)
            throwable = proceedFailure
        }
        param.returnEarly = false
        param.result = result
        param.throwable = throwable
        val afterFailure = runCatching {
            afterHookedMethod(param)
        }.exceptionOrNull()
        if (afterFailure != null) {
            markInterruptedIfNeeded(afterFailure)
            rethrowIfFatal(afterFailure)
        }
        val finalThrowable = param.throwable
        if (finalThrowable != null) throw finalThrowable
        return param.result
    }

    companion object {
        private fun markInterruptedIfNeeded(t: Throwable) {
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun rethrowIfFatal(t: Throwable) {
            when (t) {
                is Error -> throw t
                is InterruptedException -> throw t
            }
        }
    }
}
