package io.github.magisk317.xposed.logging

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnonymousInstallationIdTest {
    @Test
    fun `canonicalUuid accepts UUID and rejects other identifiers`() {
        val value = "02C1F46D-581E-4F80-9747-9B9DCB99BC46"

        assertEquals(value.lowercase(), AnonymousInstallationId.canonicalUuid(value))
        assertNull(AnonymousInstallationId.canonicalUuid("android-id"))
        assertNull(AnonymousInstallationId.canonicalUuid(null))
    }

    @Test
    fun `activeWindows use UTC ISO buckets`() {
        val windows = activeWindows(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli())

        assertEquals(ActiveWindow("daily", "2026-01-01"), windows[0])
        assertEquals(ActiveWindow("weekly", "2026-W01"), windows[1])
        assertEquals(ActiveWindow("monthly", "2026-01"), windows[2])
        assertTrue(windows.all { it.preferenceKey.startsWith("active_bucket_") })
    }

    @Test
    fun `concurrent activity claims emit each window once`() {
        val state = ConcurrentHashMap<String, String>()
        val writes = AtomicInteger()
        val claims = ActiveWindowClaims(
            readBucket = state::get,
            writeBuckets = { windows ->
                windows.forEach { state[it.preferenceKey] = it.bucket }
                writes.incrementAndGet()
            },
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val instant = Instant.parse("2026-07-26T00:00:00Z").toEpochMilli()

        val futures = List(32) {
            executor.submit<List<ActiveWindow>> {
                start.await()
                claims.claim(instant)
            }
        }
        start.countDown()
        val emitted = futures.flatMap { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(3, emitted.size)
        assertEquals(1, writes.get())
        assertEquals(setOf("daily", "weekly", "monthly"), emitted.map { it.window }.toSet())
    }
}
