package io.github.magisk317.xposed.capture

import android.app.Notification

/** Pure text helpers for rebuilding readable content out of a notification. */
object NotificationContentResolver {

    fun resolveExpandedText(notification: Notification): String {
        val extras = notification.extras
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")
            .orEmpty()
        return listOf(bigText, lines, subText).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    fun resolvePreferredBody(
        text: String,
        expandedText: String,
        tickerText: String,
    ): String {
        val normalizedText = text.trim()
        val normalizedExpanded = expandedText.trim()
        val normalizedTicker = tickerText.trim()
        return when {
            shouldPreferExpandedText(normalizedText, normalizedExpanded) -> normalizedExpanded
            normalizedText.isNotEmpty() -> normalizedText
            normalizedExpanded.isNotEmpty() -> normalizedExpanded
            else -> normalizedTicker
        }
    }

    /** Joins the distinct, non-blank views of a notification into one parseable body. */
    fun combinedContent(
        title: String,
        body: String,
        tickerText: String,
        expandedText: String,
    ): String {
        return listOf(title, body, expandedText, tickerText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
    }

    private fun shouldPreferExpandedText(
        text: String,
        expandedText: String,
    ): Boolean {
        if (expandedText.isBlank()) return false
        if (text.isBlank()) return true
        if (looksTruncated(text) && expandedText.length >= text.length) return true
        val comparableText = stripEdgeEllipsis(text)
        if (comparableText.isNotEmpty() && expandedText.contains(comparableText) && expandedText.length > comparableText.length) {
            return true
        }
        return expandedText.length >= text.length + 8
    }

    private fun looksTruncated(text: String): Boolean {
        val normalized = text.trim()
        return normalized.startsWith("...") ||
            normalized.startsWith("…") ||
            normalized.endsWith("...") ||
            normalized.endsWith("…")
    }

    private fun stripEdgeEllipsis(text: String): String {
        return text.trim()
            .removePrefix("...")
            .removePrefix("…")
            .removeSuffix("...")
            .removeSuffix("…")
            .trim()
    }
}
