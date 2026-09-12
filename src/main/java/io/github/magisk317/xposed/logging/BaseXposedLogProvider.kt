package io.github.magisk317.xposed.logging

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import java.util.Locale

/**
 * Base ContentProvider for receiving Xposed log events from hook processes.
 *
 * Host applications extend this class and implement [appendLog] to write
 * received events into their own log store. The provider sanitizes all
 * incoming fields and validates caller identity via [isCallerAllowed]. Hosts
 * that hook additional processes must override that method with an explicit
 * UID/package policy.
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

    protected open val ingressPolicy: LogProviderQuotaPolicy? = null

    protected open fun isRouteAllowed(route: String?): Boolean = true

    protected open fun sanitizeSegment(value: String?): String {
        return value
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(MAX_SEGMENT_LENGTH)
            ?: "module"
    }

    protected abstract fun appendLog(event: XposedLogEvent)

    protected open fun isCallerAllowed(context: Context): Boolean {
        return CallerGuard.isCallerAllowed(context)
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.authority != authority || uri.lastPathSegment != "entry") return null
        val ctx = context?.applicationContext ?: return null
        if (!isCallerAllowed(ctx)) return null
        val raw = values ?: return null
        val callingUid = Binder.getCallingUid()
        val payloadBytes = raw.valueSet().sumOf { (_, value) ->
            value?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        }
        if (ingressPolicy?.tryAcquire(callingUid, payloadBytes, System.currentTimeMillis()) == false) {
            return null
        }
        val alreadySanitized = raw.getAsBoolean(XposedLogEvent.KEY_SANITIZED) == true
        val normalizedRoute = raw.getAsString(XposedLogEvent.KEY_ROUTE)?.let {
            XposedLogIngressPolicy.truncateUtf8(it, XposedLogIngressPolicy.MAX_ROUTE_BYTES)
        }
        if (!isRouteAllowed(normalizedRoute)) return null
        val event = XposedLogEvent(
            source = sanitizeSegment(raw.getAsString(XposedLogEvent.KEY_SOURCE)),
            level = XposedLogIngressPolicy.normalizeLevel(raw.getAsString(XposedLogEvent.KEY_LEVEL)),
            tag = XposedLogIngressPolicy.truncateUtf8(
                raw.getAsString(XposedLogEvent.KEY_TAG).orEmpty(),
                XposedLogIngressPolicy.MAX_TAG_BYTES,
            ),
            message = XposedLogIngressPolicy.truncateUtf8(
                if (alreadySanitized) {
                    raw.getAsString(XposedLogEvent.KEY_MESSAGE).orEmpty()
                } else {
                    DefaultLogSanitizer.sanitizeIfEnabled(
                        raw.getAsString(XposedLogEvent.KEY_MESSAGE).orEmpty(),
                    )
                },
                XposedLogIngressPolicy.MAX_MESSAGE_BYTES,
            ),
            throwable = XposedLogIngressPolicy.truncateUtf8(
                if (alreadySanitized) {
                    raw.getAsString(XposedLogEvent.KEY_THROWABLE).orEmpty()
                } else {
                    DefaultLogSanitizer.sanitizeIfEnabled(
                        raw.getAsString(XposedLogEvent.KEY_THROWABLE).orEmpty(),
                    )
                },
                XposedLogIngressPolicy.MAX_THROWABLE_BYTES,
            ),
            route = normalizedRoute,
            force = raw.getAsBoolean(XposedLogEvent.KEY_FORCE) ?: false,
            sensitive = raw.getAsBoolean(XposedLogEvent.KEY_SENSITIVE) ?: false,
            sanitized = true,
            packageName = XposedLogIngressPolicy.truncateUtf8(
                raw.getAsString(XposedLogEvent.KEY_PACKAGE_NAME).orEmpty(),
                XposedLogIngressPolicy.MAX_IDENTITY_BYTES,
            ),
            processName = XposedLogIngressPolicy.truncateUtf8(
                raw.getAsString(XposedLogEvent.KEY_PROCESS_NAME).orEmpty(),
                XposedLogIngressPolicy.MAX_IDENTITY_BYTES,
            ),
            pid = Binder.getCallingPid(),
            uid = callingUid,
            schemaVersion = raw.getAsInteger(XposedLogEvent.KEY_SCHEMA_VERSION)
                ?: XposedLogEvent.CURRENT_SCHEMA_VERSION,
            eventId = raw.getAsString(XposedLogEvent.KEY_EVENT_ID)
                .takeUnless { it.isNullOrBlank() }
                ?: java.util.UUID.randomUUID().toString(),
            sequence = raw.getAsLong(XposedLogEvent.KEY_SEQUENCE) ?: 0L,
            attributesJson = raw.getAsString(XposedLogEvent.KEY_ATTRIBUTES) ?: "{}",
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

internal object XposedLogIngressPolicy {
    const val MAX_TAG_BYTES = 128
    const val MAX_ROUTE_BYTES = 128
    const val MAX_IDENTITY_BYTES = 256
    const val MAX_MESSAGE_BYTES = 64 * 1024
    const val MAX_THROWABLE_BYTES = 128 * 1024

    private val allowedLevels = setOf("V", "D", "I", "W", "E")

    fun normalizeLevel(value: String?): String {
        return value?.uppercase(Locale.ROOT)?.takeIf(allowedLevels::contains) ?: "I"
    }

    fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val encodedBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + encodedBytes > maxBytes) break
            result.appendCodePoint(codePoint)
            bytes += encodedBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
