package io.github.magisk317.xposed.diagnostics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class DiagnosticExportModeTest {
    @Test
    fun `enabled debug logging requests full diagnostics`() {
        assertEquals(DiagnosticExportMode.FULL, DiagnosticExportMode.fromDebugLogging(debugLoggingEnabled = true))
    }

    @Test
    fun `disabled debug logging requests standard diagnostics`() {
        assertEquals(DiagnosticExportMode.STANDARD, DiagnosticExportMode.fromDebugLogging(debugLoggingEnabled = false))
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
