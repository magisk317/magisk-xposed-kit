package io.github.magisk317.xposed.logging

import android.content.Context
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** Anonymous, per-install identity used only for telemetry aggregation. */
object AnonymousInstallationId {
    const val DEFAULT_PREFERENCES_NAME = "magisk_otel_identity"
    const val PREFERENCE_KEY = "anonymous_installation_id"

    @JvmStatic
    @JvmOverloads
    fun getOrCreate(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ): String {
        val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        canonicalUuid(preferences.getString(PREFERENCE_KEY, null))?.let { return it }

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(PREFERENCE_KEY, generated).commit()
        return canonicalUuid(preferences.getString(PREFERENCE_KEY, generated)) ?: generated
    }

    @JvmStatic
    @JvmOverloads
    fun read(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ): String? {
        val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        return canonicalUuid(preferences.getString(PREFERENCE_KEY, null))
    }

    @JvmStatic
    fun canonicalUuid(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
    }

    @JvmStatic
    @JvmOverloads
    fun createState(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ): InstallationTelemetryState {
        return InstallationTelemetryState(
            installationId = getOrCreate(context, preferencesName),
            tracker = InstallationActivityTracker(context.applicationContext, preferencesName),
        )
    }
}

data class InstallationTelemetryState(
    val installationId: String,
    val tracker: InstallationActivityTracker,
)

class InstallationActivityTracker internal constructor(
    context: Context,
    preferencesName: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val claims = ActiveWindowClaims(
        readBucket = { key -> preferences.getString(key, null) },
        writeBuckets = { windows ->
            val editor = preferences.edit()
            windows.forEach { editor.putString(it.preferenceKey, it.bucket) }
            editor.commit()
        },
    )

    internal fun claimWindows(nowMillis: Long): List<ActiveWindow> {
        return claims.claim(nowMillis)
    }
}

internal class ActiveWindowClaims(
    private val readBucket: (String) -> String?,
    private val writeBuckets: (List<ActiveWindow>) -> Unit,
) {
    @Synchronized
    fun claim(nowMillis: Long): List<ActiveWindow> {
        val windows = activeWindows(nowMillis)
        val claimed = windows.filter { readBucket(it.preferenceKey) != it.bucket }
        if (claimed.isNotEmpty()) {
            writeBuckets(claimed)
        }
        return claimed
    }
}

internal data class ActiveWindow(
    val window: String,
    val bucket: String,
) {
    val preferenceKey: String = "active_bucket_$window"
}

internal fun activeWindows(nowMillis: Long): List<ActiveWindow> {
    val date = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val weekFields = WeekFields.ISO
    val weekYear = date.get(weekFields.weekBasedYear())
    val week = date.get(weekFields.weekOfWeekBasedYear())
    return listOf(
        ActiveWindow("daily", date.toString()),
        ActiveWindow("weekly", String.format(Locale.ROOT, "%04d-W%02d", weekYear, week)),
        ActiveWindow("monthly", String.format(Locale.ROOT, "%04d-%02d", date.year, date.monthValue)),
    )
}
