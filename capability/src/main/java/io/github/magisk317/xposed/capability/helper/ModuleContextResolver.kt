package io.github.magisk317.xposed.capability.helper

import android.content.Context

/**
 * Resolves the module's own [Context] from any process (app, system_server, telephony)
 * with caching. Uses the current application when available, falls back to the system
 * context, and creates a package context for the module package otherwise.
 */
object ModuleContextResolver {
    @Volatile
    private var cached: Context? = null

    fun resolve(applicationId: String, fallback: Context? = null): Context? {
        cached?.let { return it }
        val base = fallback?.let { it.applicationContext ?: it }
            ?: resolveCurrentApplication()
            ?: resolveSystemContext()
            ?: return null
        val resolved = if (base.packageName == applicationId) {
            base
        } else {
            runCatching {
                base.createPackageContext(applicationId, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
        }
        return resolved?.also { cached = it }
    }

    private fun resolveCurrentApplication(): Context? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication")
        currentApplication.isAccessible = true
        currentApplication.invoke(null) as? Context
    }.getOrNull()

    private fun resolveSystemContext(): Context? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread")
        currentActivityThread.isAccessible = true
        val thread = currentActivityThread.invoke(null) ?: return null
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
        getSystemContext.isAccessible = true
        getSystemContext.invoke(thread) as? Context
    }.getOrNull()
}
