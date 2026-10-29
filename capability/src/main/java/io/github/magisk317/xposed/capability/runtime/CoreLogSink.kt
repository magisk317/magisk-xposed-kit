package io.github.magisk317.xposed.capability.runtime

interface CoreLogSink {
    fun append(
        priority: Int,
        tag: String,
        message: String,
        force: Boolean = false,
        route: String? = null,
        sensitive: Boolean = true,
        throwableText: String? = null,
    )
}

object CoreLogSinkHolder {
    @Volatile
    private var sink: CoreLogSink? = null

    fun install(logSink: CoreLogSink?) {
        sink = logSink
    }

    fun append(
        priority: Int,
        tag: String,
        message: String,
        force: Boolean = false,
        route: String? = null,
        sensitive: Boolean = true,
        throwableText: String? = null,
    ) {
        sink?.append(priority, tag, message, force, route, sensitive, throwableText)
    }
}
