package io.github.magisk317.xposed.logging

/**
 * Redacts structured credentials — URL userinfo, bearer/basic auth headers, JSON
 * secret fields and `key=value` secrets — without applying payload summaries or
 * log length limits.
 *
 * Split out of the JVM-facing `LogSanitizer` file so platform-neutral consumers
 * (Kotlin Multiplatform modules) can share the same credential policy. The full
 * deep-sanitizing policy lives in `DefaultLogSanitizer` on the logging module.
 */
object SecretRedactor {
    private const val SECRET_FIELD_PATTERN =
        "access[_-]?token|token|secret|sign|authorization|password|passwd|pwd|proxy-authorization|" +
            "api[_-]?key|x[_-]?api[_-]?key|cookie|set-cookie|reg[_-]?id|access[_-]?id|app[_-]?id|" +
            "app[_-]?key|device[_-]?id|imei|oaid|udid|aid|account"

    private val urlUserInfo = Regex("(https?://)([^/@\\s:]+):([^@\\s]+)@")
    private val keyValue = Regex(
        "(?i)($SECRET_FIELD_PATTERN)(\\s*[:=]\\s*)([^&\\s,\\\"]+)",
    )
    private val jsonField = Regex(
        "(?i)(\\\"(?:$SECRET_FIELD_PATTERN)\\\"\\s*:\\s*\\\")((?:[^\\\"\\\\]|\\\\.)*)(\\\")",
    )
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]+")
    private val basic = Regex("(?i)(basic\\s+)[A-Za-z0-9+/=]+")

    fun redact(raw: String): String {
        var text = bearer.replace(raw) { match -> "${match.groupValues[1]}***" }
        text = basic.replace(text) { match -> "${match.groupValues[1]}***" }
        text = urlUserInfo.replace(text) { match ->
            "${match.groupValues[1]}***:***@"
        }
        text = jsonField.replace(text) { match ->
            "${match.groupValues[1]}***${match.groupValues[3]}"
        }
        return keyValue.replace(text) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}***"
        }
    }
}
