package io.github.magisk317.xposed.capture

import android.app.Notification

import java.util.Locale

/**
 * Domain-neutral skip rules for captured notifications.
 *
 * [skipReason] receives whether the consumer classified the notification as a
 * priority route (for example an active call); priority routes bypass the
 * foreground-service and ongoing-event guards because those flags are normal
 * for them.
 */
interface NotificationCapturePolicy {

    fun skipReason(capture: CapturedNotification, isPriorityRoute: Boolean): String?

    /** Product hook: allow group summaries for packages that hide their real payload there. */
    fun isGroupSummaryBypassed(packageName: String): Boolean = false
}

open class DefaultNotificationCapturePolicy : NotificationCapturePolicy {

    override fun skipReason(capture: CapturedNotification, isPriorityRoute: Boolean): String? {
        val flags = capture.flags
        if (!isPriorityRoute) {
            if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return REASON_FOREGROUND_SERVICE
            if (flags and Notification.FLAG_ONGOING_EVENT != 0) return REASON_ONGOING_EVENT
            capture.fixedChannelReason()?.let { return it }
            capture.fixedContentReason()?.let { return it }
        }
        if (capture.category == Notification.CATEGORY_SERVICE) return REASON_CATEGORY_SERVICE
        val isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) {
            if (isGroupSummaryBypassed(capture.packageName)) return null
            return REASON_GROUP_SUMMARY
        }
        return null
    }

    private fun CapturedNotification.fixedChannelReason(): String? {
        val normalizedChannel = channelId.trim().lowercase(Locale.ROOT)
        if (normalizedChannel.isBlank()) return null
        if (normalizedChannel == "voicemail" || normalizedChannel == "voice_mail") return "channel_voicemail"
        if (normalizedChannel.contains("foreground_service") || normalizedChannel.contains("foregroundservice")) {
            return "channel_foreground_service"
        }
        if (normalizedChannel.contains("fgs")) return "channel_fgs"
        if (normalizedChannel.contains("low_importance_service")) return "channel_low_importance_service"
        if (normalizedChannel.contains("service_channel") || normalizedChannel.contains("servicechannel")) {
            return "channel_service"
        }
        if (normalizedChannel.contains(".hide") || normalizedChannel.endsWith("_hide") || normalizedChannel.contains("_hide_")) {
            return "channel_hidden"
        }
        if (normalizedChannel.contains("silent")) return "channel_silent"
        return null
    }

    private fun CapturedNotification.fixedContentReason(): String? {
        val normalizedText = buildString {
            append(title.trim())
            append('\n')
            append(body.trim())
        }.lowercase(Locale.ROOT)
        if (normalizedText.isBlank()) return null
        if (normalizedText.contains("正在运行")) return "content_running"
        if (normalizedText.contains("前台服务") || normalizedText.contains("foreground service")) {
            return "content_foreground_service"
        }
        if (normalizedText.contains("正在检查应用更新") || normalizedText.contains("checking app updates")) {
            return "content_checking_updates"
        }
        return null
    }

    companion object {
        const val REASON_FOREGROUND_SERVICE = "foreground_service"
        const val REASON_ONGOING_EVENT = "ongoing_event"
        const val REASON_CATEGORY_SERVICE = "category_service"
        const val REASON_GROUP_SUMMARY = "group_summary"
    }
}
