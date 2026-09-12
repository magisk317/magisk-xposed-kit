package io.github.magisk317.xposed.logging

import android.content.ContentValues
import android.net.Uri
import android.os.Process
import java.util.UUID

/**
 * Represents a single log entry produced by an Xposed hook process,
 * intended to be relayed to the host application's log store.
 */
data class XposedLogEvent(
    val source: String,
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String = "",
    val route: String? = null,
    val force: Boolean = false,
    val sensitive: Boolean = false,
    val packageName: String = "",
    val processName: String = "",
    val pid: Int = Process.myPid(),
    val uid: Int = Process.myUid(),
    val sanitized: Boolean = false,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val eventId: String = UUID.randomUUID().toString(),
    val sequence: Long = 0L,
    /** Deterministic JSON object preserving structured attributes over IPC. */
    val attributesJson: String = "{}",
) {
    fun toContentValues(): ContentValues = ContentValues().apply {
        put(KEY_SOURCE, source)
        put(KEY_LEVEL, level)
        put(KEY_TAG, tag)
        put(KEY_MESSAGE, truncate(message, MAX_MESSAGE_BYTES))
        put(KEY_THROWABLE, truncate(throwable, MAX_THROWABLE_BYTES))
        route?.let { put(KEY_ROUTE, truncate(it, MAX_ROUTE_BYTES)) }
        put(KEY_FORCE, force)
        put(KEY_SENSITIVE, sensitive)
        put(KEY_SANITIZED, sanitized)
        put(KEY_PACKAGE_NAME, packageName)
        put(KEY_PROCESS_NAME, processName)
        put(KEY_PID, pid)
        put(KEY_UID, uid)
        put(KEY_SCHEMA_VERSION, schemaVersion)
        put(KEY_EVENT_ID, eventId)
        put(KEY_SEQUENCE, sequence)
        put(KEY_ATTRIBUTES, truncate(attributesJson, MAX_ATTRIBUTES_BYTES))
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val KEY_SOURCE = "source"
        const val KEY_LEVEL = "level"
        const val KEY_TAG = "tag"
        const val KEY_MESSAGE = "message"
        const val KEY_THROWABLE = "throwable"
        const val KEY_ROUTE = "route"
        const val KEY_FORCE = "force"
        const val KEY_SENSITIVE = "sensitive"
        const val KEY_SANITIZED = "sanitized"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_PROCESS_NAME = "process_name"
        const val KEY_PID = "pid"
        const val KEY_UID = "uid"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_SEQUENCE = "sequence"
        const val KEY_ATTRIBUTES = "attributes"

        /** Binder transaction limit is ~1MB; reserve headroom for ContentValues overhead. */
        private const val MAX_MESSAGE_BYTES = 512 * 1024
        private const val MAX_THROWABLE_BYTES = 256 * 1024
        private const val MAX_ROUTE_BYTES = 128
        private const val MAX_ATTRIBUTES_BYTES = 64 * 1024

        fun fromRecord(record: LogRecord, sequence: Long): XposedLogEvent = XposedLogEvent(
            source = record.runtimeContext.source,
            level = record.level.shortName,
            tag = record.tag,
            message = record.message,
            throwable = record.error?.stacktrace.orEmpty(),
            route = record.route,
            force = record.force,
            sensitive = record.sensitive,
            packageName = record.runtimeContext.source,
            processName = record.runtimeContext.processName.orEmpty(),
            pid = record.runtimeContext.pid ?: Process.myPid(),
            uid = record.runtimeContext.uid ?: Process.myUid(),
            sanitized = record.privacyMode == PrivacyMode.SANITIZED,
            schemaVersion = record.schemaVersion,
            eventId = record.eventId,
            sequence = sequence,
            attributesJson = JsonLineEncoder.encode(
                record.attributes.toSortedMap().map { (name, value) ->
                    JsonLineField.string(name, value)
                },
            ),
        )

        fun fromContentValues(values: ContentValues): XposedLogEvent = XposedLogEvent(
            source = values.getAsString(KEY_SOURCE).orEmpty(),
            level = values.getAsString(KEY_LEVEL) ?: "I",
            tag = values.getAsString(KEY_TAG).orEmpty(),
            message = values.getAsString(KEY_MESSAGE).orEmpty(),
            throwable = values.getAsString(KEY_THROWABLE).orEmpty(),
            route = values.getAsString(KEY_ROUTE),
            force = values.getAsBoolean(KEY_FORCE) ?: false,
            sensitive = values.getAsBoolean(KEY_SENSITIVE) ?: false,
            sanitized = values.getAsBoolean(KEY_SANITIZED) ?: false,
            packageName = values.getAsString(KEY_PACKAGE_NAME).orEmpty(),
            processName = values.getAsString(KEY_PROCESS_NAME).orEmpty(),
            pid = values.getAsInteger(KEY_PID) ?: Process.myPid(),
            uid = values.getAsInteger(KEY_UID) ?: Process.myUid(),
            schemaVersion = values.getAsInteger(KEY_SCHEMA_VERSION) ?: CURRENT_SCHEMA_VERSION,
            eventId = values.getAsString(KEY_EVENT_ID).takeUnless { it.isNullOrBlank() }
                ?: UUID.randomUUID().toString(),
            sequence = values.getAsLong(KEY_SEQUENCE) ?: 0L,
            attributesJson = values.getAsString(KEY_ATTRIBUTES) ?: "{}",
        )

        fun appendUri(authority: String): Uri =
            Uri.parse("content://$authority/entry")

        private fun truncate(value: String, maxBytes: Int): String {
            return StructuredLogSanitizer.truncateUtf8(value, maxBytes)
        }
    }
}
