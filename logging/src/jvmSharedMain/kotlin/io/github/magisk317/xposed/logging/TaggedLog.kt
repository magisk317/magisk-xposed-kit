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

    fun v(throwable: Throwable?, message: () -> String) = dispatch(LogLevel.VERBOSE, message(), throwable)

    fun v(message: () -> String) = dispatch(LogLevel.VERBOSE, message(), null)

    fun d(message: String, throwable: Throwable? = null) = dispatch(LogLevel.DEBUG, message, throwable)

    fun d(throwable: Throwable?, message: () -> String) = dispatch(LogLevel.DEBUG, message(), throwable)

    fun d(message: () -> String) = dispatch(LogLevel.DEBUG, message(), null)

    fun i(message: String, throwable: Throwable? = null) = dispatch(LogLevel.INFO, message, throwable)

    fun i(throwable: Throwable?, message: () -> String) = dispatch(LogLevel.INFO, message(), throwable)

    fun i(message: () -> String) = dispatch(LogLevel.INFO, message(), null)

    fun w(message: String, throwable: Throwable? = null) = dispatch(LogLevel.WARN, message, throwable)

    fun w(throwable: Throwable?, message: () -> String) = dispatch(LogLevel.WARN, message(), throwable)

    fun w(message: () -> String) = dispatch(LogLevel.WARN, message(), null)

    fun e(message: String, throwable: Throwable? = null) = dispatch(LogLevel.ERROR, message, throwable)

    fun e(throwable: Throwable?, message: () -> String) = dispatch(LogLevel.ERROR, message(), throwable)

    fun e(message: () -> String) = dispatch(LogLevel.ERROR, message(), null)

    private fun dispatch(level: LogLevel, message: String, throwable: Throwable?) {
        if (throwable == null) {
            XLog.log(level.priority, tag, null, false, true, message)
        } else {
            XLog.log(level.priority, tag, null, false, true, message, throwable)
        }
    }
}
