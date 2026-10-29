package io.github.magisk317.xposed.capability.prefs

import io.github.magisk317.xposed.logging.MagiskOtel

interface CorePrefsAccess {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getString(key: String, defaultValue: String): String
    fun getInt(key: String, defaultValue: Int): Int
}

object CorePrefs {
    @Volatile
    private var accessRef: CorePrefsAccess? = null

    fun install(access: CorePrefsAccess) {
        accessRef = access
        MagiskOtel.event(
            name = "prefs.access",
            attributes = mapOf(
                "result" to "ok",
                "duration_ms" to "0",
                "process" to "hook",
                "reason" to "installed",
            ),
            statusOk = true,
        )
    }

    // Test seam: consumers reset the hook-side prefs between unit tests.
    fun resetForTest() {
        accessRef = null
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        accessRef?.getBoolean(key, defaultValue) ?: defaultValue

    fun getString(key: String, defaultValue: String): String =
        accessRef?.getString(key, defaultValue) ?: defaultValue

    fun getInt(key: String, defaultValue: Int): Int =
        accessRef?.getInt(key, defaultValue) ?: defaultValue
}
