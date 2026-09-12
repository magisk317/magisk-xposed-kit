package io.github.magisk317.xposed.preferences

/**
 * Result of one preference read.
 *
 * Missing means the backing source was reachable and did not contain the key. Unavailable means
 * the source could not be read, for example because a remote preference provider is unsupported,
 * frozen, or failed over Binder.
 */
sealed class PreferenceRead<out T> {
    data class Hit<T>(val value: T, val source: String) : PreferenceRead<T>()

    object Missing : PreferenceRead<Nothing>()

    object Unavailable : PreferenceRead<Nothing>()
}

/** Read-only primitive preference source with source attribution and explicit availability. */
interface PreferenceSource {
    val sourceName: String

    fun readBoolean(key: String, defaultValue: Boolean): PreferenceRead<Boolean>

    fun readString(key: String, defaultValue: String): PreferenceRead<String>

    fun readInt(key: String, defaultValue: Int): PreferenceRead<Int>
}
