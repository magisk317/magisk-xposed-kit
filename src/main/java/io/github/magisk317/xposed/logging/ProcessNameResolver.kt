package io.github.magisk317.xposed.logging

import android.app.Application
import android.os.Build

/**
 * Utility for resolving the current process name.
 *
 * Resolution order is platform API, then ActivityThread reflection, then
 * /proc/self/cmdline. `Application.getProcessName()` returns null inside
 * system_server and during early Zygote forking, so it cannot be the last
 * attempt; an empty result must not be cached either, because the process name
 * is not known yet at that point.
 */
object ProcessNameResolver {

    @Volatile
    private var cached: String? = null

    fun currentProcessName(): String {
        cached?.let { return it }
        val name = resolve()
        if (name.isNotEmpty()) {
            cached = name
        }
        return name
    }

    private fun resolve(): String {
        // Try platform API first (Android P+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val platformName = runCatching { Application.getProcessName() }.getOrNull()
            if (!platformName.isNullOrEmpty()) return platformName
        }
        // Fallback: ActivityThread reflection (works in system_server too)
        val reflectedName = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentProcessName = activityThreadClass.getDeclaredMethod("currentProcessName")
            currentProcessName.isAccessible = true
            currentProcessName.invoke(null) as? String
        }.getOrNull()
        if (!reflectedName.isNullOrEmpty()) return reflectedName
        // Last resort: /proc/self/cmdline
        val cmdlineName = runCatching {
            java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ')
        }.getOrNull()
        return cmdlineName.orEmpty()
    }
}
