package io.github.magisk317.xposed.diagnostics

import io.github.magisk317.xposed.logging.MagiskLogPolicy
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagnosticArchiveTest {
    @TempDir
    lateinit var tempDir: File

    private val identity = DiagnosticBundleIdentity.of(
        product = "smscode",
        packageName = "github.tornaco.android.thanatos",
        versionName = "3.2.1",
        versionCode = "32100",
        distributionChannel = "beta",
        commitSha = "8417b2ef3",
    )

    @AfterEach
    fun resetPolicy() {
        MagiskLogPolicy.install(isDebugBuild = false)
    }

    private fun exportWith(collect: (File, MutableList<String>) -> Unit): DiagnosticArchive.BundleResult {
        val exportDir = File(tempDir, "export").apply { mkdirs() }
        return DiagnosticArchive.buildBundle(
            identity = identity,
            timestamp = "fixed",
            exportDir = exportDir,
            exportFilePrefix = DiagnosticBundleNames.exportFilePrefix("smscode"),
            stagingDirPrefix = DiagnosticBundleNames.stagingDirPrefix("smscode"),
            collect = collect,
            makeWorldReadable = false,
        )
    }

    private fun zipEntries(zip: File): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(FileInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes().decodeToString()
                input.closeEntry()
            }
        }
        return entries
    }

    @Test
    fun copyDirectory_appliesFilterAndPreservesPaths() {
        val source = File(tempDir, "source").apply { mkdirs() }
        File(source, "nested").mkdirs()
        File(source, "keep.jsonl").writeText("one")
        File(source, "nested/skip.txt").writeText("two")
        val target = File(tempDir, "target")

        DiagnosticArchive.copyDirectory(source, target, includeFile = { it.extension == "jsonl" })

        assertEquals("one", File(target, "keep.jsonl").readText())
        assertFalse(File(target, "nested/skip.txt").exists())
    }

    @Test
    fun clearDirectories_recreatesTargets() {
        val logs = File(tempDir, "logs").apply { mkdirs() }
        File(logs, "runtime.jsonl").writeText("entry")

        val result = DiagnosticArchive.clearDirectories(listOf("log" to logs))

        assertTrue(result.success)
        assertTrue(logs.isDirectory)
        assertTrue(logs.listFiles().isNullOrEmpty())
    }

    @Test
    fun clearDirectories_reportsDeleteFailure() {
        val logs = File(tempDir, "logs").apply { mkdirs() }
        File(logs, "runtime.jsonl").writeText("entry")

        val result = DiagnosticArchive.clearDirectories(
            targets = listOf("log" to logs),
            deletePath = { false },
        )

        assertFalse(result.success)
        assertEquals("log clear failed", result.details)
    }

    @Test
    fun buildBundle_producesShareableZipWhenNoLogsCollected() {
        MagiskLogPolicy.install(isDebugBuild = true)

        val result = exportWith { _, details -> details += "app log missing" }

        val zip = result.file
        assertNotNull(zip)
        assertTrue(zip!!.isFile && zip.length() > 0)
        assertEquals(File(tempDir, "export/smscode_logs_fixed.zip"), zip)
        assertTrue(result.details.contains("app log missing"))
        assertTrue(tempDir.resolve("export").listFiles().orEmpty().none { it.name.startsWith(".tmp_") })
    }

    @Test
    fun buildBundle_stopsWhenStaleStagingCannotBeDeleted() {
        MagiskLogPolicy.install(isDebugBuild = true)
        val exportDir = File(tempDir, "export").apply { mkdirs() }
        File(exportDir, ".tmp_smscode_logs_fixed").mkdirs()
        var collected = false

        val result = DiagnosticArchive.buildBundle(
            identity = identity,
            timestamp = "fixed",
            exportDir = exportDir,
            exportFilePrefix = DiagnosticBundleNames.exportFilePrefix("smscode"),
            stagingDirPrefix = DiagnosticBundleNames.stagingDirPrefix("smscode"),
            deletePath = { false },
            collect = { _, _ -> collected = true },
            makeWorldReadable = false,
        )

        assertFalse(collected)
        assertEquals(null, result.file)
        assertTrue(result.details.startsWith("staging cleanup failed:"))
    }

    @Test
    fun `a release build refuses to export`() {
        MagiskLogPolicy.install(isDebugBuild = false)
        var collected = false

        val result = exportWith { _, _ -> collected = true }

        assertFalse(collected)
        assertEquals(null, result.file)
        assertTrue(result.details.contains("log export disabled"))
        assertTrue(tempDir.resolve("export").listFiles().orEmpty().none { it.name.endsWith(".zip") })
    }

    @Test
    fun `a verbose build says so instead of silently exporting raw text`() {
        MagiskLogPolicy.install(isDebugBuild = true)

        val result = exportWith { stagingDir, _ ->
            File(stagingDir, "logcat").mkdirs()
            File(stagingDir, "logcat/logcat_all.txt").writeText("from=13800138000")
        }

        val summary = zipEntries(result.file!!).getValue("summary.txt")
        assertTrue(summary.contains("sanitized: off (plaintext build)"))
        assertTrue(summary.contains("Log Policy: mode=FULL verbose=true sanitized=false"))
    }

    @Test
    fun `every bundle states which device and build produced it`() {
        MagiskLogPolicy.install(isDebugBuild = true)

        val result = exportWith { _, _ -> }

        val entries = zipEntries(result.file!!)
        val summary = entries.getValue("summary.txt")
        assertTrue(summary.contains("Product: smscode"))
        assertTrue(summary.contains("channel=beta commit=8417b2ef3"))
        assertTrue(summary.contains("Libraries: kit="))
        assertTrue(summary.contains("Device: "))
        val json = entries.getValue("build_identity.json")
        assertTrue(json.contains("\"distributionChannel\":\"beta\""))
        assertTrue(json.contains("\"product\":\"smscode\""))
        assertTrue(json.contains("\"exportMode\":\"FULL\""))
    }
}
