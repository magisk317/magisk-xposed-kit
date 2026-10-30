package io.github.magisk317.xposed.runtime.contract.notification

import android.app.NotificationManager

/**
 * Diagnostics for notification delivery capability.
 * Used by [NotificationPlatformBridge.inspectDelivery] to report whether
 * notifications can be posted on the current device/process.
 */
data class NotificationDeliveryDiagnostics(
    val notificationsEnabled: Boolean,
    val postNotificationsGranted: Boolean,
    val channelImportance: Int?,
) {
    /**
     * Whether a notification posted into the inspected channel can actually reach the user.
     *
     * A `null` [channelImportance] means the channel does not exist in the posting package's
     * namespace. Android 8+ drops `notify()` calls for unknown channels without throwing, so a
     * missing channel has to be reported as "cannot post" instead of being read as "no
     * restriction" - the previous `!= IMPORTANCE_NONE` comparison evaluated to `true` for `null`
     * and hid exactly that failure.
     */
    val canPost: Boolean
        get() = notificationsEnabled &&
            postNotificationsGranted &&
            channelImportance != null &&
            channelImportance != NotificationManager.IMPORTANCE_NONE

    fun summary(): String {
        return "enabled=$notificationsEnabled permission=$postNotificationsGranted channel=${importanceLabel(channelImportance)}"
    }

    companion object {
        fun importanceLabel(importance: Int?): String {
            return when (importance) {
                null -> "missing"
                NotificationManager.IMPORTANCE_NONE -> "none"
                NotificationManager.IMPORTANCE_MIN -> "min"
                NotificationManager.IMPORTANCE_LOW -> "low"
                NotificationManager.IMPORTANCE_DEFAULT -> "default"
                NotificationManager.IMPORTANCE_HIGH -> "high"
                NotificationManager.IMPORTANCE_MAX -> "max"
                else -> importance.toString()
            }
        }
    }
}
