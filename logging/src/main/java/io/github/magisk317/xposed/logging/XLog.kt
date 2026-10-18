package io.github.magisk317.xposed.logging

import android.util.Log

/**
 * Unified structured logger, neutral to any host application's domain.
 *
 * Output is routed through a pluggable [LogSink] (set via [install]).
 * Routes are opaque [String] identifiers; callers that need typed routes
 * (e.g. an enum) should convert to the route id at the call site.
 *
 * Usage:
 * ```
 * XLog.d(message = "loading package: %s", packageName)
 * XLog.withRoute("sms_hook") { XLog.i("initialized") }
 * ```
 *
 * Overload-resolution caveat: with two positional String arguments, Kotlin
 * prefers the (tag, message, vararg) overload over (message, vararg), so
 * `XLog.d("fmt %s", arg)` would treat `"fmt %s"` as the tag and `arg` as the
 * raw message. Always name the format string: `XLog.d(message = "fmt %s", arg)`.
 */
object XLog {

    private val routeContext = ThreadLocal<String?>()

    @Volatile
    private var sink: LogSink? = null

    @Volatile
    private var sensitiveMessageSanitizer: LogSanitizer = DefaultLogSanitizer

    @Volatile
    private var logTag: String = "magisk-xposed-kit"

    @Volatile
    private var sLogLevel: Int = Log.INFO

    @Volatile
    private var logToXposed: Boolean = false

    @Volatile
    private var defaultRoute: String? = null

    @Volatile
    private var testSink: ((Int, String) -> Unit)? = null

    @Volatile
    private var logPrinterForTest: ((Int, String, String) -> Unit)? = null

    /**
     * Configure the logger. Call once during module initialization.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        tag: String,
        logLevel: Int = Log.INFO,
        logToXposed: Boolean = false,
        sink: LogSink? = null,
        sanitizer: LogSanitizer = DefaultLogSanitizer,
        defaultRoute: String? = null,
    ) {
        this.logTag = tag
        this.sLogLevel = logLevel
        this.logToXposed = logToXposed
        this.sink = sink
        this.sensitiveMessageSanitizer = sanitizer
        this.defaultRoute = defaultRoute
    }

    @JvmStatic
    fun install(sink: LogSink?) {
        this.sink = sink
    }

    // ── Core log dispatch ─────────────────────────────────────────────────

    @JvmStatic
    fun log(
        priority: Int,
        route: String?,
        force: Boolean,
        sensitive: Boolean,
        message: String,
        vararg args: Any?,
    ) {
        log(priority, null, route, force, sensitive, message, *args)
    }

    /**
     * Log with an explicit per-call [tag]. When [tag] is null the globally
     * configured tag is used, so existing call sites keep working unchanged.
     */
    @JvmStatic
    fun log(
        priority: Int,
        tag: String?,
        route: String?,
        force: Boolean,
        sensitive: Boolean,
        message: String,
        vararg args: Any?,
    ) {
        if (priority < sLogLevel) return

        val effectiveTag = tag ?: logTag
        val formattedMessage = LogFormatter.formatArgs(message, args)
        val throwable = args.lastOrNull() as? Throwable
        val outputMessage = prepareForOutput(formattedMessage, sensitive)
        val outputThrowable = throwable?.stackTraceToString()?.let {
            prepareForOutput(it, sensitive)
        }

        val activeTestSink = testSink
        if (activeTestSink != null) {
            activeTestSink(priority, outputMessage)
            return
        }

        printLog(priority, effectiveTag, outputMessage)

        if (logToXposed) {
            printLog(priority, "LSPosed-Bridge", "$effectiveTag: $outputMessage")
        }

        sink?.append(
            LogEvent(
                level = LogLevel.fromPriority(priority),
                tag = effectiveTag,
                message = outputMessage,
                route = route ?: routeContext.get() ?: defaultRoute,
                force = force,
                sensitive = sensitive,
                throwableText = outputThrowable,
            ),
        )
    }

    private fun prepareForOutput(message: String, sensitive: Boolean): String {
        if (!sensitive) return message
        if (!LogSanitizerConfig.isEnabled()) {
            return DefaultLogSanitizer.sanitizeIfEnabled(message)
        }
        return runCatching { sensitiveMessageSanitizer.sanitize(message) }
            .getOrElse { DefaultLogSanitizer.sanitize(message) }
    }

