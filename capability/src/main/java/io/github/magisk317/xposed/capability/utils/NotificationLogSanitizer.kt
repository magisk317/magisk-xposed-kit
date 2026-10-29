package io.github.magisk317.xposed.capability.utils

object NotificationLogSanitizer {
    @JvmStatic
    fun formatTitle(value: String?, sensitiveDebugLog: Boolean): String {
        return formatField(label = "title", value = value, sensitiveDebugLog = sensitiveDebugLog)
    }

    @JvmStatic
    fun formatBody(value: String?, sensitiveDebugLog: Boolean): String {
        return formatField(label = "body", value = value, sensitiveDebugLog = sensitiveDebugLog)
    }

    private fun formatField(label: String, value: String?, sensitiveDebugLog: Boolean): String {
        return if (sensitiveDebugLog) escape(value) else summarize(label, value)
    }

    private fun summarize(label: String, value: String?): String {
        val text = value.orEmpty()
        if (text.isBlank()) return "$label[len=0 hash=none]"
        return "$label[len=${text.length} hash=${shortHash(text)}]"
    }

    private fun shortHash(value: String): String = Integer.toHexString(value.hashCode())

    private fun escape(value: String?): String {
        val text = value.orEmpty()
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u000C' -> sb.append("\\f")
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                else -> {
                    if (c.code < 32 || c.code >= 127) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
