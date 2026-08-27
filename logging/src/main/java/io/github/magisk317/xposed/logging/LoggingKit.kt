package io.github.magisk317.xposed.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * Custom [LogWriter] that pipes Kermit log events through [LogSanitizer]
 * and the optional [LogSink] architecture.
 */
open class KermitLogSinkWriter(
    private val sanitizer: LogSanitizer = DefaultLogSanitizer,
    private val logToXposed: Boolean = false,
    private val sink: LogSink? = null,
) : LogWriter() {

    override fun isLoggable(tag: String, severity: Severity): Boolean = true

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val sanitizedMessage = sanitizeForOutput(message)
        val sanitizedThrowable = throwable
            ?.stackTraceToString()
            ?.let(::sanitizeForOutput)
        val fullMessage = buildString {
            append(sanitizedMessage)
            if (!sanitizedThrowable.isNullOrBlank()) {
                append('\n')
                append(sanitizedThrowable)
            }
        }

        // Print to Android Log / Console safely without throwing on pure JVM
        printPlatformLog(severity, tag, fullMessage)

        if (logToXposed) {
            printPlatformLog(severity, "LSPosed-Bridge", "$tag: $fullMessage")
        }

        val mappedLevel = when (severity) {
            Severity.Verbose -> LogLevel.VERBOSE
            Severity.Debug -> LogLevel.DEBUG
            Severity.Info -> LogLevel.INFO
            Severity.Warn -> LogLevel.WARN
            Severity.Error, Severity.Assert -> LogLevel.ERROR
        }

        sink?.append(
            LogEvent(
                level = mappedLevel,
                tag = tag,
                message = sanitizedMessage,
                // Never expose the original Throwable to downstream sinks. Consumers that need
                // the rendered exception must use the already-sanitized throwableText field.
                throwable = null,
                throwableText = sanitizedThrowable,
                force = severity >= Severity.Warn,
                sensitive = true,
            ),
        )
    }

    private fun sanitizeForOutput(value: String): String {
        if (!LogSanitizerConfig.isEnabled()) {
            return DefaultLogSanitizer.sanitizeIfEnabled(value)
        }
        return runCatching { sanitizer.sanitize(value) }
            .getOrElse { DefaultLogSanitizer.sanitize(value) }
    }

    private fun printPlatformLog(severity: Severity, tag: String, message: String) {
        runCatching {
            val priority = when (severity) {
                Severity.Verbose -> 2
                Severity.Debug -> 3
                Severity.Info -> 4
                Severity.Warn -> 5
                Severity.Error -> 6
                Severity.Assert -> 7
            }
            android.util.Log.println(priority, tag, message)
        }.onFailure {
            // Fallback for Pure JVM environments / Unit Tests
            if (severity >= Severity.Warn) {
                System.err.println("[$tag] $message")
            } else {
                println("[$tag] $message")
            }
        }
    }
}

/**
 * Global entry point for configuring Kermit in the magisk/xposed kit ecosystem.
 */
object LoggingKit {
    @JvmStatic
    @JvmOverloads
    fun init(
        defaultTag: String = "MiPush",
        minSeverity: Severity = Severity.Info,
        logToXposed: Boolean = false,
        sanitizer: LogSanitizer = DefaultLogSanitizer,
        sink: LogSink? = null,
    ) {
        val writer = KermitLogSinkWriter(
            sanitizer = sanitizer,
            logToXposed = logToXposed,
            sink = sink,
        )
        Logger.setLogWriters(listOf(writer))
        Logger.setMinSeverity(minSeverity)
        Logger.setTag(defaultTag)
    }
}