    private fun printLog(priority: Int, tag: String, message: String) {
        runCatching {
            logPrinterForTest?.invoke(priority, tag, message)
                ?: Log.println(priority, tag, message)
        }
    }

    // ── Simple API (no route) ─────────────────────────────────────────────

    @JvmStatic
    fun v(message: String, vararg args: Any?) {
        log(Log.VERBOSE, null, false, true, message, *args)
    }

    @JvmStatic
    fun d(message: String, vararg args: Any?) {
        log(Log.DEBUG, null, false, true, message, *args)
    }

    @JvmStatic
    fun i(message: String, vararg args: Any?) {
        log(Log.INFO, null, false, true, message, *args)
    }

    @JvmStatic
    fun w(message: String, vararg args: Any?) {
        log(Log.WARN, null, true, true, message, *args)
    }

    @JvmStatic
    fun e(message: String, vararg args: Any?) {
        log(Log.ERROR, null, true, true, message, *args)
    }

    // ── Tag-scoped API (per-call tag) ─────────────────────────────────────

    @JvmStatic
    fun v(tag: String, message: String, vararg args: Any?) =
        log(Log.VERBOSE, tag, null, false, true, message, *args)

    @JvmStatic
    fun d(tag: String, message: String, vararg args: Any?) =
        log(Log.DEBUG, tag, null, false, true, message, *args)

    @JvmStatic
    fun i(tag: String, message: String, vararg args: Any?) =
        log(Log.INFO, tag, null, false, true, message, *args)

    @JvmStatic
    fun w(tag: String, message: String, vararg args: Any?) =
        log(Log.WARN, tag, null, true, true, message, *args)

    @JvmStatic
    fun e(tag: String, message: String, vararg args: Any?) =
        log(Log.ERROR, tag, null, true, true, message, *args)

    /**
     * Tag-scoped variants that also pin a route. Call these with the named
     * argument form (`d(tag = ..., route = ..., message = ...)`) so the
     * overload set stays unambiguous.
     */
    @JvmStatic
    fun v(tag: String, route: String?, message: String, vararg args: Any?) =
        log(Log.VERBOSE, tag, route, false, true, message, *args)

    @JvmStatic
    fun d(tag: String, route: String?, message: String, vararg args: Any?) =
        log(Log.DEBUG, tag, route, false, true, message, *args)

    @JvmStatic
    fun i(tag: String, route: String?, message: String, vararg args: Any?) =
        log(Log.INFO, tag, route, false, true, message, *args)

    @JvmStatic
    fun w(tag: String, route: String?, message: String, vararg args: Any?) =
        log(Log.WARN, tag, route, true, true, message, *args)

    @JvmStatic
    fun e(tag: String, route: String?, message: String, vararg args: Any?) =
        log(Log.ERROR, tag, route, true, true, message, *args)

    // ── Route context (ThreadLocal scoping) ───────────────────────────────

    @JvmStatic
    fun <T> withRoute(route: String?, block: () -> T): T {
        val previous = routeContext.get()
        routeContext.set(route)
        return try {
            block()
        } finally {
            if (previous == null) {
                routeContext.remove()
            } else {
                routeContext.set(previous)
            }
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────

    @JvmStatic
    fun setLogLevel(logLevel: Int) {
        sLogLevel = logLevel
    }

    @JvmStatic
    fun getLogLevel(): Int = sLogLevel

    @JvmStatic
    fun setTestSink(sink: ((Int, String) -> Unit)?) {
        testSink = sink
    }

    internal fun setLogPrinterForTest(printer: ((Int, String, String) -> Unit)?) {
        logPrinterForTest = printer
    }

    /**
     * Restore all mutable state to defaults. Intended for tests, so that the
     * shared singleton does not leak sink/tag/level/route context across cases.
     */
    @JvmStatic
    fun resetForTest() {
        sink = null
        sensitiveMessageSanitizer = DefaultLogSanitizer
        testSink = null
        logPrinterForTest = null
        logTag = "magisk-xposed-kit"
        sLogLevel = Log.INFO
        logToXposed = false
        defaultRoute = null
        routeContext.remove()
    }
}
