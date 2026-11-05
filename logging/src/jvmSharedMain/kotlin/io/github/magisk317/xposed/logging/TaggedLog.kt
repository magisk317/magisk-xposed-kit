package io.github.magisk317.xposed.logging

/**
 * A tag-bound handle over [XLog], mirroring the tagged-logger call shape the
 * repos used before unification: `XLog.tagged("Push").i { "…" }`,
 * `…i(err) { "…" }`, `…i("…")`. Holds no state — every call dispatches into
 * [XLog] immediately, so the global configuration (level gate, sink,
 * sanitizer, print switch) applies to tagged calls exactly as to static ones.
 */
class TaggedLog internal constructor(private val tag: String) {

    fun v(message: String, throwable: Throwable? = null) = dispatch(LogLevel.VERBOSE, message, throwable)

    fun v(throwable: Throwable?, message: () -> String) = dispatchLazy(LogLevel.VERBOSE, throwable, message)

    fun v(message: () -> String) = dispatchLazy(LogLevel.VERBOSE, null, message)

    fun d(message: String, throwable: Throwable? = null) = dispatch(LogLevel.DEBUG, message, throwable)

    fun d(throwable: Throwable?, message: () -> String) = dispatchLazy(LogLevel.DEBUG, throwable, message)

    fun d(message: () -> String) = dispatchLazy(LogLevel.DEBUG, null, message)

    fun i(message: String, throwable: Throwable? = null) = dispatch(LogLevel.INFO, message, throwable)

    fun i(throwable: Throwable?, message: () -> String) = dispatchLazy(LogLevel.INFO, throwable, message)

    fun i(message: () -> String) = dispatchLazy(LogLevel.INFO, null, message)

    fun w(message: String, throwable: Throwable? = null) = dispatch(LogLevel.WARN, message, throwable)

    fun w(throwable: Throwable?, message: () -> String) = dispatchLazy(LogLevel.WARN, throwable, message)

    fun w(message: () -> String) = dispatchLazy(LogLevel.WARN, null, message)

    fun e(message: String, throwable: Throwable? = null) = dispatch(LogLevel.ERROR, message, throwable)

    fun e(throwable: Throwable?, message: () -> String) = dispatchLazy(LogLevel.ERROR, throwable, message)

    fun e(message: () -> String) = dispatchLazy(LogLevel.ERROR, null, message)

    private fun dispatchLazy(level: LogLevel, throwable: Throwable?, message: () -> String) {
        if (!MagiskLogPolicy.current().loggingEnabled) return
        dispatch(level, message(), throwable)
    }

    private fun dispatch(level: LogLevel, message: String, throwable: Throwable?) {
        if (throwable == null) {
            XLog.log(level.priority, tag, null, false, true, message)
        } else {
            XLog.log(level.priority, tag, null, false, true, message, throwable)
        }
    }
}
