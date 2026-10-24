package io.github.magisk317.xposed.logging

import java.util.Locale

fun interface LogSanitizer {
    fun sanitize(message: String): String
}

object NoopLogSanitizer : LogSanitizer {
    override fun sanitize(message: String): String = message
}

/**
 * Process-wide switch controlling whether [DefaultLogSanitizer] deep-sanitizes.
 *
 * Neutral to any host application. [MagiskLogPolicy.install] drives it from the build flags,
 * so the value cannot come from a stored preference: a released app must sanitize even if the
 * device still holds a verbose preference written by an older build.
 *
 * Default is `true` (sanitized), so a process that never installs a policy leaks nothing.
 */
object LogSanitizerConfig {
    @Volatile
    private var sanitizationEnabled: Boolean = true

    fun setEnabled(enabled: Boolean) {
        sanitizationEnabled = enabled
    }

    /**
     * Apply a host's user-facing redaction switch without letting it undercut the build:
     * a released app keeps redacting when the switch is off, while a debug build — the
     * capture channel — may run plaintext because that is what its developer switched on.
     */
    fun applyUserSwitch(userWantsSanitized: Boolean) {
        setEnabled(MagiskLogPolicy.current().sanitizesText || userWantsSanitized)
    }

    fun isEnabled(): Boolean = sanitizationEnabled
}

/** Redacts credentials without applying payload summaries or log length limits. */
object DefaultLogSanitizer : LogSanitizer {
    private const val SANITIZED_LOG_MAX_LENGTH = 800
    private const val HIGH_ENTROPY_MIN_LENGTH = 16
    private const val REDACT_EDGE_KEEP = 4

    private val messageFieldNames = listOf(
        "content",
        "msg",
        "message",
        "text",
        "body",
        "title",
        "org_content",
    )
    private val senderFieldNames = listOf("from", "sender", "phone", "mobile", "number")
    private val codeFieldNames = listOf("code", "smsCode", "otp", "verifyCode")

    override fun sanitize(message: String): String {
        return truncate(sanitizeUnbounded(message), SANITIZED_LOG_MAX_LENGTH)
    }

    /**
     * Sanitize when [LogSanitizerConfig] is enabled (the default, and the only mode a
     * distributed release runs with). A verbose CI or debug build skips redaction and only
     * applies the length cap, because raw content is the point of that capture channel.
     */
    fun sanitizeIfEnabled(message: String): String {
        return if (LogSanitizerConfig.isEnabled()) {
            sanitize(message)
        } else {
            truncate(message, SANITIZED_LOG_MAX_LENGTH)
        }
    }

    fun sanitizeUnbounded(message: String): String {
        var sanitized = SecretRedactor.redact(message)
        sanitized = summarizeFields(sanitized, messageFieldNames, ::summarizePayload)
        sanitized = summarizeFields(sanitized, senderFieldNames, ::summarizeSender)
        sanitized = summarizeFields(sanitized, codeFieldNames, ::summarizeCode)
        return maskLikelyPhoneNumbers(sanitized)
    }

    /**
     * Redact a single log argument that has no `key=` prefix (e.g. a bare token
     * or regId printed directly). High-entropy heuristic: a long run without
     * whitespace is treated as a secret and edge-masked. Intended for per-arg
     * use in method-trace logging where field-name matching cannot apply.
     */
    fun redactArg(value: Any?): String {
        if (value == null) return "null"
        val raw = value.toString()
        if (value !is CharSequence) return raw
        val lower = raw.lowercase(Locale.ROOT)
        if (lower == "true" || lower == "false" || lower == "null") return raw
        if (raw.length >= HIGH_ENTROPY_MIN_LENGTH && raw.none { it.isWhitespace() }) {
            return redactMiddle(raw)
        }
        return raw
    }

    private fun summarizeFields(
        message: String,
        fieldNames: List<String>,
        summarizer: (String) -> String,
    ): String {
        var text = message
        fieldNames.forEach { field ->
            val queryRegex = Regex("(?i)(\\b$field=)([^&\\s]+)")
            text = queryRegex.replace(text) { match ->
                match.groupValues[1] + summarizer(match.groupValues[2])
            }
            // 值匹配必须跳过转义序列（如 \"、\\），否则对已序列化的 JSON 行（如导出的
            // .jsonl）做替换时会在值内第一个 \" 处截断，产生非法 JSON 并泄漏剩余内容。
            val jsonRegex = Regex("(?i)(\"$field\"\\s*:\\s*\")((?:[^\"\\\\]|\\\\.)*)(\")")
            text = jsonRegex.replace(text) { match ->
                match.groupValues[1] + summarizer(match.groupValues[2]) + match.groupValues[3]
            }
        }
        return text
    }

    private fun maskLikelyPhoneNumbers(message: String): String {
        return Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""").replace(message) { match ->
            maskKeepEdges(match.value, 3, 2)
        }
    }

    private fun summarizeSender(value: String): String {
        if (value.isBlank()) return "sender[len=0 hash=none]"
        return "sender[len=${value.length} tail=${value.takeLast(minOf(2, value.length))} hash=${shortHash(value)}]"
    }

    private fun summarizeCode(value: String): String {
        if (value.isBlank()) return "code[len=0 hash=none]"
        return "code[len=${value.length} masked=${maskKeepEdges(value, 2, 1)} hash=${shortHash(value)}]"
    }

    private fun summarizePayload(value: String): String {
        if (value.isBlank()) return "payload[len=0 hash=none]"
        return "payload[len=${value.length} hash=${shortHash(value)}]"
    }

    private fun redactMiddle(value: String): String {
        if (value.length <= REDACT_EDGE_KEEP * 2) return "<redacted>"
        return value.take(REDACT_EDGE_KEEP) + "..." + value.takeLast(REDACT_EDGE_KEEP)
    }

    private fun maskKeepEdges(value: String, prefix: Int, suffix: Int): String {
        if (value.isBlank()) return "<empty>"
        val safePrefix = prefix.coerceAtLeast(0)
        val safeSuffix = suffix.coerceAtLeast(0)
        if (value.length <= safePrefix + safeSuffix) {
            return "*".repeat(value.length.coerceAtLeast(1))
        }
        return buildString(value.length) {
            append(value.take(safePrefix))
            append("*".repeat((value.length - safePrefix - safeSuffix).coerceAtLeast(1)))
            append(value.takeLast(safeSuffix))
        }
    }

    private fun shortHash(value: String): String {
        if (value.isBlank()) return "none"
        return Integer.toHexString(value.hashCode()).lowercase(Locale.ROOT)
    }

    private fun truncate(value: String, max: Int): String {
        if (value.length <= max) return value
        return value.take(max) + "...(len=${value.length})"
    }
}
