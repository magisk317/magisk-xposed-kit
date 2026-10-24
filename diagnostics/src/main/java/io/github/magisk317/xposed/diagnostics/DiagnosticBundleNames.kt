package io.github.magisk317.xposed.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one naming scheme for every diagnostic artifact, keyed by a single product token.
 *
 * Bundle name, staging directory and export directory used to be three independent literals
 * per repository, and they drifted: one product offered `xinyi_logs_<ts>.zip` in the save
 * dialog while writing `relay_logs_<ts>.zip` on disk, another kept its bundles in a directory
 * named after a different app. Deriving all three from the token makes that impossible.
 *
 * One product, one token — MiPush exports the manager and the xmsf runtime through the same
 * bundle because a user triggers a single export; the two package identities are stamped
 * inside it instead of splitting the archive in two.
 */
object DiagnosticBundleNames {
    const val FILE_SUFFIX = ".zip"
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    /**
     * The one timestamp format shared by the file written on disk and any save dialog that
     * offers it, so the two can never disagree.
     */
    @JvmOverloads
    fun exportTimestamp(timeMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(timeMs))

    fun normalize(product: String): String =
        product.trim().lowercase(Locale.US).trim('_', '-', ' ').ifBlank { "app" }

    fun exportFilePrefix(product: String): String = "${normalize(product)}_logs_"

    fun stagingDirPrefix(product: String): String = ".tmp_${normalize(product)}_logs_"

    fun privateExportDirName(product: String): String = "${normalize(product)}_logs"

    /** The name a bundle is saved under — use this for a save dialog too, so it can never disagree with the file on disk. */
    fun bundleFileName(product: String, timestamp: String): String =
        "${exportFilePrefix(product)}$timestamp$FILE_SUFFIX"

    fun stagingDirName(product: String, timestamp: String): String =
        "${stagingDirPrefix(product)}$timestamp"
}
