package io.github.magisk317.xposed.runtime

import android.content.Context

/**
 * Host-neutral seam between injected hooks and the product application.
 *
 * SMS parsing, forwarding policy, sender configuration and UI decisions do not belong here.
 * Those domains build adapters around this infrastructure contract.
 */
interface HookHostCapabilities {
    fun isVerboseLogMode(context: Context): Boolean

    fun onProcessInit(context: Context) {}

    fun recordHookHeartbeat(
        context: Context,
        packageName: String,
        processName: String,
        source: String,
        verboseLogging: Boolean,
        moduleVersion: Int = 0,
    ) {}

    fun recordHookProcessOnline(
        context: Context,
        packageName: String,
        processName: String,
        source: String,
        moduleVersion: Int,
        verboseLogging: Boolean,
    ) {}

    fun recordHookInstall(
        context: Context?,
        hook: String,
        status: String,
        packageName: String,
        processName: String,
        detail: String,
        verboseLogging: Boolean,
    ) {}

    fun recordNotifyEvent(
        context: Context,
        source: String,
        packageName: String,
        channelId: String,
        msgType: String,
        skipReason: String,
        verboseLogging: Boolean,
    ) {}

    fun recordCallNotificationSource(
        context: Context,
        packageName: String,
        channelId: String,
        category: String,
        verboseLogging: Boolean,
    ) {}
}
