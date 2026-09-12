package io.github.magisk317.xposed.logging

import java.util.LinkedHashMap

/** Per-provider ingress and daily quota configuration. */
data class LogProviderQuotaConfig(
    val maxEventsPerWindow: Int,
    val windowMs: Long,
    val maxBytesPerDay: Long,
    val maxEventsPerDay: Long,
    val maxTrackedUids: Int = 64,
) {
    init {
        require(maxEventsPerWindow > 0) { "maxEventsPerWindow must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(maxBytesPerDay > 0) { "maxBytesPerDay must be positive" }
        require(maxEventsPerDay > 0) { "maxEventsPerDay must be positive" }
        require(maxTrackedUids > 0) { "maxTrackedUids must be positive" }
    }
}

/** Shared provider policy; caller allowlists and route allowlists stay in consumers. */
class LogProviderQuotaPolicy(
    private val config: LogProviderQuotaConfig,
) {
    private data class Usage(
        val day: Long,
        var events: Long = 0,
        var bytes: Long = 0,
    )

    private val ingress = FixedWindowIngressLimiter(
        maxEvents = config.maxEventsPerWindow,
        windowMs = config.windowMs,
        maxTrackedUids = config.maxTrackedUids,
    )
    private val daily = LinkedHashMap<Int, Usage>(16, 0.75f, true)

    @Synchronized
    fun tryAcquire(uid: Int, payloadBytes: Long, nowMs: Long): Boolean {
        if (payloadBytes < 0L) return false
        if (!ingress.tryAcquire(uid, nowMs)) return false
        val day = Math.floorDiv(nowMs, MILLIS_PER_DAY)
        val usage = daily[uid]
        val current = if (usage == null || usage.day != day || nowMs < usage.day * MILLIS_PER_DAY) {
            if (usage == null && daily.size >= config.maxTrackedUids) {
                daily.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            Usage(day).also { daily[uid] = it }
        } else {
            usage
        }
        if (current.events >= config.maxEventsPerDay) return false
        if (payloadBytes > config.maxBytesPerDay - current.bytes) return false
        current.events += 1
        current.bytes += payloadBytes
        return true
    }

    @Synchronized
    fun trackedUidCount(): Int = daily.size

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
