package io.github.magisk317.xposed.logging

/**
 * Per-UID fixed-window limiter for exported logging ingress surfaces.
 *
 * The tracked UID set is bounded so a stream of previously unseen callers cannot grow memory
 * without limit. When every tracked window is still active, the oldest window is evicted.
 */
class FixedWindowIngressLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val maxTrackedUids: Int = DEFAULT_MAX_TRACKED_UIDS,
) {
    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(maxTrackedUids > 0) { "maxTrackedUids must be positive" }
    }

    private data class Window(val startedAt: Long, val count: Int)

    private val windows = linkedMapOf<Int, Window>()

    @Synchronized
    fun tryAcquire(uid: Int, now: Long): Boolean {
        val current = windows[uid]
        if (current == null || now < current.startedAt || now - current.startedAt >= windowMs) {
            if (current == null) makeRoomForNewUid(now)
            windows[uid] = Window(startedAt = now, count = 1)
            return true
        }
        if (current.count >= maxEvents) return false
        windows[uid] = current.copy(count = current.count + 1)
        return true
    }

    private fun makeRoomForNewUid(now: Long) {
        windows.entries.removeAll { now < it.value.startedAt || now - it.value.startedAt >= windowMs }
        if (windows.size >= maxTrackedUids) {
            val oldestUid = windows.minByOrNull { it.value.startedAt }?.key
            if (oldestUid != null) windows.remove(oldestUid)
        }
    }

    private companion object {
        const val DEFAULT_MAX_TRACKED_UIDS = 64
    }
}
