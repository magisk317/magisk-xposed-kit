package io.github.magisk317.xposed.permission

import android.Manifest
import android.content.Context
import android.os.Build

/** Settings-page permissions the startup flow can route the user to. */
enum class StartupSpecialPermission(val key: String) {
    EXACT_ALARM("exact_alarm"),
    INSTALL_UNKNOWN_APPS("install_unknown_apps"),
    NOTIFICATION_LISTENER("notification_listener"),
    ACCESSIBILITY_SERVICE("accessibility_service"),
    BATTERY_OPTIMIZATION("battery_optimization"),
    ;

    fun isApplicable(
        context: Context,
        requestedPermissions: Set<String>,
        config: StartupPermissionConfig,
    ): Boolean {
        return when (this) {
            EXACT_ALARM ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    Manifest.permission.SCHEDULE_EXACT_ALARM in requestedPermissions

            INSTALL_UNKNOWN_APPS ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    Manifest.permission.REQUEST_INSTALL_PACKAGES in requestedPermissions

            NOTIFICATION_LISTENER ->
                context.isServiceDeclared(config.notificationListenerClassName())

            ACCESSIBILITY_SERVICE -> {
                val className = config.autoInputAccessibilityClassName()
                className != null && context.isServiceDeclared(className)
            }

            BATTERY_OPTIMIZATION ->
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in requestedPermissions
        }
    }

    fun isGranted(context: Context, config: StartupPermissionConfig): Boolean {
        return when (this) {
            EXACT_ALARM -> context.canScheduleExactAlarms()
            INSTALL_UNKNOWN_APPS -> context.canRequestPackageInstalls()
            NOTIFICATION_LISTENER -> context.isComponentEnabledInSecureSetting(
                className = config.notificationListenerClassName(),
                settingName = "enabled_notification_listeners",
            )

            ACCESSIBILITY_SERVICE -> {
                val className = config.autoInputAccessibilityClassName()
                className != null && context.isComponentEnabledInSecureSetting(
                    className = className,
                    settingName = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }

            BATTERY_OPTIMIZATION -> context.isExemptedFromBatteryOptimization()
        }
    }

    companion object {
        val entriesByKey: Map<String, StartupSpecialPermission> = entries.associateBy(StartupSpecialPermission::key)
    }
}

/** Bare class name of the flattened `package/Class` component string. */
fun StartupPermissionConfig.notificationListenerClassName(): String =
    notificationListenerComponent.substringAfter('/')

/** Bare class name of the accessibility component, or null when this build has none. */
fun StartupPermissionConfig.autoInputAccessibilityClassName(): String? =
    autoInputAccessibilityComponent?.substringAfter('/')
