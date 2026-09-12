package io.github.magisk317.xposed.logging

/** Stable JSONL schema for persisted v2 records. */
object LogRecordJsonLineEncoder {
    fun encode(record: LogRecord): String {
        val attributes = JsonLineEncoder.encode(
            record.attributes.toSortedMap().map { (name, value) ->
                JsonLineField.string(name, value)
            },
        )
        val error = record.error?.let { value ->
            JsonLineEncoder.encode(
                JsonLineField.string("type", value.type),
                JsonLineField.string("message", value.message.orEmpty(), value.message != null),
                JsonLineField.string("stacktrace", value.stacktrace),
                JsonLineField.string("cause", value.cause.orEmpty(), value.cause != null),
            )
        }
        return JsonLineEncoder.encode(
            JsonLineField.number("schema_version", record.schemaVersion),
            JsonLineField.string("event_id", record.eventId),
            JsonLineField.number("timestamp_ms", record.timestampMs),
            JsonLineField.string("level", record.level.shortName),
            JsonLineField.string("tag", record.tag),
            JsonLineField.string("route", record.route.orEmpty(), record.route != null),
            JsonLineField.string("message", record.message),
            JsonLineField.rawJson("attributes", attributes),
            JsonLineField.rawJson("error", error.orEmpty(), error != null),
            JsonLineField.rawJson("force", record.force.toString()),
            JsonLineField.rawJson("sensitive", record.sensitive.toString()),
            JsonLineField.string("privacy_mode", record.privacyMode.name),
            JsonLineField.string("source", record.runtimeContext.source),
            JsonLineField.string(
                "process_name",
                record.runtimeContext.processName.orEmpty(),
                record.runtimeContext.processName != null,
            ),
            JsonLineField.rawJson("pid", record.runtimeContext.pid?.toString().orEmpty(), record.runtimeContext.pid != null),
            JsonLineField.rawJson("uid", record.runtimeContext.uid?.toString().orEmpty(), record.runtimeContext.uid != null),
            JsonLineField.string(
                "thread_name",
                record.runtimeContext.threadName.orEmpty(),
                record.runtimeContext.threadName != null,
            ),
        )
    }
}
