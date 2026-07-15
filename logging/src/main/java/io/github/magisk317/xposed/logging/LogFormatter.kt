package io.github.magisk317.xposed.logging

import java.util.IllegalFormatException

object LogFormatter {
    @JvmStatic
    fun format(message: String, vararg args: Any?): String {
        return formatArgs(message, args)
    }

    @JvmStatic
    fun formatArgs(message: String, args: Array<out Any?>): String {
        val throwable = args.lastOrNull() as? Throwable
        val messageArgs = if (throwable == null) args.toList() else args.dropLast(1)
        val formattedMessage = if (messageArgs.isEmpty()) {
            message
        } else {
            safeFormat(message, messageArgs)
        }
        return if (throwable == null) {
            formattedMessage
        } else {
            formattedMessage + '\n' + stackTraceToString(throwable)
        }
    }

    @JvmStatic
    fun stackTraceToString(throwable: Throwable): String {
        return throwable.stackTraceToString()
    }

    private fun safeFormat(message: String, args: List<Any?>): String {
        return try {
            String.format(message, *args.toTypedArray())
        } catch (_: IllegalFormatException) {
            message
        } catch (_: RuntimeException) {
            message
        }
    }
}
