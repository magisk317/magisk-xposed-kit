package io.github.magisk317.xposed.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VerboseLogEnableTrackerTest {

    @BeforeEach
    fun reset() {
        VerboseLogEnableTracker.onVerboseLogToggled(false)
    }

    @Test
    fun freshTrackerDoesNotBlockExport() {
        assertNull(VerboseLogEnableTracker.shouldBlockExport())
    }

    @Test
    fun recentlyEnabledVerboseLogBlocksExport() {
        VerboseLogEnableTracker.onVerboseLogToggled(true)
        assertEquals("uikit_log_export_need_reproduce", VerboseLogEnableTracker.shouldBlockExport())
    }

    @Test
    fun disablingClearsTheBlock() {
        VerboseLogEnableTracker.onVerboseLogToggled(true)
        VerboseLogEnableTracker.onVerboseLogToggled(false)
        assertNull(VerboseLogEnableTracker.shouldBlockExport())
    }

    @Test
    fun checkPreExportRequiresRootWhenSupplierSaysNo() {
        assertEquals(
            "uikit_log_export_need_root",
            VerboseLogEnableTracker.checkPreExport(verboseLogEnabled = true) { false },
        )
    }

    @Test
    fun checkPreExportRequiresVerboseLogWhenDisabled() {
        assertEquals(
            "uikit_log_export_need_verbose_log",
            VerboseLogEnableTracker.checkPreExport(verboseLogEnabled = false) { true },
        )
    }

    @Test
    fun checkPreExportDefaultsToNoRootGate() {
        VerboseLogEnableTracker.onVerboseLogToggled(true)
        assertEquals(
            "uikit_log_export_need_reproduce",
            VerboseLogEnableTracker.checkPreExport(verboseLogEnabled = true),
        )
    }
}
