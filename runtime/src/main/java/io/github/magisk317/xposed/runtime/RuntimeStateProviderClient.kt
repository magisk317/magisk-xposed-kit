package io.github.magisk317.xposed.runtime

import android.content.Context
import android.os.Bundle
import io.github.magisk317.xposed.logging.XLog

/**
 * The client side of the module's own runtime-state provider.
 *
 * Every host speaks the same protocol here: the runtime gate claim and the hook heartbeat. The
 * protocol lives in the kit so the hosts cannot drift apart on the extras, on the result keys or on
 * the address the provider is reached at.
 */
object RuntimeStateProviderClient {

    /** The extra the providers gate their calls with. */
    const val EXTRA_IPC_TOKEN = "ipc_token"

    /** What the runtime gate answered. */
    data class GateClaim(
        val claimed: Boolean,
        val ageMs: Long? = null,
        val blockedKey: String? = null,
    )

    /**
     * Claims the runtime gate for [fileName].
     *
     * Fails open: when the provider cannot be reached the claim is granted, because a missing IPC
     * channel must not stop the delivery the gate only guards against duplicates.
     */
    fun claimRuntimeGate(
        context: Context,
        applicationId: String,
        fileName: String,
        keys: Collection<String>,
        windowMs: Long,
        maxEntries: Int = RuntimeStateProviderContract.DEFAULT_MAX_ENTRIES,
        token: String? = null,
        onFailure: (method: String, error: Throwable) -> Unit = { calledMethod, error ->
            XLog.w(
                message = "runtime gate claim failed: method=%s authority=%s err=%s",
                calledMethod,
                RuntimeStateProviderAddress.authorityOf(applicationId),
                error.message ?: error.javaClass.simpleName,
            )
        },
    ): GateClaim {
        val normalizedKeys = keys
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toCollection(ArrayList())
        if (normalizedKeys.isEmpty()) return GateClaim(claimed = true)

        val extras = Bundle().apply {
            putStringArrayList(RuntimeStateProviderContract.EXTRA_KEYS, normalizedKeys)
            putLong(RuntimeStateProviderContract.EXTRA_WINDOW_MS, windowMs)
            putInt(RuntimeStateProviderContract.EXTRA_MAX_ENTRIES, maxEntries)
            if (!token.isNullOrBlank()) {
                putString(EXTRA_IPC_TOKEN, token)
            }
        }
        val response = RuntimeStateProviderAddress.call(
            context = context,
            applicationId = applicationId,
            method = RuntimeStateProviderContract.METHOD_CLAIM_RUNTIME_GATE,
            arg = fileName,
            extras = extras,
            onFailure = onFailure,
        ) ?: return GateClaim(claimed = true)

        val ageMs = response
            .getLong(RuntimeStateProviderContract.RESULT_AGE_MS, RuntimeStateProviderContract.NO_AGE_MS)
            .takeIf { it != RuntimeStateProviderContract.NO_AGE_MS }
        return GateClaim(
            claimed = response.getBoolean(RuntimeStateProviderContract.RESULT_CLAIMED, true),
            ageMs = ageMs,
            blockedKey = response.getString(RuntimeStateProviderContract.RESULT_BLOCKED_KEY),
        )
    }

    /**
     * Records that the hook is alive in the calling process.
     *
     * Returns false when the provider could not be reached; the caller decides whether that is
     * worth a warning, because the framework-reported hook targets cover the same ground.
     */
    fun recordHookHeartbeat(
        context: Context,
        applicationId: String,
        packageName: String,
        processName: String,
        source: String,
        verboseLogging: Boolean,
        route: String,
        token: String? = null,
        moduleVersion: Int? = null,
        onFailure: (method: String, error: Throwable) -> Unit = { calledMethod, error ->
            XLog.w(
                message = "hook heartbeat failed: method=%s authority=%s err=%s",
                calledMethod,
                RuntimeStateProviderAddress.authorityOf(applicationId),
                error.message ?: error.javaClass.simpleName,
            )
        },
    ): Boolean {
        val extras = Bundle().apply {
            putString(RuntimeStateProviderContract.EXTRA_PACKAGE_NAME, packageName)
            putString(RuntimeStateProviderContract.EXTRA_PROCESS_NAME, processName)
            putString(RuntimeStateProviderContract.EXTRA_SOURCE, source)
            putBoolean(RuntimeStateProviderContract.EXTRA_VERBOSE_LOGGING, verboseLogging)
            putString(RuntimeStateProviderContract.EXTRA_ROUTE, route)
            if (moduleVersion != null) {
                putInt(RuntimeStateProviderContract.EXTRA_MODULE_VERSION, moduleVersion)
            }
            if (!token.isNullOrBlank()) {
                putString(EXTRA_IPC_TOKEN, token)
            }
        }
        return RuntimeStateProviderAddress.call(
            context = context,
            applicationId = applicationId,
            method = RuntimeStateProviderContract.METHOD_RECORD_HOOK_HEARTBEAT,
            extras = extras,
            onFailure = onFailure,
        )?.getBoolean(RuntimeStateProviderContract.RESULT_OK, false) == true
    }
}
