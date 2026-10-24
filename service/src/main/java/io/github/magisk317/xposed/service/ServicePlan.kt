package io.github.magisk317.xposed.service

/**
 * Scope and preference-mirror arithmetic.
 *
 * Kept free of Android and Binder types so the decisions a caller can get wrong — what is still
 * missing from a scope, which entry is too large for one binder transaction, which values a
 * SharedPreferences editor can carry at all — are testable on a plain JVM.
 */
internal object ServicePlan {

    /** Per-entry parcel framing: type tag, alignment and the string header. */
    private const val ENTRY_OVERHEAD = 16L

    /** Packages in [wanted] that [granted] does not contain yet, in the caller's order. */
    fun missingTargets(granted: Collection<String>, wanted: Collection<String>): List<String> {
        val current = granted.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return wanted.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filterNot { it in current }
            .toList()
    }

    /**
     * Estimate of what one key costs inside a RemotePreferences bundle.
     *
     * The framework's limit is on the whole transaction, so this deliberately overshoots (UTF-16
     * characters, 8-byte payload framing) rather than reproduce the exact parcel encoding.
     */
    fun entryBytes(key: String, value: Any?): Long {
        val payload = when (value) {
            null -> 0L
            is Boolean, is Float, is Int, is Long -> 8L
            is String -> 8L + value.length * 2L
            is Set<*> -> 8L + value.sumOf { (it as? String)?.length?.times(2L) ?: 0L } +
                value.size * 4L
            else -> 8L + value.toString().length * 2L
        }
        return ENTRY_OVERHEAD + key.length * 2L + payload
    }

    /** Whether a SharedPreferences editor can carry this value type at all. */
    fun isMirrorable(value: Any?): Boolean = when (value) {
        is Boolean, is Float, is Int, is Long, is String -> true
        is Set<*> -> value.all { it is String }
        else -> false
    }

    /** Estimated payload of a whole group, i.e. one binder transaction. */
    fun groupBytes(entries: Map<String, Any?>): Long =
        entries.entries.sumOf { (key, value) -> entryBytes(key, value) }

    class MirrorPlan(
        val groups: Map<String, Map<String, Any?>>,
        val ungrouped: List<String>,
        val unsupported: List<String>,
        val oversized: List<String>,
        val oversizedGroups: List<String>,
    )

    /**
     * Buckets [entries] by the caller's group policy, dropping what must not be sent.
     *
     * Keys are visited in sorted order so a report is reproducible. A null or blank group means the
     * caller excluded that key; that decision, like every other policy, stays outside this module. A
     * group that is over budget is withheld whole instead of half-written, because splitting it
     * would mean inventing group names on the caller's behalf.
     */
    fun partition(
        entries: Map<String, Any?>,
        keyGroup: (key: String) -> String?,
        maxEntryBytes: Long,
        maxGroupBytes: Long,
    ): MirrorPlan {
        val bucketed = linkedMapOf<String, MutableMap<String, Any?>>()
        val ungrouped = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val oversized = mutableListOf<String>()
        for (key in entries.keys.sorted()) {
            val group = keyGroup(key)?.trim()?.takeIf { it.isNotEmpty() }
            if (group == null) {
                ungrouped += key
                continue
            }
            val value = entries[key]
            if (!isMirrorable(value)) {
                unsupported += key
                continue
            }
            if (entryBytes(key, value) > maxEntryBytes) {
                oversized += key
                continue
            }
            bucketed.getOrPut(group) { linkedMapOf() }[key] = value
        }
        val groups = linkedMapOf<String, Map<String, Any?>>()
        val oversizedGroups = mutableListOf<String>()
        for ((group, bucket) in bucketed) {
            if (groupBytes(bucket) > maxGroupBytes) {
                oversizedGroups += group
            } else {
                groups[group] = bucket
            }
        }
        return MirrorPlan(groups, ungrouped, unsupported, oversized, oversizedGroups)
    }
}
