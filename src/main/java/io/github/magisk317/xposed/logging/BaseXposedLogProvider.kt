package io.github.magisk317.xposed.logging

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Base ContentProvider for receiving Xposed log events from hook processes.
 *
 * Host applications extend this class and implement [appendLog] to write
 * received events into their own log store. The provider sanitizes all
 * incoming string fields and validates caller identity via [CallerGuard].
 *
 * Declare in AndroidManifest.xml:
 * ```xml
 * <provider
 *     android:name=".MyLogProvider"
 *     android:authorities="${applicationId}.xposed.log"
 *     android:exported="true" />
 * ```
 */
abstract class BaseXposedLogProvider : ContentProvider() {

    abstract val authority: String

    protected open fun sanitizeSegment(value: String?): String {
        return value
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(MAX_SEGMENT_LENGTH)
            ?: "module"
    }

    protected abstract fun appendLog(event: XposedLogEvent)

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.lastPathSegment != "entry") return null
        val ctx = context?.applicationContext ?: return null
        if (!CallerGuard.isCallerAllowed(ctx)) return null
        val raw = values ?: return null
        val event = XposedLogEvent(
            source = sanitizeSegment(raw.getAsString(XposedLogEvent.KEY_SOURCE)),
            level = raw.getAsString(XposedLogEvent.KEY_LEVEL)?.ifBlank { "I" } ?: "I",
            tag = raw.getAsString(XposedLogEvent.KEY_TAG).orEmpty(),
            message = raw.getAsString(XposedLogEvent.KEY_MESSAGE).orEmpty(),
            throwable = raw.getAsString(XposedLogEvent.KEY_THROWABLE).orEmpty(),
            route = raw.getAsString(XposedLogEvent.KEY_ROUTE),
            force = raw.getAsBoolean(XposedLogEvent.KEY_FORCE) ?: false,
            sensitive = raw.getAsBoolean(XposedLogEvent.KEY_SENSITIVE) ?: false,
            packageName = raw.getAsString(XposedLogEvent.KEY_PACKAGE_NAME).orEmpty(),
            processName = raw.getAsString(XposedLogEvent.KEY_PROCESS_NAME).orEmpty(),
            pid = raw.getAsInteger(XposedLogEvent.KEY_PID) ?: 0,
            uid = raw.getAsInteger(XposedLogEvent.KEY_UID) ?: 0,
        )
        appendLog(event)
        return XposedLogEvent.appendUri(authority)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        private const val MAX_SEGMENT_LENGTH = 64
    }
}
