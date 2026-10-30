package io.github.magisk317.xposed.contract.dedup

/**
 * Bounded timestamp window shared by in-memory and file-backed deduplication adapters.
 *
 * Timestamps later than [claim] time are discarded so a wall-clock rollback cannot keep a key
 * blocked until the old clock value is reached again.
 */
class BoundedTimestampMap<K>(
    private val windowMs: Long,
    private val maxEntries: Int,
    initialEntries: Map<K, Long> = emptyMap(),
) {
    data class Claim<K>(
        val claimed: Boolean,
        val blockedKey: K? = null,
        val ageMs: Long? = null,
    )

    private val timestamps = LinkedHashMap<K, Long>()

    var isDirty: Boolean = false
        private set

    init {
        require(windowMs > 0L) { "windowMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
        timestamps.putAll(initialEntries)
    }

    fun claim(
        key: K,
        now: Long,
        refreshOnBlocked: Boolean = false,
    ): Claim<K> {
        normalize(now)
        val last = timestamps[key]
        if (last != null) {
            val ageMs = now - last
            if (refreshOnBlocked) {
                timestamps.remove(key)
                timestamps[key] = now
                isDirty = true
            }
            return Claim(claimed = false, blockedKey = key, ageMs = ageMs)
        }

        timestamps[key] = now
        isDirty = true
        trimToCapacity()
        return Claim(claimed = true)
    }

    fun claimAll(keys: Collection<K>, now: Long): Claim<K> {
        normalize(now)
        val uniqueKeys = keys.distinct()
        uniqueKeys.forEach { key ->
            val last = timestamps[key]
            if (last != null) {
                return Claim(claimed = false, blockedKey = key, ageMs = now - last)
            }
        }

        uniqueKeys.forEach { key -> timestamps[key] = now }
        if (uniqueKeys.isNotEmpty()) {
            isDirty = true
            trimToCapacity()
        }
        return Claim(claimed = true)
    }

    /** Adds [key] only when it has no active timestamp. */
    fun ensure(key: K, now: Long): Boolean {
        normalize(now)
        if (timestamps.containsKey(key)) return false
        timestamps[key] = now
        isDirty = true
        trimToCapacity()
        return true
    }

    fun clear() {
        if (timestamps.isNotEmpty()) {
            timestamps.clear()
            isDirty = true
        }
    }

    fun snapshot(): LinkedHashMap<K, Long> = LinkedHashMap(timestamps)

    private fun normalize(now: Long) {
        prune(now)
        trimToCapacity()
    }

    private fun prune(now: Long) {
        val iterator = timestamps.entries.iterator()
        while (iterator.hasNext()) {
            val timestamp = iterator.next().value
            if (timestamp > now || now - timestamp > windowMs) {
                iterator.remove()
                isDirty = true
            }
        }
    }

    private fun trimToCapacity(): Boolean {
        var changed = false
        while (timestamps.size > maxEntries) {
            val eldestKey = timestamps.entries.firstOrNull()?.key ?: break
            timestamps.remove(eldestKey)
            changed = true
        }
        if (changed) isDirty = true
        return changed
    }
}
