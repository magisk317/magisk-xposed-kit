package io.github.magisk317.xposed.logging

import android.app.Application
import android.os.Build

/**
 * Utility for resolving the current process name.
 * Uses the platform API on Android P+ and falls back to ActivityThread reflection on older versions.
 */
object ProcessNameResolver {

    @Volatile
    private var cached: String? = null

    fun currentProcessName(): String {
        cached?.let { return it }
        val name = resolve()
        cached = name
        return name
    }

    private fun resolve(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        }
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentProcessName = activityThreadClass.getDeclaredMethod("currentProcessName")
            currentProcessName.isAccessible = true
            currentProcessName.invoke(null) as? String
        }.getOrNull().orEmpty()
    }
}
