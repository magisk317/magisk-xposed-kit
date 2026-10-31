package io.github.magisk317.xposed.contract.dedup

data class CapturedEventDedupSpec(
    val eventId: String = "",
    val sender: String? = null,
    val body: String? = null,
    val timestamp: Long = 0L,
    val msgType: String = "sms",
    val source: String = "sms_hook",
    val simSlot: Int? = null,
    val subId: Int? = null,
)

object CapturedEventDedupKeyFactory {
    fun build(spec: CapturedEventDedupSpec): String {
        val normalizedEventId = spec.eventId.trim()
        if (normalizedEventId.isNotEmpty()) {
            return "event:$normalizedEventId"
        }

        return buildString {
            append("sms:")
            append(spec.msgType)
            append('|')
            append(spec.source)
            append('|')
            append(spec.sender.orEmpty().trim())
            append('|')
            append(spec.body.orEmpty().trim())
            append('|')
            append(spec.timestamp)
            append('|')
            append(spec.simSlot ?: -1)
            append('|')
            append(spec.subId ?: -1)
        }
    }
}

class RecentEventDeduplicator(
    windowMs: Long,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val recent = BoundedTimestampMap<String>(windowMs, maxEntries)

    @Synchronized
    fun shouldDrop(key: String, now: Long = System.currentTimeMillis()): Boolean {
        return !recent.claim(key, now, refreshOnBlocked = true).claimed
    }

    @Synchronized
    fun clear() {
        recent.clear()
    }

    private companion object {
        private const val DEFAULT_MAX_ENTRIES = 256
    }
}
