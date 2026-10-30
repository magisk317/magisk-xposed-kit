package io.github.magisk317.xposed.contract.dedup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedTimestampMapTest {
    @Test
    fun `future timestamps do not remain blocked after clock rollback`() {
        val timestamps = BoundedTimestampMap(
            windowMs = 1_000L,
            maxEntries = 4,
            initialEntries = mapOf("event" to 10_000L),
        )

        val result = timestamps.claim("event", now = 9_000L)

        assertTrue(result.claimed)
        assertEquals(9_000L, timestamps.snapshot()["event"])
    }

    @Test
    fun `window boundary is inclusive and expired entries can be claimed`() {
        val timestamps = BoundedTimestampMap<String>(windowMs = 1_000L, maxEntries = 4)

        assertTrue(timestamps.claim("event", now = 1_000L).claimed)
        assertFalse(timestamps.claim("event", now = 2_000L).claimed)
        assertTrue(timestamps.claim("event", now = 2_001L).claimed)
    }

    @Test
    fun `capacity evicts oldest timestamp`() {
        val timestamps = BoundedTimestampMap<String>(windowMs = 10_000L, maxEntries = 2)

        timestamps.claim("first", now = 1_000L)
        timestamps.claim("second", now = 1_001L)
        timestamps.claim("third", now = 1_002L)

        assertEquals(listOf("second", "third"), timestamps.snapshot().keys.toList())
        assertTrue(timestamps.claim("first", now = 1_003L).claimed)
    }

    @Test
    fun `restored entries are expired before capacity is enforced`() {
        val timestamps = BoundedTimestampMap(
            windowMs = 1_000L,
            maxEntries = 1,
            initialEntries = linkedMapOf(
                "active" to 9_500L,
                "expired" to 1_000L,
            ),
        )

        val result = timestamps.claim("active", now = 10_000L)

        assertFalse(result.claimed)
        assertEquals(listOf("active"), timestamps.snapshot().keys.toList())
    }

    @Test
    fun `blocked claims can refresh a rolling window`() {
        val timestamps = BoundedTimestampMap<String>(windowMs = 1_000L, maxEntries = 2)

        assertTrue(timestamps.claim("event", now = 1_000L).claimed)
        assertFalse(timestamps.claim("event", now = 1_500L, refreshOnBlocked = true).claimed)

        val refreshed = timestamps.claim("event", now = 2_200L)
        assertFalse(refreshed.claimed)
        assertEquals(700L, refreshed.ageMs)
    }

    @Test
    fun `invalid limits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedTimestampMap<String>(windowMs = 0L, maxEntries = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedTimestampMap<String>(windowMs = 1L, maxEntries = 0)
        }
    }
}
