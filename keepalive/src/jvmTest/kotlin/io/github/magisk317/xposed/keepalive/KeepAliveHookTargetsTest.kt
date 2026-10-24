package io.github.magisk317.xposed.keepalive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the shared descriptor table.
 *
 * The descriptors are the part of the hook that silently breaks: a framework refactor
 * renames or reorders a parameter and the hook keeps compiling while its argument
 * indexes now point at different values. These tests pin the descriptors and the
 * index-to-role mapping so such a change fails here instead of in system_server.
 */
class KeepAliveHookTargetsTest {

    @Test
    fun `android 17 targets use psc process state and current kill signature`() {
        val oom = KeepAliveHookTargets.oomApply.first()
        assertEquals("com.android.server.am.psc.OomAdjuster", oom.shape.owner)
        assertEquals(
            listOf("com.android.server.am.psc.ProcessRecordInternal", "boolean"),
            oom.shape.parameterTypes,
        )
        assertEquals("void", oom.shape.returnType)

        val kill = KeepAliveHookTargets.killLocked.first { target ->
            target.shape.parameterTypes.any { type -> type.contains("ApplicationExitInfo") }
        }
        assertEquals("android.app.ApplicationExitInfo\$AnrInfo", kill.shape.parameterTypes[4])
        assertEquals(2, kill.valueIndex)
        assertEquals(3, kill.secondaryValueIndex)
    }

    @Test
    fun `api 33 targets retain the exact parameter roles`() {
        val appIdle = KeepAliveHookTargets.appIdle.single()
        val forceIdle = KeepAliveHookTargets.forceIdle.single()
        val packageKill = KeepAliveHookTargets.packageKill.single()

        assertEquals(listOf(1, 2, 2), KeepAliveHookTargets.standbyBucket.map { it.valueIndex })
        assertTrue(KeepAliveHookTargets.standbyBucket.all { it.packageIndex == 0 })
        assertEquals(0, packageKill.packageIndex)
        assertEquals(10, packageKill.valueIndex)
        assertEquals(11, packageKill.secondaryValueIndex)
        assertEquals(1, appIdle.valueIndex)
        assertEquals(2, forceIdle.valueIndex)
    }

    /** The Android 17 layout is tried before the legacy one. */
    @Test
    fun `oom owners resolve psc first`() {
        assertEquals(
            listOf(
                "com.android.server.am.psc.OomAdjuster",
                "com.android.server.am.OomAdjuster",
            ),
            KeepAliveHookTargets.oomApplyOwners,
        )
    }

    /** Each table entry must name the capability it installs. */
    @Test
    fun `every target declares its capability`() {
        val all = KeepAliveHookTargets.oomApply +
            KeepAliveHookTargets.killLocked +
            KeepAliveHookTargets.packageKill +
            KeepAliveHookTargets.standbyBucket +
            KeepAliveHookTargets.appIdle +
            KeepAliveHookTargets.forceIdle
        assertTrue(all.isNotEmpty())
        all.forEach { target ->
            assertTrue(target.shape.owner.isNotBlank())
            assertTrue(target.shape.name.isNotBlank())
        }
        assertTrue(KeepAliveHookTargets.killLocked.all { it.capability == KeepAliveCapability.KILL_GUARD })
        assertTrue(KeepAliveHookTargets.oomApply.all { it.capability == KeepAliveCapability.OOM_APPLY })
        assertEquals(
            KeepAliveCapability.PACKAGE_KILL_GUARD,
            KeepAliveHookTargets.packageKill.single().capability,
        )
        assertEquals(KeepAliveCapability.STANDBY_IDLE, KeepAliveHookTargets.appIdle.single().capability)
        assertEquals(
            KeepAliveCapability.STANDBY_FORCE_IDLE,
            KeepAliveHookTargets.forceIdle.single().capability,
        )
    }
}
