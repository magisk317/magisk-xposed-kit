package io.github.magisk317.xposed.runtime.state

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XposedActivationPropertyTest {
    // Feature: dual-runtime-mode-fix, Property 2
    @Test
    fun heartbeatCountsOnlyInsideTheBootScopedWindow() {
        samples().forEach { (heartbeat, now, boot) ->
            assertEquals(
                expectedAlive(heartbeat, now, boot),
                XposedActivation.isHeartbeatAlive(heartbeat, now, boot),
                "heartbeat=$heartbeat now=$now boot=$boot",
            )
        }
    }

    // Feature: dual-runtime-mode-fix, Property 3
    @Test
    fun frameworkAnswerActivatesWhenTargetsExistOrHeartbeatIsAlive() {
        samples().forEach { (heartbeat, now, boot) ->
            listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE).forEach { count ->
                assertEquals(
                    count > 0 || expectedAlive(heartbeat, now, boot),
                    XposedActivation.isActive(FrameworkRead.Answered(count), heartbeat, now, boot),
                    "count=$count heartbeat=$heartbeat now=$now boot=$boot",
                )
            }
        }
    }

    // Feature: dual-runtime-mode-fix, Property 4
    @Test
    fun unavailableFrameworkUsesOnlyTheHeartbeat() {
        samples().forEach { (heartbeat, now, boot) ->
            assertEquals(
                expectedAlive(heartbeat, now, boot),
                XposedActivation.isActive(FrameworkRead.Unavailable, heartbeat, now, boot),
                "heartbeat=$heartbeat now=$now boot=$boot",
            )
        }
    }

    private fun samples(): List<Triple<Long, Long, Long>> {
        val random = Random(20261110)
        return buildList {
            repeat(128) {
                val boot = random.nextLong(1L, 2_000_000_000_000L)
                val now = boot + random.nextLong(120_001L, 10_000_000L)
                // Explicit boundaries are included for every generated boot/current pair.
                listOf(
                    boot, boot - 1L, now - WINDOW_MS, now - WINDOW_MS - 1L,
                    now, now + 1L, 0L, -1L,
                    random.nextLong(boot, now + 1L),
                ).forEach { heartbeat -> add(Triple(heartbeat, now, boot)) }
                add(Triple(boot, boot, boot))
                add(Triple(boot, boot + WINDOW_MS, boot))
            }
        }
    }

    // Pin the chosen window independently so changing the production constant needs a contract decision.
    private fun expectedAlive(heartbeat: Long, now: Long, boot: Long): Boolean =
        heartbeat > 0L && heartbeat in boot..now && heartbeat >= now - WINDOW_MS

    companion object {
        private const val WINDOW_MS = 60_000L
    }
}
