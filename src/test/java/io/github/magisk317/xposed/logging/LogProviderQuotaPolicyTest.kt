package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogProviderQuotaPolicyTest {
    @Test
    fun `enforces ingress and daily event and byte quotas`() {
        val policy = LogProviderQuotaPolicy(
            LogProviderQuotaConfig(
                maxEventsPerWindow = 2,
                windowMs = 1_000L,
                maxBytesPerDay = 10L,
                maxEventsPerDay = 3L,
                maxTrackedUids = 2,
            ),
        )

        assertTrue(policy.tryAcquire(1001, 4L, 10_000L))
        assertTrue(policy.tryAcquire(1001, 4L, 10_100L))
        assertFalse(policy.tryAcquire(1001, 1L, 10_200L))
        assertTrue(policy.tryAcquire(1001, 2L, 11_000L))
        assertFalse(policy.tryAcquire(1001, 1L, 11_100L))
        assertTrue(policy.tryAcquire(1002, 1L, 11_100L))
        assertEquals(2, policy.trackedUidCount())
    }

    @Test
    fun `clock rollback does not bypass daily usage quota`() {
        val policy = LogProviderQuotaPolicy(
            LogProviderQuotaConfig(
                maxEventsPerWindow = 10,
                windowMs = 1_000L,
                maxBytesPerDay = 4L,
                maxEventsPerDay = 1L,
            ),
        )

        assertTrue(policy.tryAcquire(1001, 4L, 10_000L))
        assertFalse(policy.tryAcquire(1001, 1L, 10_100L))
        assertFalse(policy.tryAcquire(1001, 4L, 9_000L))
    }
}
