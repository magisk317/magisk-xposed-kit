package io.github.magisk317.xposed.capability.helper

import android.content.Context

/**
 * Resolves the context that belongs to the process currently making an IPC call.
 *
 * Provider authorities identify the module, but Binder still validates the calling package
 * carried by [Context.getContentResolver] against the caller UID. Creating a package context for
 * the module inside phone/MMS/system_server therefore produces an impossible identity pair
 * (`android` or module package + phone UID). Provider clients must use this resolver, while code
 * that actually needs module resources keeps using [ModuleContextResolver].
 */
object ProcessContextResolver {
    @Volatile
    private var reflected: Context? = null

    fun resolve(fallback: Context? = null): Context? {
        // Keep the supplied package context: ContentResolver uses its attribution package when it
        // validates the IPC caller UID. `applicationContext` can collapse a phone/MMS package
        // context back to the `android` system context and recreate the exact mismatch we avoid.
        fallback?.let { return it }
        reflected?.let { return it }
        val resolved = resolveCurrentApplication() ?: resolveSystemContext() ?: return null
        return resolved.also { reflected = it }
    }

    fun resolveForPackage(packageName: String, fallback: Context? = null): Context? {
        val base = resolve(fallback) ?: return null
        if (packageName.isBlank() || base.packageName == packageName) return base
        return runCatching {
            base.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: base
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
