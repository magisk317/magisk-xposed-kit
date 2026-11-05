package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MagiskLogPolicyTest {
    @Test
    fun `an uninstalled policy is release-shaped`() {
        val snapshot = MagiskLogPolicy.evaluate(isDiagnosticBuild = false)

        assertFalse(snapshot.loggingEnabled)
        assertFalse(snapshot.verboseLogging)
        assertTrue(snapshot.sanitizesText)
        assertFalse(snapshot.capturesRootHeavyDiagnostics)
        assertFalse(snapshot.allowsLogExport)
    }

    @Test
    fun `a diagnostic build captures everything`() {
        val snapshot = MagiskLogPolicy.evaluate(isDiagnosticBuild = true)

        assertTrue(snapshot.loggingEnabled)
        assertTrue(snapshot.verboseLogging)
        assertFalse(snapshot.sanitizesText)
        assertTrue(snapshot.capturesRootHeavyDiagnostics)
        assertTrue(snapshot.allowsLogExport)
    }

    @Test
    fun `install pushes the decision into the sanitizer switch`() {
        try {
            assertTrue(MagiskLogPolicy.install(isDiagnosticBuild = false).sanitizesText)
            assertTrue(LogSanitizerConfig.isEnabled())

            MagiskLogPolicy.install(isDiagnosticBuild = true)
            assertFalse(LogSanitizerConfig.isEnabled())
            assertEquals(MagiskLogPolicy.current(), MagiskLogPolicy.evaluate(isDiagnosticBuild = true))
        } finally {
            MagiskLogPolicy.install(isDiagnosticBuild = false)
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
