package io.github.magisk317.xposed.contract.dedup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentEventDeduplicatorTest {

    @Test
    fun `build prefers explicit event id`() {
        val key = SmsForwardDedupKeyFactory.build(
            SmsForwardDedupSpec(
                eventId = " evt-123 ",
                sender = "10086",
                body = "ignored",
            ),
        )

        assertEquals("event:evt-123", key)
    }

    @Test
    fun `should drop duplicate only inside window`() {
        val deduplicator = RecentEventDeduplicator(windowMs = 1_000L)

        assertFalse(deduplicator.shouldDrop("k", now = 1_000L))
        assertTrue(deduplicator.shouldDrop("k", now = 1_500L))
        assertFalse(deduplicator.shouldDrop("k", now = 2_600L))
    }

    @Test
    fun `clock rollback starts a new window`() {
        val deduplicator = RecentEventDeduplicator(windowMs = 1_000L)

        assertFalse(deduplicator.shouldDrop("k", now = 10_000L))
        assertFalse(deduplicator.shouldDrop("k", now = 9_000L))
        assertTrue(deduplicator.shouldDrop("k", now = 9_500L))
    }
}
