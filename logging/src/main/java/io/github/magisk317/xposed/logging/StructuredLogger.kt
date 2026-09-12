package io.github.magisk317.xposed.logging

import java.util.UUID

/**
 * Small structured logger facade for the v2 pipeline.
 *
 * A logger is immutable and cheap to derive with [withTag] or [withRoute].
 * It never infers a route from a caller class and it never uses ThreadLocal
 * state to carry a Throwable between calls.
 */
class StructuredLogger internal constructor(
    private val sink: StructuredLogSink,
    private val runtimeContext: LogRuntimeContext,
    private val tag: String,
    private val route: String?,
    private val minLevel: LogLevel,
    private val privacyMode: PrivacyMode,
    private val limiter: FixedWindowLogLimiter?,
    private val clock: LogClock,
    private val eventIdFactory: () -> String,
) {
    fun withTag(tag: String): StructuredLogger = copy(tag = tag)

    fun withRoute(route: String?): StructuredLogger = copy(route = route)

    fun log(
        level: LogLevel,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
        force: Boolean = level.isForceByDefault(),
        sensitive: Boolean = true,
        limitKey: String? = null,
    ): Boolean {
        if (!force && level.priority < minLevel.priority) return false
        if (limitKey != null && limiter?.tryAcquire(limitKey, clock.monotonicTimeMs())?.allowed == false) {
            return false
        }

        val record = LogRecord(
            eventId = eventIdFactory(),
            timestampMs = clock.wallTimeMs(),
            level = level,
            tag = tag,
            route = route,
            message = message,
            attributes = attributes.toMap(),
            error = cause?.let(LogError::from),
            force = force,
            sensitive = sensitive,
            privacyMode = privacyMode,
            runtimeContext = runtimeContext,
        )
        return runCatching { sink.append(record) }.isSuccess
    }

    fun verbose(message: String, attributes: Map<String, String> = emptyMap()): Boolean =
        log(LogLevel.VERBOSE, message, attributes)

    fun debug(message: String, attributes: Map<String, String> = emptyMap()): Boolean =
        log(LogLevel.DEBUG, message, attributes)

    fun debugLimited(
        key: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ): Boolean = log(LogLevel.DEBUG, message, attributes, limitKey = key)

    fun info(message: String, attributes: Map<String, String> = emptyMap()): Boolean =
        log(LogLevel.INFO, message, attributes)

    fun warn(
        message: String,
        attributes: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ): Boolean = log(LogLevel.WARN, message, attributes, cause)

    fun error(
        message: String,
        attributes: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ): Boolean = log(LogLevel.ERROR, message, attributes, cause)

    private fun copy(
        tag: String = this.tag,
        route: String? = this.route,
    ): StructuredLogger = StructuredLogger(
        sink = sink,
        runtimeContext = runtimeContext,
        tag = tag,
        route = route,
        minLevel = minLevel,
        privacyMode = privacyMode,
        limiter = limiter,
        clock = clock,
        eventIdFactory = eventIdFactory,
    )

    companion object {
        fun create(
            source: String,
            tag: String,
            sink: StructuredLogSink,
            route: String? = null,
            minLevel: LogLevel = LogLevel.VERBOSE,
            privacyMode: PrivacyMode = PrivacyMode.SANITIZED,
            limiter: FixedWindowLogLimiter? = null,
            clock: LogClock = SystemLogClock,
            runtimeContext: LogRuntimeContext = LogRuntimeContext(source = source),
            eventIdFactory: () -> String = { UUID.randomUUID().toString() },
        ): StructuredLogger = StructuredLogger(
            sink = sink,
            runtimeContext = runtimeContext,
            tag = tag,
            route = route,
            minLevel = minLevel,
            privacyMode = privacyMode,
            limiter = limiter,
            clock = clock,
            eventIdFactory = eventIdFactory,
        )

        private fun LogLevel.isForceByDefault(): Boolean = when (this) {
            LogLevel.WARN, LogLevel.ERROR, LogLevel.ASSERT -> true
            else -> false
        }
    }
}

typealias LogScope = StructuredLogger
