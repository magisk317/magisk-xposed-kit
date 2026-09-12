package io.github.magisk317.xposed.logging

/**
 * A JSONL field with an explicit inclusion flag so callers can preserve
 * protocol-specific optional-field behavior without hand-written escaping.
 */
class JsonLineField private constructor(
    internal val name: String,
    internal val value: Value,
    internal val include: Boolean,
) {
    internal sealed interface Value {
        data class StringValue(val value: String) : Value
        data class NumberValue(val encoded: String) : Value
        data class RawJsonValue(val encoded: String) : Value
    }

    companion object {
        fun string(name: String, value: String, include: Boolean = true): JsonLineField =
            JsonLineField(name, Value.StringValue(value), include)

        fun rawJson(name: String, encodedJson: String, include: Boolean = true): JsonLineField {
            if (!include) {
                return JsonLineField(name, Value.RawJsonValue("null"), false)
            }
            require(encodedJson.isNotBlank()) { "Raw JSON value for '$name' must not be blank" }
            return JsonLineField(name, Value.RawJsonValue(encodedJson), true)
        }

        fun number(name: String, value: Number, include: Boolean = true): JsonLineField {
            val encoded = value.toString()
            require(JSON_NUMBER_PATTERN.matches(encoded)) {
                "Invalid JSON number for field '$name': $encoded"
            }
            return JsonLineField(name, Value.NumberValue(encoded), include)
        }

        private val JSON_NUMBER_PATTERN =
            Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
    }
}

/** Encodes ordered fields as one JSON object without a trailing newline. */
object JsonLineEncoder {
    fun encode(vararg fields: JsonLineField): String = encode(fields.asList())

    fun encode(fields: Iterable<JsonLineField>): String = buildString {
        append('{')
        var first = true
        fields.forEach { field ->
            if (!field.include) return@forEach
            if (!first) append(',')
            first = false
            appendJsonString(field.name)
            append(':')
            when (val value = field.value) {
                is JsonLineField.Value.StringValue -> appendJsonString(value.value)
                is JsonLineField.Value.NumberValue -> append(value.encoded)
                is JsonLineField.Value.RawJsonValue -> append(value.encoded)
            }
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }
}
