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
 * XLog.d("loading package: %s", packageName)
 * XLog.withRoute("sms_hook") { XLog.i("initialized") }
 * ```
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
    ) {
        this.logTag = tag
        this.sLogLevel = logLevel
        this.logToXposed = logToXposed
        this.sink = sink
        this.sensitiveMessageSanitizer = sanitizer
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
        if (priority < sLogLevel) return

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

        printLog(priority, logTag, outputMessage)

        if (logToXposed) {
            printLog(priority, "LSPosed-Bridge", "$logTag: $outputMessage")
        }

        sink?.append(
            LogEvent(
                level = LogLevel.fromPriority(priority),
                tag = logTag,
                message = outputMessage,
                route = route ?: routeContext.get(),
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
        routeContext.remove()
    }
}
