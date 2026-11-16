package io.github.magisk317.xposed.runtime.state

sealed interface FrameworkRead {
    /** The framework answered the running-target query with [runningTargetCount] entries. */
    data class Answered(val runningTargetCount: Int) : FrameworkRead

    /** The framework is not bound, threw, or does not support the query. */
    data object Unavailable : FrameworkRead
}

object XposedActivation {
    /** Length of the window in which a heartbeat still proves that the hook is running. */
    const val LIVENESS_WINDOW_MS: Long = 60_000L

    /**
     * Whether the Xposed hook counts as running. A framework report of running targets, or a heartbeat
     * from this boot that is still inside the liveness window, is enough on its own.
     */
    fun isActive(
        frameworkRead: FrameworkRead,
        lastHeartbeatAtMs: Long,
        nowMs: Long,
        bootStartMs: Long,
    ): Boolean {
        val heartbeatAlive = isHeartbeatAlive(lastHeartbeatAtMs, nowMs, bootStartMs)
        return when (frameworkRead) {
            is FrameworkRead.Answered -> frameworkRead.runningTargetCount > 0 || heartbeatAlive
            FrameworkRead.Unavailable -> heartbeatAlive
        }
    }

    internal fun isHeartbeatAlive(heartbeatAtMs: Long, nowMs: Long, bootStartMs: Long): Boolean =
        heartbeatAtMs > 0L &&
            heartbeatAtMs >= bootStartMs &&
            heartbeatAtMs <= nowMs &&
            nowMs - heartbeatAtMs <= LIVENESS_WINDOW_MS
}
