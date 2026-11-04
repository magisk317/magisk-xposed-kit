package io.github.magisk317.xposed.permission

import android.content.Context

/**
 * App-specific wiring the startup permission flow needs to stay package-agnostic:
 * which notification listener / accessibility component to probe, as flattened
 * `package/Class` strings.
 */
data class StartupPermissionConfig(
    val notificationListenerComponent: String,
    val autoInputAccessibilityComponent: String?,
)

/**
 * One startup permission requirement, described independently of any screen.
 *
 * [key] is either a manifest permission name (runtime permission) or
 * `special:<name>` (settings-page permission). Copy/labels stay with the host;
 * the kit only carries keys and grant channels.
 */
data class StartupRequirement(
    val key: String,
    val rootGrantable: Boolean,
) {
    val isSpecial: Boolean get() = key.startsWith(SPECIAL_KEY_PREFIX)

    companion object {
        const val SPECIAL_KEY_PREFIX = "special:"
    }
}

/** Full requirement list entry with live grant state. */
data class StartupRequirementStatus(
    val requirement: StartupRequirement,
    val granted: Boolean,
)

/**
 * Root coverage for one requirement, as seen by the host's root transport.
 *
 * The tri-state comes from a real incident in the app family: a cached UNAVAILABLE
 * root state once made a fully-granted device report an outstanding requirement and
 * replay the wizard on every upgrade. UNAVAILABLE means "the transport cannot tell
 * yet" — it must NOT be treated as missing.
 */
enum class StartupRootCoverage {
    /** The root transport can deterministically cover this requirement. */
    COVERED,

    /** The root transport cannot cover this requirement. */
    NOT_COVERED,

    /** The transport has not answered yet; neither claim coverage nor declare a gap. */
    UNKNOWN,
}

/**
 * The root seam of the startup flow. xinyi implements it over [PermissionBridge]
 * command coverage (deterministic — never UNKNOWN); MiPush implements it over its
 * manager gateway snapshots (which may return UNKNOWN while the bridge is being read).
 */
interface StartupRootGrantChannel {
    fun coverage(context: Context, requirementKey: String): StartupRootCoverage

    fun grantAll(context: Context, config: StartupPermissionConfig, log: ((String) -> Unit)? = null): Boolean
}
