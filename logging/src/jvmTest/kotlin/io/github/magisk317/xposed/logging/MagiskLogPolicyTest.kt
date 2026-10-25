package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MagiskLogPolicyTest {
    @Test
    fun `an uninstalled policy is release-shaped`() {
        val snapshot = MagiskLogPolicy.evaluate(isDebugBuild = false)

        assertFalse(snapshot.verboseLogging)
        assertTrue(snapshot.sanitizesText)
        assertFalse(snapshot.capturesRootHeavyDiagnostics)
        assertFalse(snapshot.allowsLogExport)
    }

    @Test
    fun `a debug build captures everything`() {
        val snapshot = MagiskLogPolicy.evaluate(isDebugBuild = true)

        assertTrue(snapshot.verboseLogging)
        assertFalse(snapshot.sanitizesText)
        assertTrue(snapshot.capturesRootHeavyDiagnostics)
        assertTrue(snapshot.allowsLogExport)
    }

    @Test
    fun `the plaintext flag drops redaction without widening collection`() {
        val snapshot = MagiskLogPolicy.evaluate(
            isDebugBuild = false,
            allowPlaintextLogs = true,
        )

        assertFalse(snapshot.verboseLogging)
        assertFalse(snapshot.sanitizesText)
        assertFalse(snapshot.capturesRootHeavyDiagnostics)
        assertTrue(snapshot.allowsLogExport)
    }

    @Test
    fun `install pushes the decision into the sanitizer switch`() {
        try {
            assertTrue(MagiskLogPolicy.install(isDebugBuild = false).sanitizesText)
            assertTrue(LogSanitizerConfig.isEnabled())

            MagiskLogPolicy.install(isDebugBuild = true)
            assertFalse(LogSanitizerConfig.isEnabled())
            assertEquals(MagiskLogPolicy.current(), MagiskLogPolicy.evaluate(isDebugBuild = true))
        } finally {
            MagiskLogPolicy.install(isDebugBuild = false)
            assertTrue(LogSanitizerConfig.isEnabled())
        }
    }

    @Test
    fun `a user switch raises redaction but never lowers it below the build`() {
        try {
            MagiskLogPolicy.install(isDebugBuild = false)
            LogSanitizerConfig.applyUserSwitch(userWantsSanitized = false)
            assertTrue(LogSanitizerConfig.isEnabled())

            MagiskLogPolicy.install(isDebugBuild = true)
            LogSanitizerConfig.applyUserSwitch(userWantsSanitized = false)
            assertFalse(LogSanitizerConfig.isEnabled())

            LogSanitizerConfig.applyUserSwitch(userWantsSanitized = true)
            assertTrue(LogSanitizerConfig.isEnabled())
        } finally {
            MagiskLogPolicy.install(isDebugBuild = false)
            assertTrue(LogSanitizerConfig.isEnabled())
        }
    }

    @Test
    fun `retention falls back to two days and keeps an explicit choice`() {
        assertEquals(MagiskLogPolicy.DEFAULT_LOG_RETENTION_DAYS, MagiskLogPolicy.retentionDays(0))
        assertEquals(MagiskLogPolicy.DEFAULT_LOG_RETENTION_DAYS, MagiskLogPolicy.retentionDays(-3))
        assertEquals(MagiskLogPolicy.MIN_LOG_RETENTION_DAYS, MagiskLogPolicy.retentionDays(1))
        assertEquals(7, MagiskLogPolicy.retentionDays(7))
        assertEquals(30, MagiskLogPolicy.retentionDays(30))
    }
}
