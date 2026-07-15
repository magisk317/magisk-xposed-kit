package io.github.magisk317.xposed.logging

import java.util.Locale

enum class LogLevel(
    val priority: Int,
    val shortName: String,
    val displayName: String,
) {
    VERBOSE(2, "V", "VERBOSE"),
    DEBUG(3, "D", "DEBUG"),
    INFO(4, "I", "INFO"),
    WARN(5, "W", "WARN"),
    ERROR(6, "E", "ERROR"),
    ASSERT(7, "A", "ASSERT"),
    ;

    companion object {
        fun fromPriority(priority: Int): LogLevel {
            return entries.firstOrNull { it.priority == priority } ?: INFO
        }

        fun fromName(value: String?): LogLevel? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { level ->
                normalized == level.shortName || normalized == level.displayName
            } ?: normalized.toIntOrNull()?.let(::fromPriority)
        }

        fun priorityName(priority: Int): String {
            return entries.firstOrNull { it.priority == priority }?.shortName ?: priority.toString()
        }
    }
}
