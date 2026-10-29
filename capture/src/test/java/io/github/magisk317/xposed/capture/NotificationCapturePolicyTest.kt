package io.github.magisk317.xposed.capture

import android.app.Notification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NotificationCapturePolicyTest {

    private val policy = DefaultNotificationCapturePolicy()

    @Test
    fun `skips foreground service notifications unless the route is priority`() {
        val capture = capture(flags = Notification.FLAG_FOREGROUND_SERVICE)

        assertEquals("foreground_service", policy.skipReason(capture, isPriorityRoute = false))
        assertNull(policy.skipReason(capture, isPriorityRoute = true))
    }

    @Test
    fun `skips ongoing event notifications unless the route is priority`() {
        val capture = capture(flags = Notification.FLAG_ONGOING_EVENT)

        assertEquals("ongoing_event", policy.skipReason(capture, isPriorityRoute = false))
        assertNull(policy.skipReason(capture, isPriorityRoute = true))
    }

    @Test
    fun `skips service category regardless of priority`() {
        val capture = capture(category = Notification.CATEGORY_SERVICE)

        assertEquals("category_service", policy.skipReason(capture, isPriorityRoute = false))
        assertEquals("category_service", policy.skipReason(capture, isPriorityRoute = true))
    }

    @Test
    fun `skips known service channels unless the route is priority`() {
        val capture = capture(channelId = "com.example:fgs")

        assertEquals("channel_fgs", policy.skipReason(capture, isPriorityRoute = false))
        assertNull(policy.skipReason(capture, isPriorityRoute = true))
    }

    @Test
    fun `skips group summaries with a bypass hook`() {
        val capture = capture(flags = Notification.FLAG_GROUP_SUMMARY)

        assertEquals("group_summary", policy.skipReason(capture, isPriorityRoute = true))
        assertNull(
            object : DefaultNotificationCapturePolicy() {
                override fun isGroupSummaryBypassed(packageName: String): Boolean = true
            }.skipReason(capture, isPriorityRoute = true),
        )
    }

    @Test
    fun `passes a plain user notification through`() {
        val capture = capture()

        assertNull(policy.skipReason(capture, isPriorityRoute = false))
    }

    private fun capture(
        flags: Int = 0,
        category: String? = null,
        channelId: String = "main",
    ): CapturedNotification = CapturedNotification(
        packageName = "com.example.app",
        title = "Title",
        body = "Body",
        tickerText = "",
        expandedText = "",
        channelId = channelId,
        category = category,
        flags = flags,
    )
}
