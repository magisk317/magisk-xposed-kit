package io.github.magisk317.xposed.permission

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [PermissionBridge.permissionCommands] must only bridge permissions the app
 * actually declares: `pm grant`/`appops set` of an undeclared permission can
 * only fail, so distributions that drop one (e.g. a Play build without the SMS
 * channel) must not pay for its failing command on every launch.
 */
class PermissionBridgeCommandTest {

    private val allPermissions = setOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.PACKAGE_USAGE_STATS,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    )

    @Test
    fun `every declared bridgeable permission maps to its grant command in order`() {
        assertEquals(
            listOf(
                "pm grant app.pkg ${Manifest.permission.POST_NOTIFICATIONS}",
                "pm grant app.pkg ${Manifest.permission.READ_CONTACTS}",
                "pm grant app.pkg ${Manifest.permission.SEND_SMS}",
                "pm grant app.pkg ${Manifest.permission.READ_PHONE_STATE}",
                "appops set app.pkg GET_USAGE_STATS allow",
                "appops set app.pkg SCHEDULE_EXACT_ALARM allow",
                "appops set app.pkg REQUEST_INSTALL_PACKAGES allow",
                "dumpsys deviceidle whitelist +app.pkg",
            ),
            PermissionBridge.permissionCommands("app.pkg", allPermissions),
        )
    }

    @Test
    fun `undeclared permissions produce no commands`() {
        val commands = PermissionBridge.permissionCommands(
            "app.pkg",
            setOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.PACKAGE_USAGE_STATS),
        )

        assertEquals(
            listOf(
                "pm grant app.pkg ${Manifest.permission.POST_NOTIFICATIONS}",
                "appops set app.pkg GET_USAGE_STATS allow",
            ),
            commands,
        )
    }

    @Test
    fun `nothing declared produces an empty command list`() {
        assertEquals(emptyList<String>(), PermissionBridge.permissionCommands("app.pkg", emptySet()))
    }
}
