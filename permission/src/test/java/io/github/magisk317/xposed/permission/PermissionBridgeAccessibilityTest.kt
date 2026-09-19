package io.github.magisk317.xposed.permission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The accessibility list is shared with every service the user enabled, so these cover the merge and
 * the filter independently of any device.
 */
class PermissionBridgeAccessibilityTest {

    private val component = "io.example.app/io.example.app.KeepAliveService"
    private val other = "com.other.app/com.other.app.TalkBackService"
    private val third = "com.third.app/com.third.app.Switcher"

    @Test
    fun `enabling an empty list writes the component and the master switch`() {
        assertEquals(
            listOf(
                "settings put secure enabled_accessibility_services $component",
                "settings put secure accessibility_enabled 1",
            ),
            PermissionBridge.accessibilityCommands(currentServices = null, component = component),
        )
    }

    @Test
    fun `enabling keeps the services already in the list`() {
        assertEquals(
            listOf(
                "settings put secure enabled_accessibility_services $other:$third:$component",
                "settings put secure accessibility_enabled 1",
            ),
            PermissionBridge.accessibilityCommands("$other:$third", component),
        )
    }

    @Test
    fun `enabling an already enabled component costs no root round trip`() {
        assertTrue(PermissionBridge.accessibilityCommands("$other:$component", component).isEmpty())
    }

    @Test
    fun `disabling removes only the target`() {
        assertEquals(
            listOf("settings put secure enabled_accessibility_services $other:$third"),
            PermissionBridge.accessibilityCommands("$other:$component:$third", component, enable = false),
        )
    }

    @Test
    fun `disabling the last service clears the list without touching the master switch`() {
        assertEquals(
            listOf("settings put secure enabled_accessibility_services "),
            PermissionBridge.accessibilityCommands(component, component, enable = false),
        )
    }

    @Test
    fun `disabling a component that is not enabled costs no root round trip`() {
        assertTrue(PermissionBridge.accessibilityCommands(other, component, enable = false).isEmpty())
    }

    @Test
    fun `a longer component name is not mistaken for the target`() {
        val longer = "${component}Wrapper"
        assertEquals(
            listOf(
                "settings put secure enabled_accessibility_services $longer:$component",
                "settings put secure accessibility_enabled 1",
            ),
            PermissionBridge.accessibilityCommands(longer, component),
        )
    }

    @Test
    fun `blank entries and the literal null are ignored`() {
        assertTrue(PermissionBridge.accessibilityCommands("", component, enable = false).isEmpty())
        assertTrue(PermissionBridge.accessibilityCommands("null", component, enable = false).isEmpty())
        assertEquals(
            listOf(
                "settings put secure enabled_accessibility_services $component",
                "settings put secure accessibility_enabled 1",
            ),
            PermissionBridge.accessibilityCommands(":", component),
        )
    }
}
