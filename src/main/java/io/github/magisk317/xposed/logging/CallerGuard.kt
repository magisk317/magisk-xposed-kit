package io.github.magisk317.xposed.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Process

/**
 * Validates whether the calling process is authorized to write logs
 * into the host application's ContentProvider.
 *
 * Default policy: **allow all callers**. The provider authority (containing
 * the applicationId) acts as the gate — callers must know the exact authority
 * string to reach this provider.
 *
 * At the Binder level, Xposed modules running inside third-party app processes
 * (e.g. WeChat) are indistinguishable from the third-party app itself: both
 * present the host app's UID and signature. Blocking non-system callers would
 * therefore block legitimate module logs.
 *
 * Projects that need stricter control (e.g. signature-based or allowlist-based)
 * can override [isCallerAllowed] by subclassing [CallerGuard] and replacing
 * the singleton instance via [install].
 */
open class CallerGuard {

    open fun isCallerAllowed(context: Context): Boolean = true

    companion object {
        private val DEFAULT = CallerGuard()

        @Volatile
        private var delegate: CallerGuard = DEFAULT

        /**
         * Install a custom CallerGuard implementation for stricter caller validation.
         */
        fun install(guard: CallerGuard) {
            delegate = guard
        }

        fun isCallerAllowed(context: Context): Boolean =
            delegate.isCallerAllowed(context)

        fun isPrivilegedUid(uid: Int, appUid: Int?): Boolean {
            return uid == Process.SYSTEM_UID ||
                uid == Process.PHONE_UID ||
                (appUid != null && uid == appUid)
        }

        fun isSystemPackageFlags(flags: Int): Boolean {
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            return flags and systemFlags != 0
        }
    }
}
