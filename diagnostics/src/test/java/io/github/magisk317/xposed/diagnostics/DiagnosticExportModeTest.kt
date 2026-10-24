package io.github.magisk317.xposed.diagnostics

import io.github.magisk317.xposed.logging.MagiskLogPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticExportModeTest {
    @Test
    fun `a verbose build requests full diagnostics`() {
        val policy = MagiskLogPolicy.evaluate(isDebugBuild = true)

        assertEquals(DiagnosticExportMode.FULL, DiagnosticExportMode.of(policy))
    }

    @Test
    fun `a release build requests standard diagnostics`() {
        val policy = MagiskLogPolicy.evaluate(isDebugBuild = false)

        assertEquals(DiagnosticExportMode.STANDARD, DiagnosticExportMode.of(policy))
    }

    @Test
    fun `a plaintext-only build still stays bounded`() {
        val policy = MagiskLogPolicy.evaluate(
            isDebugBuild = false,
            allowPlaintextLogs = true,
        )

        assertEquals(DiagnosticExportMode.STANDARD, DiagnosticExportMode.of(policy))
    }

    @Test
    fun `current mode follows the installed policy`() {
        try {
            MagiskLogPolicy.install(isDebugBuild = true)

            assertEquals(DiagnosticExportMode.FULL, DiagnosticExportMode.current())
        } finally {
            MagiskLogPolicy.install(isDebugBuild = false)
        }
    }

    @Test
    fun `standard mode bounds collection cost`() {
        val mode = DiagnosticExportMode.STANDARD
        assertTrue(mode.usesBoundedLogcat)
        assertFalse(mode.capturesRootHeavyDiagnostics)
    }

    @Test
    fun `full mode keeps legacy broad collection`() {
        val mode = DiagnosticExportMode.FULL
        assertFalse(mode.usesBoundedLogcat)
        assertTrue(mode.capturesRootHeavyDiagnostics)
    }
}
