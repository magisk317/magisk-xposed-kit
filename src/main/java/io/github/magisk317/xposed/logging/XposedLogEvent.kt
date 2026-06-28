package io.github.magisk317.xposed.logging

import android.content.ContentValues
import android.net.Uri
import android.os.Process

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
) {
    fun toContentValues(): ContentValues = ContentValues().apply {
        put(KEY_SOURCE, source)
        put(KEY_LEVEL, level)
        put(KEY_TAG, tag)
        put(KEY_MESSAGE, truncate(message, MAX_MESSAGE_BYTES))
        put(KEY_THROWABLE, truncate(throwable, MAX_THROWABLE_BYTES))
        route?.let { put(KEY_ROUTE, it) }
        put(KEY_FORCE, force)
        put(KEY_SENSITIVE, sensitive)
        put(KEY_PACKAGE_NAME, packageName)
        put(KEY_PROCESS_NAME, processName)
        put(KEY_PID, pid)
        put(KEY_UID, uid)
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_LEVEL = "level"
        const val KEY_TAG = "tag"
        const val KEY_MESSAGE = "message"
        const val KEY_THROWABLE = "throwable"
        const val KEY_ROUTE = "route"
        const val KEY_FORCE = "force"
        const val KEY_SENSITIVE = "sensitive"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_PROCESS_NAME = "process_name"
        const val KEY_PID = "pid"
        const val KEY_UID = "uid"

        /**
         * Binder transaction limit is ~1MB. Reserve headroom for ContentValues overhead
         * and other fields; cap message at 512KB, throwable at 256KB.
         */
        private const val MAX_MESSAGE_BYTES = 512 * 1024
        private const val MAX_THROWABLE_BYTES = 256 * 1024

        fun fromContentValues(values: ContentValues): XposedLogEvent = XposedLogEvent(
            source = values.getAsString(KEY_SOURCE).orEmpty(),
            level = values.getAsString(KEY_LEVEL) ?: "I",
            tag = values.getAsString(KEY_TAG).orEmpty(),
            message = values.getAsString(KEY_MESSAGE).orEmpty(),
            throwable = values.getAsString(KEY_THROWABLE).orEmpty(),
            route = values.getAsString(KEY_ROUTE),
            force = values.getAsBoolean(KEY_FORCE) ?: false,
            sensitive = values.getAsBoolean(KEY_SENSITIVE) ?: false,
            packageName = values.getAsString(KEY_PACKAGE_NAME).orEmpty(),
            processName = values.getAsString(KEY_PROCESS_NAME).orEmpty(),
            pid = values.getAsInteger(KEY_PID) ?: Process.myPid(),
            uid = values.getAsInteger(KEY_UID) ?: Process.myUid(),
        )

        fun appendUri(authority: String): Uri =
            Uri.parse("content://$authority/entry")

        private fun truncate(value: String, maxBytes: Int): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return value
            val truncated = bytes.copyOf(maxBytes)
            val result = String(truncated, Charsets.UTF_8)
            // Only strip the last character if it's the Unicode replacement character (U+FFFD),
            // which indicates an incomplete multi-byte UTF-8 sequence was cut.
            return if (result.isNotEmpty() && result.last() == '�') {
                result.dropLast(1)
            } else {
                result
            }
        }
    }
}
