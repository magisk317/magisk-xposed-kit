package io.github.magisk317.xposed.preferences

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Adapts an Android SharedPreferences provider to [PreferenceSource].
 *
 * The provider is invoked for every read so a remote Binder-backed source can become available or
 * unavailable without being permanently captured. This class is deliberately read-only: it does
 * not publish values, change file permissions, or decide whether a local source may override a
 * remote source.
 */
class SharedPreferencesSource(
    override val sourceName: String,
    private val provider: () -> SharedPreferences?,
    private val onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
) : PreferenceSource {

    init {
        require(sourceName.isNotBlank()) { "Preference source name must not be blank" }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun readBoolean(key: String, defaultValue: Boolean): PreferenceRead<Boolean> =
        when (val raw = readRaw(key)) {
            is PreferenceRead.Hit -> when (val value = raw.value) {
                is Boolean -> PreferenceRead.Hit(value, sourceName)
                is Number -> PreferenceRead.Hit(value.toInt() != 0, sourceName)
                is String -> parseBoolean(value)?.let { PreferenceRead.Hit(it, sourceName) }
                    ?: PreferenceRead.Unavailable
                else -> PreferenceRead.Unavailable
            }
            PreferenceRead.Missing -> PreferenceRead.Missing
            PreferenceRead.Unavailable -> PreferenceRead.Unavailable
        }

    override fun readString(key: String, defaultValue: String): PreferenceRead<String> =
        when (val raw = readRaw(key)) {
            is PreferenceRead.Hit -> PreferenceRead.Hit(
                value = when (val value = raw.value) {
                    null -> defaultValue
                    is String -> value
                    else -> value.toString()
                },
                source = sourceName,
            )
            PreferenceRead.Missing -> PreferenceRead.Missing
            PreferenceRead.Unavailable -> PreferenceRead.Unavailable
        }

    @Suppress("UNUSED_PARAMETER")
    override fun readInt(key: String, defaultValue: Int): PreferenceRead<Int> =
        when (val raw = readRaw(key)) {
            is PreferenceRead.Hit -> when (val value = raw.value) {
                is Int -> PreferenceRead.Hit(value, sourceName)
                is Number -> PreferenceRead.Hit(value.toInt(), sourceName)
                is String -> value.trim().toIntOrNull()?.let { PreferenceRead.Hit(it, sourceName) }
                    ?: PreferenceRead.Unavailable
                else -> PreferenceRead.Unavailable
            }
            PreferenceRead.Missing -> PreferenceRead.Missing
            PreferenceRead.Unavailable -> PreferenceRead.Unavailable
        }

    private fun readRaw(key: String): PreferenceRead<Any?> {
        if (key.isBlank()) return PreferenceRead.Missing
        val preferences = runCatching { provider() }
            .onFailure { report("resolve preferences", it) }
            .getOrNull() ?: return PreferenceRead.Unavailable

        return runCatching { preferences.all }
            .mapCatching { values ->
                if (values.containsKey(key)) {
                    PreferenceRead.Hit(values[key], sourceName)
                } else {
                    PreferenceRead.Missing
                }
            }
            .onFailure { report("read key '$key'", it) }
            .getOrDefault(PreferenceRead.Unavailable)
    }

    private fun parseBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> null
    }

    private fun report(operation: String, error: Throwable) {
        runCatching { onError(operation, error) }
    }
}

/** Constructors for the two platform providers used by Xposed consumers. */
object LibXposedPreferenceSources {
    fun remote(
        xposed: XposedInterface,
        group: String,
        onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
    ): SharedPreferencesSource {
        require(group.isNotBlank()) { "Remote preference group must not be blank" }
        return SharedPreferencesSource(
            sourceName = "remote_libxposed:$group",
            provider = { xposed.getRemotePreferences(group) },
            onError = onError,
        )
    }

    fun local(
        context: Context,
        name: String,
        onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
    ): SharedPreferencesSource {
        require(name.isNotBlank()) { "Local preference name must not be blank" }
        return SharedPreferencesSource(
            sourceName = "local:$name",
            provider = { context.getSharedPreferences(name, Context.MODE_PRIVATE) },
            onError = onError,
        )
    }
}
