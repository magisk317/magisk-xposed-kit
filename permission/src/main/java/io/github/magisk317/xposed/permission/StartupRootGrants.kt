package io.github.magisk317.xposed.permission

import android.content.Context
import android.provider.Settings

/**
 * Root-channel default implementation over [PermissionBridge]: grants everything the
 * bridge can cover in one idempotent pass; the remaining gap is re-collected by the caller.
 */
object StartupRootGrants {
    fun grantAll(
        context: Context,
        config: StartupPermissionConfig,
        log: ((String) -> Unit)? = null,
    ): Boolean {
        return PermissionBridge.runRoot(
            PermissionBridge.bridgeCommands(
                context = context,
                notificationListenerComponent = config.notificationListenerComponent,
                notificationListenerEnabled = StartupPermissionCollectorCore.isNotificationListenerEnabled(context, config),
                accessibilityServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ),
                accessibilityComponent = config.autoInputAccessibilityComponent,
            ),
            log = log,
        )
    }
}
