package io.github.magisk317.xposed.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationContentResolverTest {

    @Test
    fun `prefers expanded text when the short text is truncated`() {
        val body = NotificationContentResolver.resolvePreferredBody(
            text = "您的验证码是123…",
            expandedText = "您的验证码是123456，请在 5 分钟内输入。",
            tickerText = "ticker",
        )

        assertEquals("您的验证码是123456，请在 5 分钟内输入。", body)
    }

    @Test
    fun `prefers the complete expanded body even when the preview has no ellipsis`() {
        val body = NotificationContentResolver.resolvePreferredBody(
            text = "您的验证码是123456",
            expandedText = "您的验证码是123456，请在 5 分钟内输入。",
            tickerText = "ticker",
        )

        assertEquals("您的验证码是123456，请在 5 分钟内输入。", body)
    }

    @Test
    fun `keeps the preview when the expanded view adds no useful text`() {
        val preview = "您的验证码是123456"
        assertEquals(preview, NotificationContentResolver.resolvePreferredBody(preview, "", "ticker"))
        assertEquals(preview, NotificationContentResolver.resolvePreferredBody(preview, "备用文本", "ticker"))
        assertEquals(preview, NotificationContentResolver.resolvePreferredBody(preview, preview, "ticker"))
    }

    @Test
    fun `prefers a containing expansion even when it only adds one character`() {
        assertEquals(
            "code 123456!",
            NotificationContentResolver.resolvePreferredBody("code 123456", "code 123456!", ""),
        )
    }

    @Test
    fun `uses the eight character threshold for a different expanded view`() {
        val preview = "preview"
        val belowThreshold = "a".repeat(preview.length + 7)
        val atThreshold = "a".repeat(preview.length + 8)

        assertEquals(
            preview,
            NotificationContentResolver.resolvePreferredBody(preview, belowThreshold, ""),
        )
        assertEquals(
            atThreshold,
            NotificationContentResolver.resolvePreferredBody(preview, atThreshold, ""),
        )
    }

    @Test
    fun `does not replace an ellipsized preview with a shorter unrelated view`() {
        assertEquals(
            "您的验证码是123…",
            NotificationContentResolver.resolvePreferredBody("您的验证码是123…", "摘要", "ticker"),
        )
    }

    @Test
    fun `falls back through text expanded and ticker`() {
        assertEquals("expanded", NotificationContentResolver.resolvePreferredBody("", "expanded", "ticker"))
        assertEquals("ticker", NotificationContentResolver.resolvePreferredBody("", "", "ticker"))
        assertEquals("", NotificationContentResolver.resolvePreferredBody("", "", ""))
    }

    @Test
    fun `combined content joins distinct non-blank views`() {
        val content = NotificationContentResolver.combinedContent(
            title = "Title",
            body = "Body",
            tickerText = "Title",
            expandedText = "Expanded",
        )

        assertEquals("Title\nBody\nExpanded", content)
    }
}
