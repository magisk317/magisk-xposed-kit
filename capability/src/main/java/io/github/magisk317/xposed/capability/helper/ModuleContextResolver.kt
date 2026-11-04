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
        val base = ProcessContextResolver.resolve(fallback) ?: return null
        val resolved = if (base.packageName == applicationId) {
            base
        } else {
            runCatching {
                base.createPackageContext(applicationId, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
        }
        return resolved?.also { cached = it }
    }
}
