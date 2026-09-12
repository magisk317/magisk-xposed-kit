package io.github.magisk317.xposed.logging

import java.util.LinkedHashMap

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

    private val windows = LinkedHashMap<String, Window>(16, 0.75f, true)

    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive" }
    }

    @Synchronized
    fun tryAcquire(key: String, nowMs: Long): LogLimitDecision {
        require(key.isNotBlank()) { "key must not be blank" }

        val window = windows[key] ?: Window(nowMs).also {
            if (windows.size >= maxTrackedKeys) {
                val eldest = windows.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            windows[key] = it
        }

        if (nowMs < window.startedAtMs || nowMs - window.startedAtMs >= windowMs) {
            window.startedAtMs = nowMs
            window.accepted = 0
            window.suppressed = 0
        }

        return if (window.accepted < maxEvents) {
            window.accepted += 1
            LogLimitDecision(allowed = true, suppressedCount = window.suppressed)
        } else {
            window.suppressed += 1
            LogLimitDecision(allowed = false, suppressedCount = window.suppressed)
        }
    }

    @Synchronized
    fun reset() {
        windows.clear()
    }

    @Synchronized
    fun trackedKeyCount(): Int = windows.size
}
