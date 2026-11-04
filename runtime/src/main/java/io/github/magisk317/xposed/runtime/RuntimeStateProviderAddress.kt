package io.github.magisk317.xposed.runtime

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.magisk317.xposed.logging.XLog

/**
 * Where the module's own app process publishes the runtime state.
 *
 * The address is derived from the MODULE id, never from a [Context]: the hook process runs inside
 * another package's process, so `context.packageName` there is the hooked app (com.android.phone and
 * friends) and every call lands on a provider that does not exist. Hosts hand their module id in
 * once, and every hook-side call site goes through here, so the rule cannot be forgotten at one
 * site.
 */
object RuntimeStateProviderAddress {

    /** Suffix of the app-side provider that carries the runtime state. */
    const val AUTHORITY_SUFFIX = ".db.provider"

    /** Authority of the module's own runtime-state provider. */
    fun authorityOf(applicationId: String): String = applicationId + AUTHORITY_SUFFIX

    /** Uri of the module's own runtime-state provider. */
    fun uriOf(applicationId: String): Uri = Uri.parse("content://${authorityOf(applicationId)}")

    /**
     * Calls the module's runtime-state provider.
     *
     * Returns null when the provider could not be reached. The app process may be stopped and the
     * system may refuse to start it, which is exactly the case the framework-reported hook targets
     * cover, so a failure here is a missing signal rather than a crash.
     *
     * @param onFailure receives the method and the cause. The default reports it through the kit
     *   logger, so a silently dropped signal cannot happen.
     */
    fun call(
        context: Context,
        applicationId: String,
        method: String,
        arg: String? = null,
        extras: Bundle? = null,
        onFailure: (method: String, error: Throwable) -> Unit = { calledMethod, error ->
            XLog.w(
                message = "runtime state provider call failed: method=%s authority=%s err=%s",
                calledMethod,
                authorityOf(applicationId),
                error.message ?: error.javaClass.simpleName,
            )
        },
    ): Bundle? {
        val identity = Binder.clearCallingIdentity()
        return try {
            runCatching {
                context.contentResolver.call(uriOf(applicationId), method, arg, extras)
            }.onFailure { error ->
                onFailure(method, error)
            }.getOrNull()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
