package io.github.magisk317.xposed.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Process

/**
 * Validates whether the calling process is authorized to write logs
 * into the host application's ContentProvider.
 *
 * Default policy: allow only the host app, Android system, and phone UIDs.
 * Provider authorities are public package metadata and are not an authorization
 * boundary.
 *
 * At the Binder level, Xposed modules running inside third-party app processes
 * (e.g. WeChat) are indistinguishable from the third-party app itself: both
 * present the host app's UID and signature. Blocking non-system callers would
 * therefore block legitimate module logs.
 *
 * Projects that receive logs from additional hooked processes must opt in with
 * an explicit package/UID allowlist by overriding the provider policy or by
 * installing a host-specific guard with [install].
 */
open class CallerGuard {

    open fun isCallerAllowed(context: Context): Boolean {
        return isPrivilegedUid(Binder.getCallingUid(), context.applicationInfo?.uid)
    }

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
            return uid == Process.ROOT_UID ||
                uid == 0 ||
                uid == Process.SYSTEM_UID ||
                uid == Process.PHONE_UID ||
                (appUid != null && uid == appUid)
        }

        fun isSystemPackageFlags(flags: Int): Boolean {
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            return flags and systemFlags != 0
        }
    }
}

/**
 * Caller policy for providers that accept a fixed set of hooked packages.
 *
 * Package names alone are not trusted: callers outside the host/system UID set
 * must resolve to an explicitly allowed system or updated-system package.
 */
class PackageCallerGuard(
    allowedPackages: Set<String>,
    private val requireSystemPackage: Boolean = true,
    private val allowPhoneUid: Boolean = false,
) : CallerGuard() {
    private val allowedPackages = allowedPackages.toSet()

    override fun isCallerAllowed(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (isPrivilegedUid(uid, context.applicationInfo?.uid, allowPhoneUid)) return true

        val packageManager = context.packageManager
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
            ?: return false
        return packages.any { packageName ->
            val flags = runCatching {
                packageManager.getApplicationInfo(packageName, 0).flags
            }.getOrNull() ?: return@any false
            isPackageAllowed(packageName, flags)
        }
    }

    fun isPackageAllowed(packageName: String, applicationFlags: Int): Boolean {
        if (packageName !in allowedPackages) return false
        return !requireSystemPackage || CallerGuard.isSystemPackageFlags(applicationFlags)
    }

    internal val configuredPackages: Set<String>
        get() = allowedPackages

    companion object {
        fun isPrivilegedUid(uid: Int, appUid: Int?, allowPhoneUid: Boolean = false): Boolean {
            return uid == Process.ROOT_UID ||
                uid == 0 ||
                uid == Process.SYSTEM_UID ||
                (allowPhoneUid && uid == Process.PHONE_UID) ||
                (appUid != null && uid == appUid)
        }
    }
}
