package io.github.magisk317.xposed.permission

import android.content.Context

/**
 * Collects the startup permissions the app still misses (and the full status list),
 * independent of any screen and of any product.
 *
 * Hosts inject copy via [labelResolver] and their root seam via [StartupRootGrantChannel];
 * everything platform-shaped lives in [StartupPermissionProbes]/[StartupSpecialPermission].
 *
 * Contract note carried over from the app family's bug history: special permissions and
 * runtime permissions must be evaluated with the same keys the caller will re-check with,
 * so the upgrade-replay gate and the wizard report can never disagree.
 */
object StartupPermissionCollectorCore {

    fun collectMissing(
        context: Context,
        config: StartupPermissionConfig,
        labelResolver: (String) -> String,
        rootChannel: StartupRootGrantChannel,
    ): List<StartupRequirement> {
        val requestedPermissions = context.requestedPermissionsSet()
        val missing = mutableListOf<StartupRequirement>()
        requestedPermissions
            .filter { permission ->
                permission.isRuntimePermissionSupported() &&
                    permission.isDangerousPermission(context) &&
                    !context.hasPermissionGranted(permission)
            }
            .distinct()
            .mapTo(missing) { permission ->
                StartupRequirement(
                    key = permission,
                    rootGrantable = rootChannel.coverage(context, permission) == StartupRootCoverage.COVERED,
                )
            }
        StartupSpecialPermission.entries
            .filter { it.isApplicable(context, requestedPermissions, config) && !it.isGranted(context, config) }
            .mapTo(missing) { special ->
                val key = StartupRequirement.SPECIAL_KEY_PREFIX + special.key
                StartupRequirement(
                    key = key,
                    rootGrantable = rootChannel.coverage(context, key) == StartupRootCoverage.COVERED,
                )
            }
        return missing.distinctBy(StartupRequirement::key)
    }

    fun collectStatuses(
        context: Context,
        config: StartupPermissionConfig,
        labelResolver: (String) -> String,
        rootChannel: StartupRootGrantChannel,
    ): List<StartupRequirementStatus> {
        val requestedPermissions = context.requestedPermissionsSet()
        val statuses = mutableListOf<StartupRequirementStatus>()
        requestedPermissions
            .filter { permission ->
                permission.isRuntimePermissionSupported() && permission.isDangerousPermission(context)
            }
            .distinct()
            .mapTo(statuses) { permission ->
                StartupRequirementStatus(
                    requirement = StartupRequirement(
                        key = permission,
                        rootGrantable = rootChannel.coverage(context, permission) == StartupRootCoverage.COVERED,
                    ),
                    granted = context.hasPermissionGranted(permission),
                )
            }
        StartupSpecialPermission.entries
            .filter { it.isApplicable(context, requestedPermissions, config) }
            .mapTo(statuses) { special ->
                val key = StartupRequirement.SPECIAL_KEY_PREFIX + special.key
                StartupRequirementStatus(
                    requirement = StartupRequirement(
                        key = key,
                        rootGrantable = rootChannel.coverage(context, key) == StartupRootCoverage.COVERED,
                    ),
                    granted = special.isGranted(context, config),
                )
            }
        return statuses.distinctBy { it.requirement.key }
    }

    fun isNotificationListenerEnabled(context: Context, config: StartupPermissionConfig): Boolean {
        return context.isComponentEnabledInSecureSetting(
            className = config.notificationListenerClassName(),
            settingName = "enabled_notification_listeners",
        )
    }
}
