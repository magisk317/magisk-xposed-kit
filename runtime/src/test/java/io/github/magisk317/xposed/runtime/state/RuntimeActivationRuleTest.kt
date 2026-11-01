package io.github.magisk317.xposed.runtime.state

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeActivationRuleTest {

    @Test
    fun `every proof of life activates the module on its own`() {
        assertTrue(RuntimeActivationRule.isActivated(RuntimeStatusInputs(true, false)))
        assertTrue(RuntimeActivationRule.isActivated(RuntimeStatusInputs(false, false, hasServiceBind = true)))
        assertTrue(
            RuntimeActivationRule.isActivated(
                RuntimeStatusInputs(false, false, hasFrameworkHookTarget = true),
            ),
        )
        assertTrue(RuntimeActivationRule.isActivated(RuntimeStatusInputs(false, true)))
    }

    @Test
    fun `no proof of life leaves the module inactive`() {
        assertFalse(RuntimeActivationRule.isActivated(RuntimeStatusInputs(false, false)))
    }

    @Test
    fun `a bind from this boot activates the module`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(lastServiceBindAtMs = 5_000L),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertTrue(inputs.hasServiceBind)
        assertTrue(RuntimeActivationRule.isActivated(inputs))
    }

    @Test
    fun `a bind from a previous boot proves nothing`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(lastServiceBindAtMs = 500L),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertFalse(inputs.hasServiceBind)
        assertFalse(RuntimeActivationRule.isActivated(inputs))
    }

    @Test
    fun `a dead framework stops the bind from counting`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(
                lastServiceBindAtMs = 5_000L,
                lastServiceDeathAtMs = 6_000L,
            ),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertFalse(inputs.hasServiceBind)
        assertFalse(RuntimeActivationRule.isActivated(inputs))
    }

    @Test
    fun `a later bind outlives an earlier death`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(
                lastServiceBindAtMs = 7_000L,
                lastServiceDeathAtMs = 6_000L,
            ),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertTrue(inputs.hasServiceBind)
    }

    @Test
    fun `framework hook targets from this boot activate the module`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(
                lastFrameworkHookAtMs = 5_000L,
                frameworkHookTargets = listOf(
                    FrameworkHookTargetStatus(processName = "system_server", uid = 1000),
                ),
            ),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertTrue(inputs.hasFrameworkHookTarget)
        assertTrue(RuntimeActivationRule.isActivated(inputs))
    }

    @Test
    fun `framework hook targets from a previous boot prove nothing`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(
                lastFrameworkHookAtMs = 500L,
                frameworkHookTargets = listOf(
                    FrameworkHookTargetStatus(processName = "system_server", uid = 1000),
                ),
            ),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertFalse(inputs.hasFrameworkHookTarget)
        assertFalse(RuntimeActivationRule.isActivated(inputs))
    }

    @Test
    fun `a heartbeat from this boot activates the module`() {
        val inputs = RuntimeActivationRule.evaluate(
            snapshot = RuntimeDiagnosticsSnapshot(lastHookAtMs = 5_000L),
            bootStartAtMs = 1_000L,
            runtimeConnected = false,
        )

        assertTrue(inputs.hasHookHeartbeat)
        assertTrue(RuntimeActivationRule.isActivated(inputs))
    }
}
