package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedWindowLogLimiterTest {
    @Test
    fun `window resets after expiry and counts suppressions`() {
        val limiter = FixedWindowLogLimiter(maxEvents = 2, windowMs = 1_000L, maxTrackedKeys = 4)

        assertTrue(limiter.tryAcquire("k", nowMs = 0).allowed)
        assertTrue(limiter.tryAcquire("k", nowMs = 100).allowed)

        val blocked = limiter.tryAcquire("k", nowMs = 200)
        assertFalse(blocked.allowed)
        assertEquals(1L, blocked.suppressedCount)

        // New window: the budget and the suppression counter both reset.
        assertTrue(limiter.tryAcquire("k", nowMs = 1_000).allowed)
        assertTrue(limiter.tryAcquire("k", nowMs = 1_100).allowed)
        val blockedAgain = limiter.tryAcquire("k", nowMs = 1_200)
        assertFalse(blockedAgain.allowed)
        assertEquals(1L, blockedAgain.suppressedCount)
    }

    @Test
    fun `clock rollback starts a new window`() {
        val limiter = FixedWindowLogLimiter(maxEvents = 1, windowMs = 1_000L)

        assertTrue(limiter.tryAcquire("k", nowMs = 5_000).allowed)
        assertFalse(limiter.tryAcquire("k", nowMs = 5_100).allowed)
        assertTrue(limiter.tryAcquire("k", nowMs = 4_000).allowed)
    }

    @Test
    fun `evicts least recently used key beyond the tracked budget`() {
        val limiter = FixedWindowLogLimiter(maxEvents = 1, windowMs = 60_000L, maxTrackedKeys = 2)

        assertTrue(limiter.tryAcquire("a", nowMs = 0).allowed)
        assertTrue(limiter.tryAcquire("b", nowMs = 1).allowed)
        // Refresh "a" (still throttled inside its window) so "b" becomes the LRU victim.
        assertFalse(limiter.tryAcquire("a", nowMs = 2).allowed)
        assertTrue(limiter.tryAcquire("c", nowMs = 3).allowed)
        assertEquals(2, limiter.trackedKeyCount())

        // "b" was evicted, so it gets a fresh window.
        assertTrue(limiter.tryAcquire("b", nowMs = 4).allowed)
        // Inserting "b" evicted the now-least-recently-used "a", so "a" restarts too.
        assertTrue(limiter.tryAcquire("a", nowMs = 5).allowed)
    }

    @Test
    fun `reset clears every window`() {
        val limiter = FixedWindowLogLimiter(maxEvents = 1, windowMs = 60_000L)
        assertTrue(limiter.tryAcquire("a", nowMs = 0).allowed)
        assertFalse(limiter.tryAcquire("a", nowMs = 1).allowed)
        assertEquals(1, limiter.trackedKeyCount())

        limiter.reset()
        assertEquals(0, limiter.trackedKeyCount())
        assertTrue(limiter.tryAcquire("a", nowMs = 2).allowed)
    }

    @Test
    fun `rejects invalid configuration and blank keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowLogLimiter(maxEvents = 0, windowMs = 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowLogLimiter(maxEvents = 1, windowMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowLogLimiter(maxEvents = 1, windowMs = 1_000L, maxTrackedKeys = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowLogLimiter(maxEvents = 1, windowMs = 1_000L).tryAcquire(" ", nowMs = 0)
        }
    }
}
