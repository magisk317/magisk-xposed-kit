package io.github.magisk317.xposed.logging

import android.content.Context

/**
 * Android-only wiring for the exporter: the installation identity it needs is stored in
 * SharedPreferences, and the analytics switch has to be mirrored into a file the hook
 * process can read through libxposed remote preferences.
 *
 * A JVM consumer configures [MagiskOtel.configure] with its own instance id instead --
 * there is no Android shape to reuse here, so none is faked.
 */
@JvmOverloads
fun MagiskOtel.configureForInstallation(
    context: Context,
    config: MagiskOtel.Config,
    preferencesName: String = AnonymousInstallationId.DEFAULT_PREFERENCES_NAME,
) {
    val state = AnonymousInstallationId.createState(context, preferencesName)
    configure(
        config.copy(
            serviceInstanceId = state.installationId,
            activityTracker = state.tracker,
        ),
    )
}

/**
 * Mirrors the effective analytics switch into the plain SharedPreferences file that also holds
 * the anonymous installation id. Hook processes cannot read DataStore, but they can read this
 * file through libxposed remote preferences, so the hook side can follow the user's switch.
 */
fun MagiskOtel.publishSwitch(context: Context, key: String, enabled: Boolean) {
    runCatching {
        context
            .getSharedPreferences(
                AnonymousInstallationId.DEFAULT_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }.onFailure {
        // Best-effort by necessity: the switch mirrors into the hook process, and reporting
        // this through XLog would route straight back into this exporter.
    }
}
