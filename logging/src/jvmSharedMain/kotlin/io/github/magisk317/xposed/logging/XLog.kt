package io.github.magisk317.xposed.logging

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
    private var sLogLevel: Int = LogLevel.INFO.priority

    @Volatile
    private var logToXposed: Boolean = false

    @Volatile
    private var defaultRoute: String? = null

    @Volatile
    private var testSink: ((Int, String) -> Unit)? = null

    @Volatile
    private var logPrinterForTest: ((Int, String, String) -> Unit)? = null

    /**
     * Whether the platform print seam (logcat on Android, stdout/stderr on JVM)
     * is active. A host that renders everything through its sink (the desktop
     * JSONL capture) turns this off so lines are not written twice.
     */
    @Volatile
    private var printEnabled: Boolean = true

    /**
     * Configure the logger. Call once during module initialization.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        tag: String,
        logLevel: Int = LogLevel.INFO.priority,
        logToXposed: Boolean = false,
        sink: LogSink? = null,
        sanitizer: LogSanitizer = DefaultLogSanitizer,
        defaultRoute: String? = null,
        printEnabled: Boolean = true,
    ) {
        this.logTag = tag
        this.sLogLevel = logLevel
        this.logToXposed = logToXposed
        this.sink = sink
        this.sensitiveMessageSanitizer = sanitizer
        this.defaultRoute = defaultRoute
        this.printEnabled = printEnabled
    }

    @JvmStatic
    fun install(sink: LogSink?) {
        this.sink = sink
    }

    /** Sink-only mode helper: see [printEnabled]. */
    @JvmStatic
    fun setPrintEnabled(enabled: Boolean) {
        printEnabled = enabled
    }

    /** A [TaggedLog] bound to [tag]: `XLog.tagged("Push").i { "…" }`. */
    @JvmStatic
    fun tagged(tag: String): TaggedLog = TaggedLog(tag)

    /**
     * Structured entry carrying field metadata: the message plus a flat
     * string map travel on one [LogEvent] for sinks that render fields
     * (JSONL captures, telemetry). Named arguments only — the vararg
     * formatting overloads below do not compose with a metadata map.
     */
    @JvmStatic
    @JvmOverloads
    fun structured(
        level: LogLevel,
        message: String,
        throwable: Throwable? = null,
        tag: String? = null,
        route: String? = null,
        metadata: Map<String, String> = emptyMap(),
        sensitive: Boolean = true,
        force: Boolean = level >= LogLevel.WARN,
    ) {
        dispatch(
            priority = level.priority,
            tag = tag,
            route = route,
            force = force,
            sensitive = sensitive,
            message = message,
            args = if (throwable == null) emptyArray() else arrayOf(throwable),
            metadata = metadata,
        )
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
        dispatch(priority, tag, route, force, sensitive, message, args, emptyMap())
    }

    /**
     * Metadata-carrying variant of [log]: [metadata] rides the emitted
     * [LogEvent] for sinks that render fields. The map sits before [message]
     * so positional calls can never be confused with the String-first overload.
     */
    @JvmStatic
    fun log(
        priority: Int,
        tag: String?,
        route: String?,
        force: Boolean,
        sensitive: Boolean,
        metadata: Map<String, String>,
        message: String,
        vararg args: Any?,
    ) {
        dispatch(priority, tag, route, force, sensitive, message, args, metadata)
    }

    private fun dispatch(
        priority: Int,
        tag: String?,
        route: String?,
        force: Boolean,
        sensitive: Boolean,
        message: String,
        args: Array<out Any?>,
        metadata: Map<String, String>,
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
                metadata = metadata,
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
                ?: if (printEnabled) {
                    printPlatformLine(priority, tag, message)
                } else {
                    // Sink-only mode: the host renders everything itself.
                }
        }.onFailure {
            // The printer is the last resort: there is no sink left that could receive a
            // report about this print failing, and throwing back into the caller would turn
            // a logging hiccup into a hook failure.
        }
    }

    // ── Simple API (no route) ─────────────────────────────────────────────

    @JvmStatic
    fun v(message: String, vararg args: Any?) {
        log(LogLevel.VERBOSE.priority, null, false, true, message, *args)
    }

    @JvmStatic
    fun d(message: String, vararg args: Any?) {
        log(LogLevel.DEBUG.priority, null, false, true, message, *args)
    }

    @JvmStatic
    fun i(message: String, vararg args: Any?) {
        log(LogLevel.INFO.priority, null, false, true, message, *args)
    }

    @JvmStatic
    fun w(message: String, vararg args: Any?) {
        log(LogLevel.WARN.priority, null, true, true, message, *args)
    }

    @JvmStatic
    fun e(message: String, vararg args: Any?) {
        log(LogLevel.ERROR.priority, null, true, true, message, *args)
    }

    // ── Tag-scoped API (per-call tag) ─────────────────────────────────────

    @JvmStatic
    fun v(tag: String, message: String, vararg args: Any?) =
        log(LogLevel.VERBOSE.priority, tag, null, false, true, message, *args)

    @JvmStatic
    fun d(tag: String, message: String, vararg args: Any?) =
        log(LogLevel.DEBUG.priority, tag, null, false, true, message, *args)

    @JvmStatic
    fun i(tag: String, message: String, vararg args: Any?) =
        log(LogLevel.INFO.priority, tag, null, false, true, message, *args)

    @JvmStatic
    fun w(tag: String, message: String, vararg args: Any?) =
        log(LogLevel.WARN.priority, tag, null, true, true, message, *args)

    @JvmStatic
    fun e(tag: String, message: String, vararg args: Any?) =
        log(LogLevel.ERROR.priority, tag, null, true, true, message, *args)

    /**
     * Tag-scoped variants that also pin a route. Call these with the named
     * argument form (`d(tag = ..., route = ..., message = ...)`) so the
     * overload set stays unambiguous.
     */
    @JvmStatic
    fun v(tag: String, route: String?, message: String, vararg args: Any?) =
        log(LogLevel.VERBOSE.priority, tag, route, false, true, message, *args)

    @JvmStatic
    fun d(tag: String, route: String?, message: String, vararg args: Any?) =
        log(LogLevel.DEBUG.priority, tag, route, false, true, message, *args)

    @JvmStatic
    fun i(tag: String, route: String?, message: String, vararg args: Any?) =
        log(LogLevel.INFO.priority, tag, route, false, true, message, *args)

    @JvmStatic
    fun w(tag: String, route: String?, message: String, vararg args: Any?) =
        log(LogLevel.WARN.priority, tag, route, true, true, message, *args)

    @JvmStatic
    fun e(tag: String, route: String?, message: String, vararg args: Any?) =
        log(LogLevel.ERROR.priority, tag, route, true, true, message, *args)

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
        sLogLevel = LogLevel.INFO.priority
        logToXposed = false
        defaultRoute = null
        printEnabled = true
        routeContext.remove()
    }
}
