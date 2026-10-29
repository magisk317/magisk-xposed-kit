package io.github.magisk317.xposed.capture

import android.app.Notification

/**
 * Domain-neutral snapshot of a notification intercepted at the system level.
 *
 * The capture pipeline owns extraction only; deciding what a notification means
 * (call, verification code, plain forward) belongs to the consuming product.
 */
data class CapturedNotification(
    val packageName: String,
    val title: String,
    val body: String,
    val tickerText: String,
    val expandedText: String,
    val channelId: String,
    val category: String?,
    val flags: Int,
) {
    val isBlankContent: Boolean
        get() = title.isBlank() && body.isBlank()

    companion object {
        fun from(
            packageName: String,
            notification: Notification,
        ): CapturedNotification {
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val tickerText = notification.tickerText?.toString().orEmpty()
            val expandedText = NotificationContentResolver.resolveExpandedText(notification)
            val body = NotificationContentResolver.resolvePreferredBody(text, expandedText, tickerText)
            return CapturedNotification(
                packageName = packageName,
                title = title,
                body = body,
                tickerText = tickerText,
                expandedText = expandedText,
                channelId = notification.channelId.orEmpty(),
                category = notification.category,
                flags = notification.flags,
            )
        }
    }
}
