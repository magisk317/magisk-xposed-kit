package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedWindowIngressLimiterTest {
    @Test
    fun `rejects excess per uid and resets at the window boundary`() {
        val limiter = FixedWindowIngressLimiter(maxEvents = 2, windowMs = 1_000L)

        assertTrue(limiter.tryAcquire(uid = 1001, now = 10_000L))
        assertTrue(limiter.tryAcquire(uid = 1001, now = 10_100L))
        assertFalse(limiter.tryAcquire(uid = 1001, now = 10_200L))
        assertTrue(limiter.tryAcquire(uid = 1002, now = 10_200L))
        assertTrue(limiter.tryAcquire(uid = 1001, now = 11_000L))
    }

    @Test
    fun `clock rollback starts a new window`() {
        val limiter = FixedWindowIngressLimiter(maxEvents = 1, windowMs = 1_000L)

        assertTrue(limiter.tryAcquire(uid = 1001, now = 10_000L))
        assertFalse(limiter.tryAcquire(uid = 1001, now = 10_100L))
        assertTrue(limiter.tryAcquire(uid = 1001, now = 9_000L))
    }

    @Test
    fun `tracked uid count remains bounded`() {
        val limiter = FixedWindowIngressLimiter(
            maxEvents = 1,
            windowMs = 10_000L,
            maxTrackedUids = 2,
        )

        assertTrue(limiter.tryAcquire(uid = 1001, now = 1_000L))
        assertTrue(limiter.tryAcquire(uid = 1002, now = 1_001L))
        assertTrue(limiter.tryAcquire(uid = 1003, now = 1_002L))
        assertTrue(limiter.tryAcquire(uid = 1001, now = 1_003L))
        assertFalse(limiter.tryAcquire(uid = 1003, now = 1_004L))
    }

    @Test
    fun `rejects invalid limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowIngressLimiter(maxEvents = 0, windowMs = 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowIngressLimiter(maxEvents = 1, windowMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedWindowIngressLimiter(maxEvents = 1, windowMs = 1_000L, maxTrackedUids = 0)
        }
    }
}
