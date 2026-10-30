package io.github.magisk317.xposed.runtime.contract.notification

import android.content.Context

/**
 * Platform-specific notification operations.
 * Implementations handle channel creation, delivery diagnostics,
 * and permission checks on the host platform.
 */
interface NotificationPlatformBridge {
    /**
     * Creates or updates a notification channel.
     * Implementations should handle the case where the channel was previously deleted.
     */
    fun createNotificationChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int,
    )

    /**
     * Inspects whether notifications can be delivered for the given channel.
     * Returns diagnostics including permission state and channel importance.
     */
    fun inspectDelivery(context: Context, channelId: String): NotificationDeliveryDiagnostics

    /**
     * Checks whether the POST_NOTIFICATIONS permission is granted.
     */
    fun hasPostNotificationsPermission(context: Context): Boolean
}

/**
 * No-op implementation that reports all notifications as unavailable.
 * Useful as a default when no platform bridge is installed.
 */
object NoopNotificationPlatformBridge : NotificationPlatformBridge {
    override fun createNotificationChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int,
    ) = Unit

    override fun inspectDelivery(context: Context, channelId: String): NotificationDeliveryDiagnostics {
        return NotificationDeliveryDiagnostics(
            notificationsEnabled = false,
            postNotificationsGranted = false,
            channelImportance = null,
        )
    }

    override fun hasPostNotificationsPermission(context: Context): Boolean = false
}
