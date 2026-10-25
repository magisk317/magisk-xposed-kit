package io.github.magisk317.xposed.logging

fun interface LogSink {
    fun append(event: LogEvent)
}
