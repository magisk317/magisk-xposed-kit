package io.github.magisk317.xposed.logging

/**
 * Applies the shared privacy and UTF-8 boundary policy to a structured record
 * before it reaches logcat, a queue, a provider or persistent storage.
 */
class StructuredLogSanitizer(
    private val sanitizer: LogSanitizer = DefaultLogSanitizer,
    private val maxMessageBytes: Int = DEFAULT_MESSAGE_BYTES,
    private val maxAttributeBytes: Int = DEFAULT_ATTRIBUTE_BYTES,
    private val maxThrowableBytes: Int = DEFAULT_THROWABLE_BYTES,
) {
    init {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive" }
        require(maxAttributeBytes > 0) { "maxAttributeBytes must be positive" }
        require(maxThrowableBytes > 0) { "maxThrowableBytes must be positive" }
    }

    fun sanitize(record: LogRecord): LogRecord {
        val redact = record.sensitive || record.privacyMode == PrivacyMode.SANITIZED
        fun apply(value: String, maxBytes: Int): String {
            val sanitized = if (redact) {
                runCatching { sanitizer.sanitize(value) }
                    .getOrElse { DefaultLogSanitizer.sanitizeIfEnabled(value) }
            } else {
                DefaultLogSanitizer.sanitizeIfEnabled(value)
            }
            return truncateUtf8(sanitized, maxBytes)
        }

        val safeError = record.error?.let { error ->
            error.copy(
                message = error.message?.let { apply(it, maxThrowableBytes) },
                stacktrace = apply(error.stacktrace, maxThrowableBytes),
                cause = error.cause?.let { apply(it, maxThrowableBytes) },
            )
        }
        return record.copy(
            message = apply(record.message, maxMessageBytes),
            attributes = record.attributes.mapValues { (name, value) ->
                if (redact) {
                    val sanitized = runCatching { sanitizer.sanitize("$name=$value") }
                        .getOrElse { DefaultLogSanitizer.sanitizeIfEnabled("$name=$value") }
                    truncateUtf8(sanitized.substringAfter('=', sanitized), maxAttributeBytes)
                } else {
                    apply(value, maxAttributeBytes)
                }
            },
            error = safeError,
        )
    }

    companion object {
        const val DEFAULT_MESSAGE_BYTES = 64 * 1024
        const val DEFAULT_ATTRIBUTE_BYTES = 8 * 1024
        const val DEFAULT_THROWABLE_BYTES = 128 * 1024

        fun truncateUtf8(value: String, maxBytes: Int): String {
            require(maxBytes > 0) { "maxBytes must be positive" }
            if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
            val result = StringBuilder()
            var index = 0
            var bytes = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val encodedBytes = String(Character.toChars(codePoint))
                    .toByteArray(Charsets.UTF_8)
                    .size
                if (bytes + encodedBytes > maxBytes) break
                result.appendCodePoint(codePoint)
                bytes += encodedBytes
                index += Character.charCount(codePoint)
            }
            return result.toString()
        }
    }
}

fun truncateStructuredLogUtf8(value: String, maxBytes: Int): String =
    StructuredLogSanitizer.truncateUtf8(value, maxBytes)
