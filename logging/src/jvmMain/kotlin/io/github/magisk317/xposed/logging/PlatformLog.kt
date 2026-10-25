package io.github.magisk317.xposed.logging

// Desktop / server-side consumer: a line goes to stdout, warnings and errors to stderr.
// There is no logcat here, so this is the whole implementation rather than a fallback.
internal actual fun printPlatformLine(priority: Int, tag: String, message: String) {
    val line = "[$tag] $message"
    when (priority) {
        LogLevel.WARN.priority, LogLevel.ERROR.priority, LogLevel.ASSERT.priority -> System.err.println(line)
        else -> println(line)
    }
}
