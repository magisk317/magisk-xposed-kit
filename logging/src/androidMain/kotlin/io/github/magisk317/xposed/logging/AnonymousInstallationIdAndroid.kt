package io.github.magisk317.xposed.logging

import android.content.Context
import java.util.UUID

/**
 * Android persistence for the anonymous installation identity.
 *
 * The identity format and the activity-window arithmetic are platform-neutral and stay in
 * the shared source set; only SharedPreferences binding lives here, because that is the part
 * a desktop or server-side consumer must not inherit.
 */
@JvmOverloads
fun AnonymousInstallationId.getOrCreate(
    context: Context,
    preferencesName: String = AnonymousInstallationId.DEFAULT_PREFERENCES_NAME,
): String {
    val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    canonicalUuid(preferences.getString(AnonymousInstallationId.PREFERENCE_KEY, null))?.let { return it }

    val generated = UUID.randomUUID().toString()
    preferences.edit().putString(AnonymousInstallationId.PREFERENCE_KEY, generated).commit()
    return canonicalUuid(preferences.getString(AnonymousInstallationId.PREFERENCE_KEY, generated)) ?: generated
}

@JvmOverloads
fun AnonymousInstallationId.read(
    context: Context,
    preferencesName: String = AnonymousInstallationId.DEFAULT_PREFERENCES_NAME,
): String? {
    val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    return canonicalUuid(preferences.getString(AnonymousInstallationId.PREFERENCE_KEY, null))
}

@JvmOverloads
fun AnonymousInstallationId.createState(
    context: Context,
    preferencesName: String = AnonymousInstallationId.DEFAULT_PREFERENCES_NAME,
): InstallationTelemetryState = InstallationTelemetryState(
    installationId = getOrCreate(context, preferencesName),
    tracker = SharedPreferencesInstallationActivityTracker(context.applicationContext, preferencesName),
)

internal class SharedPreferencesInstallationActivityTracker constructor(
    context: Context,
    preferencesName: String,
) : InstallationActivityTracker {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val claims = ActiveWindowClaims(
        readBucket = { key -> preferences.getString(key, null) },
        writeBuckets = { windows ->
            val editor = preferences.edit()
            windows.forEach { editor.putString(it.preferenceKey, it.bucket) }
            editor.commit()
        },
    )

    override fun claimWindows(nowMillis: Long): List<ActiveWindow> = claims.claim(nowMillis)
}
