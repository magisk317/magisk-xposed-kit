package io.github.magisk317.xposed.service

import android.content.SharedPreferences

/**
 * Mirrors the app's own SharedPreferences into the framework's remote preference groups.
 *
 * Which keys are mirrored, which group each belongs to and which source wins all belong to the
 * caller: [keyGroup] returns null for a key that must not leave the app. This class only moves
 * values, refuses anything large enough to break the binder transaction it shares with the rest of
 * its group, and reports every key or group it did not move.
 */
class RemotePreferenceMirror(
    private val local: () -> SharedPreferences?,
    private val remote: (group: String) -> SharedPreferences?,
    private val keyGroup: (key: String) -> String?,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maxGroupBytes: Long = DEFAULT_MAX_GROUP_BYTES,
    private val onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
) {

    /** Everything one publish pass decided, with nothing dropped silently. */
    data class Report(
        val written: Int = 0,
        val removed: Int = 0,
        val ungrouped: List<String> = emptyList(),
        val unsupported: List<String> = emptyList(),
        val oversized: List<String> = emptyList(),
        val oversizedGroups: List<String> = emptyList(),
        val failedGroups: List<String> = emptyList(),
        val sourceUnavailable: Boolean = false,
    )

    fun publishAll(): Report {
        val source = runCatching { local() }
            .onFailure { onError("resolve local preferences", it) }
            .getOrNull()
            ?: return Report(sourceUnavailable = true)
        val values = runCatching { source.all }
            .onFailure { onError("read local preferences", it) }
            .getOrNull()
            ?: return Report(sourceUnavailable = true)

        val plan = ServicePlan.partition(values, keyGroup, maxEntryBytes, maxGroupBytes)
        var written = 0
        val failed = mutableListOf<String>()
        for ((group, entries) in plan.groups) {
            runCatching {
                val editor = editorFor(group)
                entries.forEach { (key, value) ->
                    if (!write(editor, key, value)) error("cannot write '$key' into '$group'")
                }
                commit(editor, group)
                entries.size
            }.onSuccess { written += it }
                .onFailure {
                    onError("mirror group '$group'", it)
                    failed += group
                }
        }
        return Report(
            written = written,
            ungrouped = plan.ungrouped,
            unsupported = plan.unsupported,
            oversized = plan.oversized,
            oversizedGroups = plan.oversizedGroups,
            failedGroups = failed,
        )
    }

    /**
     * Mirrors one key after a change. A key that is no longer present locally is removed remotely,
     * so a delete survives as surely as a write.
     */
    fun publishKey(key: String): Report {
        val group = keyGroup(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Report(ungrouped = listOf(key))
        val source = runCatching { local() }
            .onFailure { onError("resolve local preferences", it) }
            .getOrNull()
            ?: return Report(sourceUnavailable = true)
        val values = runCatching { source.all }
            .onFailure { onError("read local preferences", it) }
            .getOrNull()
            ?: return Report(sourceUnavailable = true)

        return runCatching {
            val editor = editorFor(group)
            if (!values.containsKey(key)) {
                editor.remove(key)
                commit(editor, group)
                Report(removed = 1)
            } else {
                val value = values[key]
                when {
                    !ServicePlan.isMirrorable(value) -> Report(unsupported = listOf(key))
                    ServicePlan.entryBytes(key, value) > maxEntryBytes -> Report(oversized = listOf(key))
                    else -> {
                        if (!write(editor, key, value)) error("cannot write '$key' into '$group'")
                        commit(editor, group)
                        Report(written = 1)
                    }
                }
            }
        }.onFailure { onError("mirror key '$key'", it) }
            .getOrElse { Report(failedGroups = listOf(group)) }
    }

    private fun editorFor(group: String): SharedPreferences.Editor {
        val target = remote(group) ?: error("remote group '$group' is unavailable")
        return target.edit() ?: error("remote group '$group' rejects edits")
    }

    private fun commit(editor: SharedPreferences.Editor, group: String) {
        if (!editor.commit()) error("commit rejected for '$group'")
    }

    private fun write(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                if (!value.all { it is String }) return false
                editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            else -> return false
        }
        return true
    }

    companion object {
        /**
         * A RemotePreferences group travels in one binder transaction, whose cap is shared by every
         * key in it. These ceilings are estimates against that shared budget, not against the
         * framework's own storage limit.
         */
        const val DEFAULT_MAX_ENTRY_BYTES = 128L * 1024L
        const val DEFAULT_MAX_GROUP_BYTES = 512L * 1024L
    }
}
