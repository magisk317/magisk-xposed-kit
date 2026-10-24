package io.github.magisk317.xposed.diagnostics

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticBundleNamesTest {
    @Test
    fun `one token drives every artifact name`() {
        assertEquals("smscode_logs_", DiagnosticBundleNames.exportFilePrefix("smscode"))
        assertEquals(".tmp_smscode_logs_", DiagnosticBundleNames.stagingDirPrefix("smscode"))
        assertEquals("smscode_logs", DiagnosticBundleNames.privateExportDirName("smscode"))
        assertEquals("smscode_logs_20261021-1130.zip", DiagnosticBundleNames.bundleFileName("smscode", "20261021-1130"))
        assertEquals(
            ".tmp_smscode_logs_20261021-1130",
            DiagnosticBundleNames.stagingDirName("smscode", "20261021-1130"),
        )
    }

    @Test
    fun `the save dialog name is the file name`() {
        val timestamp = "20261021-1130"
        val onDisk = "dir/${DiagnosticBundleNames.exportFilePrefix("relay")}$timestamp.zip"

        assertEquals(
            onDisk.removePrefix("dir/"),
            DiagnosticBundleNames.bundleFileName("relay", timestamp),
        )
    }

    @Test
    fun `tokens are normalized and never empty`() {
        assertEquals("mipush_logs", DiagnosticBundleNames.privateExportDirName(" MiPush "))
        assertEquals("mi-push_logs", DiagnosticBundleNames.privateExportDirName("MI-PUSH"))
        assertEquals("app_logs", DiagnosticBundleNames.privateExportDirName("  -_ "))
    }

    @Test
    fun `manager and runtime share one product token`() {
        // A user triggers a single export, so the archive is named once and both package
        // identities are stamped inside it rather than split across two bundles.
        assertEquals(
            DiagnosticBundleNames.exportFilePrefix("mipush"),
            DiagnosticBundleNames.exportFilePrefix("Mipush"),
        )
    }

    @Test
    fun `the timestamp is locale independent`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de-DE"))
            val stamp = DiagnosticBundleNames.exportTimestamp(
                GregorianCalendar(2026, Calendar.OCTOBER, 21, 11, 30, 0).timeInMillis,
            )

            // Only the shape is asserted: the hour depends on the machine's zone, and a
            // German default locale must not turn the separator into a dot.
            assertTrue(Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}$""").matches(stamp))
        } finally {
            Locale.setDefault(original)
        }
    }
}
