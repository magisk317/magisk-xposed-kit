package io.github.magisk317.xposed.keepalive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TARGET = "com.example.target"

class KeepAlivePolicyTest {

    // ── oom_adj ─────────────────────────────────────────────────────────────

    @Test
    fun `oom_adj is promoted only for the target process`() {
        assertNull(KeepAlivePolicy.desiredOomAdj(true, true, TARGET, "other", 500))
        assertEquals(0, KeepAlivePolicy.desiredOomAdj(true, true, TARGET, TARGET, 500))
    }

    @Test
    fun `oom_adj is never promoted when already foreground`() {
        assertNull(KeepAlivePolicy.desiredOomAdj(true, true, TARGET, TARGET, 0))
    }

    @Test
    fun `oom_adj respects the switch and the ready flag`() {
        assertNull(KeepAlivePolicy.desiredOomAdj(false, true, TARGET, TARGET, 500))
        assertNull(KeepAlivePolicy.desiredOomAdj(true, false, TARGET, TARGET, 500))
    }

    // ── standby bucket ─────────────────────────────────────────────────────

    @Test
    fun `restricted buckets are promoted to ACTIVE`() {
        for (bucket in listOf(20, 30, 40, 45, 50)) {
            assertEquals(10, KeepAlivePolicy.desiredStandbyBucket(true, true, TARGET, TARGET, bucket))
        }
    }

    /**
     * EXEMPTED (5) ranks above ACTIVE (10). Promoting it would demote an app the user
     * explicitly exempted from battery optimisation. The previous per-repo hook forced
     * ACTIVE on every bucket it did not recognise, including this one.
     */
    @Test
    fun `exempted bucket is never demoted to ACTIVE`() {
        assertNull(KeepAlivePolicy.desiredStandbyBucket(true, true, TARGET, TARGET, 5))
    }

    @Test
    fun `standby bucket ignores other packages`() {
        assertNull(KeepAlivePolicy.desiredStandbyBucket(true, true, TARGET, "other", 45))
    }

    // ── idle state ─────────────────────────────────────────────────────────

    @Test
    fun `forced idle is cleared only for the target`() {
        assertEquals(false, KeepAlivePolicy.desiredIdleState(true, true, TARGET, TARGET, true))
        assertNull(KeepAlivePolicy.desiredIdleState(true, true, TARGET, "other", true))
        assertNull(KeepAlivePolicy.desiredIdleState(true, true, TARGET, TARGET, false))
    }

    // ── kill suppression ───────────────────────────────────────────────────

    @Test
    fun `automatic kill of the live target record is suppressed`() {
        assertTrue(
            KeepAlivePolicy.shouldSuppressKill(
                enabled = true,
                target = true,
                targetProcess = TARGET,
                processName = TARGET,
                reason = 13,
                subReason = 2,
                nameMapping = true,
                pidMapping = true,
            ),
        )
    }

    @Test
    fun `manual kill reasons are never suppressed`() {
        assertFalse(
            KeepAlivePolicy.shouldSuppressKill(
                enabled = true,
                target = true,
                targetProcess = TARGET,
                processName = TARGET,
                reason = 1,
                subReason = 2,
                nameMapping = true,
                pidMapping = true,
            ),
        )
    }

    /**
     * A stale record no longer describes a live process. Sparing it would protect the
     * wrong process while the real one stays killable.
     */
    @Test
    fun `stale records are never spared`() {
        assertFalse(
            KeepAlivePolicy.shouldSuppressKill(
                enabled = true,
                target = true,
                targetProcess = TARGET,
                processName = TARGET,
                reason = 13,
                subReason = 2,
                nameMapping = true,
                pidMapping = false,
            ),
        )
        assertFalse(
            KeepAlivePolicy.shouldSuppressKill(
                enabled = true,
                target = true,
                targetProcess = TARGET,
                processName = TARGET,
                reason = 13,
                subReason = 2,
                nameMapping = false,
                pidMapping = true,
            ),
        )
    }

    @Test
    fun `kill suppression is disabled until a snapshot has been read`() {
        assertFalse(
            KeepAlivePolicy.shouldSuppressKill(
                enabled = false,
                target = true,
                targetProcess = TARGET,
                processName = TARGET,
                reason = 13,
                subReason = 2,
                nameMapping = true,
                pidMapping = true,
            ),
        )
    }

    // ── package kill suppression ──────────────────────────────────────────

    @Test
    fun `package kill is suppressed only for the narrow automatic path`() {
        assertTrue(
            KeepAlivePolicy.shouldSuppressPackageKill(
                enabled = true,
                target = true,
                targetPackage = TARGET,
                packageName = TARGET,
                reason = 13,
                subReason = 6,
                callerWillRestart = false,
                doit = true,
                evenPersistent = false,
                setRemoved = false,
                uninstalling = false,
            ),
        )
        // An uninstall must always proceed.
        assertFalse(
            KeepAlivePolicy.shouldSuppressPackageKill(
                enabled = true,
                target = true,
                targetPackage = TARGET,
                packageName = TARGET,
                reason = 13,
                subReason = 6,
                callerWillRestart = false,
                doit = true,
                evenPersistent = false,
                setRemoved = false,
                uninstalling = true,
            ),
        )
        // A kill that only stages the action (doit=false) must not be swallowed.
        assertFalse(
            KeepAlivePolicy.shouldSuppressPackageKill(
                enabled = true,
                target = true,
                targetPackage = TARGET,
                packageName = TARGET,
                reason = 13,
                subReason = 6,
                callerWillRestart = false,
                doit = false,
                evenPersistent = false,
                setRemoved = false,
                uninstalling = false,
            ),
        )
    }
}
