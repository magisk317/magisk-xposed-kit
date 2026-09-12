package io.github.magisk317.xposed.logging

import java.io.PrintWriter
import java.io.StringWriter

/** Privacy classification used by the v2 structured logging pipeline. */
enum class PrivacyMode {
    SANITIZED,
    SENSITIVE_DEBUG,
}

data class LogError(
    val type: String,
    val message: String?,
    val stacktrace: String,
    val cause: String? = null,
) {
    companion object {
        fun from(throwable: Throwable): LogError {
            val rendered = StringWriter().also { writer ->
                throwable.printStackTrace(PrintWriter(writer))
            }.toString()
            return LogError(
                type = throwable::class.java.name,
                message = throwable.message,
                stacktrace = rendered,
                cause = throwable.cause?.let { cause ->
                    "${cause::class.java.name}: ${cause.message.orEmpty()}"
                },
            )
        }
    }
}

data class LogRuntimeContext(
    val source: String,
    val processName: String? = null,
    val pid: Int? = null,
    val uid: Int? = null,
    val threadName: String? = null,
)

data class LogRecord(
    val eventId: String,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val route: String? = null,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
    val error: LogError? = null,
    val force: Boolean = false,
    val sensitive: Boolean = true,
    val privacyMode: PrivacyMode = PrivacyMode.SANITIZED,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val runtimeContext: LogRuntimeContext,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

fun interface StructuredLogSink {
    fun append(record: LogRecord)
}

interface LogClock {
    fun wallTimeMs(): Long
    fun monotonicTimeMs(): Long
}

object SystemLogClock : LogClock {
    private const val NANOS_PER_MILLI = 1_000_000L

    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun monotonicTimeMs(): Long = System.nanoTime() / NANOS_PER_MILLI
}
