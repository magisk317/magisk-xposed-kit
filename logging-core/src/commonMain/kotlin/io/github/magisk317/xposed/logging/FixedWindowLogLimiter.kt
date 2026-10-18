package io.github.magisk317.xposed.logging

import kotlin.collections.ArrayDeque
import kotlin.collections.HashMap

/** Result of a producer-side limiter decision. */
data class LogLimitDecision(
    val allowed: Boolean,
    val suppressedCount: Long,
)

/**
 * Bounded producer-side fixed-window limiter.
 *
 * The limiter uses the caller-provided monotonic time, treats a clock rollback
 * as a new window, and evicts the least-recently-used key when the bounded key
 * budget is reached. It deliberately emits no logs of its own.
 *
 * Platform-neutral port of the former JVM-only implementation: the LRU order is
 * tracked with an explicit [ArrayDeque] instead of an access-ordered
 * `LinkedHashMap`, and mutual exclusion uses [kotlin.synchronized], so the same
 * source compiles for every target this module publishes.
 */
class FixedWindowLogLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val maxTrackedKeys: Int = 64,
) {
    private data class Window(
        var startedAtMs: Long,
        var accepted: Int = 0,
        var suppressed: Long = 0,
    )

    private val lock = Any()
    private val windows = HashMap<String, Window>()
    private val lruOrder = ArrayDeque<String>()

    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive" }
    }

    fun tryAcquire(key: String, nowMs: Long): LogLimitDecision {
        require(key.isNotBlank()) { "key must not be blank" }

        return synchronized(lock) {
            var window = windows[key]
            if (window == null) {
                while (windows.size >= maxTrackedKeys && lruOrder.isNotEmpty()) {
                    val eldest = lruOrder.removeFirst()
                    windows.remove(eldest)
                }
                window = Window(nowMs)
                windows[key] = window
            }
            lruOrder.remove(key)
            lruOrder.addLast(key)

            if (nowMs < window.startedAtMs || nowMs - window.startedAtMs >= windowMs) {
                window.startedAtMs = nowMs
                window.accepted = 0
                window.suppressed = 0
            }

            if (window.accepted < maxEvents) {
                window.accepted += 1
                LogLimitDecision(allowed = true, suppressedCount = window.suppressed)
            } else {
                window.suppressed += 1
                LogLimitDecision(allowed = false, suppressedCount = window.suppressed)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            windows.clear()
            lruOrder.clear()
        }
    }

    fun trackedKeyCount(): Int = synchronized(lock) { windows.size }
}
