package io.github.magisk317.xposed.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** System-settings routing for startup special permissions; null = no page on this OS. */
object StartupSetupPermissionIntents {
    fun buildSystemIntent(context: Context, requirementKey: String, config: StartupPermissionConfig): Intent? {
        if (!requirementKey.startsWith(StartupRequirement.SPECIAL_KEY_PREFIX)) return null
        val special = StartupSpecialPermission.entriesByKey[
            requirementKey.removePrefix(StartupRequirement.SPECIAL_KEY_PREFIX),
        ] ?: return null
        return when (special) {
            StartupSpecialPermission.EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    null
                } else {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
            }

            StartupSpecialPermission.INSTALL_UNKNOWN_APPS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    null
                } else {
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    )
                }
            }

            StartupSpecialPermission.NOTIFICATION_LISTENER ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

            StartupSpecialPermission.ACCESSIBILITY_SERVICE -> {
                val className = config.autoInputAccessibilityClassName()
                if (className != null && context.isAccessibilityServiceListed(className)) {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    val componentName = ComponentName(context, className).flattenToString()
                    intent.putExtra(":settings:fragment_args_key", componentName)
                    intent.putExtra(":settings:show_fragment_args", android.os.Bundle())
                    intent
                } else {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                }
            }

            StartupSpecialPermission.BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
        }
    }
}
