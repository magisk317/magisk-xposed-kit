package io.github.magisk317.xposed.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticBundleIdentityTest {
    private fun identity(
        versionName: String = "3.2.1",
        distributionChannel: String = "",
        commitSha: String = "",
        libraries: Map<String, String> = mapOf("smscode-core" to "3c956f9"),
    ) = DiagnosticBundleIdentity.of(
        product = "smscode",
        packageName = "github.tornaco.android.thanatos",
        versionName = versionName,
        versionCode = "32100",
        distributionChannel = distributionChannel,
        commitSha = commitSha,
        libraryCommitShas = libraries,
    )

    @Test
    fun `missing build stamps degrade to unknown instead of vanishing`() {
        val lines = identity().summaryLines()

        assertTrue(lines.any { it.contains("channel=${DiagnosticBundleIdentity.UNKNOWN}") })
        assertTrue(lines.any { it.contains("commit=${DiagnosticBundleIdentity.UNKNOWN}") })
        assertTrue(lines.none { it.contains("version= (") })
    }

    @Test
    fun `the header carries the fields every product used to disagree about`() {
        val lines = identity(
            distributionChannel = "beta",
            commitSha = "8417b2ef3",
        ).summaryLines().joinToString("\n")

        assertTrue(lines.contains("Product: smscode"))
        assertTrue(lines.contains("version=3.2.1 (32100) channel=beta commit=8417b2ef3"))
        assertTrue(lines.contains("Libraries: kit="))
        assertTrue(lines.contains("smscode-core=3c956f9"))
        assertTrue(lines.contains("Device: "))
        assertTrue(lines.contains("Android "))
    }

    @Test
    fun `json payload flattens the same fields including per-library hashes`() {
        val fields = identity(distributionChannel = "beta", commitSha = "8417b2ef3").jsonFields()

        assertEquals("smscode", fields["product"])
        assertEquals("beta", fields["distributionChannel"])
        assertEquals("8417b2ef3", fields["commitSha"])
        assertEquals("3c956f9", fields["lib_smscode-core"])
        assertEquals(DiagnosticBuildStamps.kitCommitHash, fields["kitCommitSha"])
        assertEquals(DiagnosticBundleIdentity.UNKNOWN, fields["deviceManufacturer"])
    }
}
