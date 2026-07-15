package io.github.magisk317.xposed.logging

data class LogEvent(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val route: String? = null,
    val force: Boolean = false,
    val sensitive: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val priority: Int
        get() = level.priority
}
