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
    fun `keeps the short text when it is not truncated`() {
        val body = NotificationContentResolver.resolvePreferredBody(
            text = "您的验证码是123456",
            expandedText = "您的验证码是123456，请在 5 分钟内输入。",
            tickerText = "ticker",
        )

        assertEquals("您的验证码是123456", body)
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
