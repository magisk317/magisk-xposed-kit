package io.github.magisk317.xposed.permission

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

/** Pure platform probes for the startup permission flow; no product policy lives here. */
object StartupPermissionProbes {
    fun requestedPermissionsSet(context: Context): Set<String> = context.requestedPermissionsSet()

    fun isDangerousPermission(context: Context, permission: String): Boolean =
        permission.isDangerousPermission(context)

    fun hasPermission(context: Context, permission: String): Boolean =
        context.hasPermissionGranted(permission)

    fun isRuntimePermissionSupported(permission: String): Boolean = permission.isRuntimePermissionSupported()

    fun currentVersionCode(context: Context): Long = context.currentVersionCode()

    /**
     * PackageManager label fallback for permissions the host has no curated copy for.
     */
    fun defaultPermissionLabel(context: Context, permission: String): String {
        return runCatching {
            @Suppress("DEPRECATION")
            val permissionInfo = context.packageManager.getPermissionInfo(permission, 0)
            permissionInfo.loadLabel(context.packageManager).toString()
        }.getOrDefault(permission)
    }
}

fun Context.requestedPermissionsSet(): Set<String> {
    return runCatching {
        selfPackageInfo(PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
    }.getOrDefault(emptySet())
}

internal fun Context.selfPackageInfo(flags: Int): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, flags)
    }
}

internal fun String.isRuntimePermissionSupported(): Boolean {
    return when (this) {
        Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        else -> true
    }
}

internal fun String.isDangerousPermission(context: Context): Boolean {
    val permissionInfo = runCatching {
        context.packageManager.getPermissionInfo(this, 0)
    }.getOrNull() ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        permissionInfo.protection == PermissionInfo.PROTECTION_DANGEROUS
    } else {
        @Suppress("DEPRECATION")
        (permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
            PermissionInfo.PROTECTION_DANGEROUS
    }
}

internal fun Context.hasPermissionGranted(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.canScheduleExactAlarms(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
    return alarmManager.canScheduleExactAlarms()
}

fun Context.canRequestPackageInstalls(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }
}

fun Context.isExemptedFromBatteryOptimization(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

fun Context.isComponentEnabledInSecureSetting(
    className: String,
    settingName: String,
): Boolean {
    val expectedService = ComponentName(packageName, className).flattenToString()
    val enabledServices = Settings.Secure.getString(contentResolver, settingName).orEmpty()
    if (enabledServices.isBlank()) return false
    return enabledServices.split(':').any { candidate ->
        candidate.equals(expectedService, ignoreCase = true)
    }
}

fun Context.isServiceDeclared(className: String): Boolean {
    val componentName = ComponentName(packageName, className)
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(
                componentName,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getServiceInfo(componentName, PackageManager.GET_META_DATA)
        }
    }.isSuccess
}

fun Context.isAccessibilityServiceListed(className: String): Boolean {
    return isServiceDeclared(className) &&
        runCatching {
            val accessibilityManager = getSystemService(AccessibilityManager::class.java)
            accessibilityManager?.getInstalledAccessibilityServiceList()?.any { serviceInfo ->
                val resolvedServiceInfo = serviceInfo.resolveInfo?.serviceInfo ?: return@any false
                resolvedServiceInfo.packageName == packageName &&
                    resolvedServiceInfo.name == className
            } == true
        }.getOrDefault(false)
}

fun Context.canDrawOverlaysPermission(): Boolean = Settings.canDrawOverlays(this)

fun Context.isRuntimePermissionGranted(permission: String): Boolean = hasPermissionGranted(permission)

/**
 * Usage-stats access via AppOps (raw check, plus the legacy pre-P path). This is only
 * the platform layer of MiPush's requirement resolution - its fallback chain
 * (accessibility proxy, usage query, root gateway) stays host-side.
 */
fun Context.hasUsageStatsAccessViaAppOps(
    uid: Int = applicationInfo.uid,
    pkg: String = packageName,
): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val op = AppOpsManager.OPSTR_GET_USAGE_STATS
    val allowedModes = setOf(
        AppOpsManager.MODE_ALLOWED,
        AppOpsManager.MODE_FOREGROUND,
        AppOpsManager.MODE_DEFAULT,
    )
    if (Build.VERSION.SDK_INT >= 36) {
        return appOps.checkOpRawNoThrow(op, uid, pkg, null) in allowedModes
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        val rawMode = appOps.unsafeCheckOpRawNoThrow(op, uid, pkg)
        return rawMode in allowedModes
    }
    @Suppress("DEPRECATION")
    val mode = appOps.checkOpNoThrow(op, uid, pkg)
    return mode in allowedModes
}

fun Context.currentVersionCode(fallback: Long = 0L): Long {
    return runCatching {
        val packageInfo = selfPackageInfo(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }.getOrDefault(fallback)
}
