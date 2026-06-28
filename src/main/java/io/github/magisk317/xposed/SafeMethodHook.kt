package io.github.magisk317.xposed

/**
 * A [MethodHook] subclass that wraps [before] / [after] callbacks with
 * non-fatal exception handling: [InterruptedException] is restored on the
 * current thread, and fatal errors (VM errors, [ThreadDeath], [LinkageError])
 * are rethrown immediately.
 *
 * Subclasses override [before] / [after] instead of the raw [beforeHookedMethod] /
 * [afterHookedMethod].
 */
abstract class SafeMethodHook(
    override val stableId: String? = null,
) : MethodHook() {

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val failure = runCatching {
            before(param)
        }.exceptionOrNull()
        if (failure != null) {
            failure.markInterruptedIfNeeded()
            failure.rethrowIfFatal()
            onHookError(param, failure, phase = "before")
        }
    }

    @Throws(Throwable::class)
    protected open fun before(param: MethodHookParam) {
    }

    @Throws(Throwable::class)
    override fun afterHookedMethod(param: MethodHookParam) {
        val failure = runCatching {
            after(param)
        }.exceptionOrNull()
        if (failure != null) {
            failure.markInterruptedIfNeeded()
            failure.rethrowIfFatal()
            onHookError(param, failure, phase = "after")
        }
    }

    @Throws(Throwable::class)
    protected open fun after(param: MethodHookParam) {
    }

    /**
     * Called when [before] or [after] throws a non-fatal exception.
     * Default implementation is a no-op; override to log or report.
     */
    protected open fun onHookError(param: MethodHookParam, error: Throwable, phase: String) {
    }
}
