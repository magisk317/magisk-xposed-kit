package io.github.magisk317.xposed.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Product-agnostic archive pipeline for diagnostic log bundles.
 *
 * Collectors remain product-specific; this helper only owns staging, ZIP packaging,
 * directory copy/clear helpers, and share-intent construction.
 */
object DiagnosticArchive {
    private const val ZIP_MIME_TYPE = "application/zip"

    data class BundleResult(
        val file: File?,
        val details: String,
    )

    data class ClearResult(
        val success: Boolean,
        val details: String,
    )

    fun buildBundle(
        context: Context,
        timestamp: String,
        exportDir: File,
        exportFilePrefix: String,
        stagingDirPrefix: String,
        deletePath: (File) -> Boolean = ::deleteRecursively,
        collect: (File, MutableList<String>) -> Unit,
        onInfo: (String) -> Unit = {},
        onWarning: (String) -> Unit = {},
        onError: (String, Throwable?) -> Unit = { _, _ -> },
        makeWorldReadable: Boolean = true,
        worldReadableParentDepth: Int = 2,
    ): BundleResult {
        if (!ensureDirectory(exportDir, recreateWhenFile = true, onWarning = onWarning)) {
            val details = "export root unavailable: ${exportDir.absolutePath}"
            onError(details, null)
            return BundleResult(null, details)
        }
        val stagingDir = File(exportDir, "$stagingDirPrefix$timestamp")
        if (stagingDir.exists() && !deletePath(stagingDir)) {
            val details = "staging cleanup failed: ${stagingDir.absolutePath}"
            onError(details, null)
            return BundleResult(null, details)
        }
        if (!ensureDirectory(stagingDir, recreateWhenFile = true, onWarning = onWarning)) {
            val details = "staging dir unavailable: ${stagingDir.absolutePath}"
            onError(details, null)
            return BundleResult(null, details)
        }

        val details = mutableListOf<String>()
        val result = runCatching {
            collect(stagingDir, details)
            // Always emit a shareable summary-only ZIP, even when no log sources were
            // collected, so UI can offer a normal share flow instead of a false failure.
            File(stagingDir, "summary.txt").writeText(
                buildString {
                    appendLine("Export Time: $timestamp")
                    appendLine("Package: ${context.packageName}")
                    details.forEach { appendLine("- $it") }
                },
            )
            val zipFile = File(exportDir, "$exportFilePrefix$timestamp.zip")
            zipDirectory(stagingDir, zipFile, onWarning)
            if (makeWorldReadable) {
                setFileWorldReadable(zipFile, parentDepth = worldReadableParentDepth)
            }
            val summary = details.joinToString("; ")
            onInfo(
                "buildLogBundle success: file=${zipFile.absolutePath} size=${zipFile.length()} details=$summary",
            )
            BundleResult(zipFile, summary)
        }.getOrElse { error ->
            onError("buildLogBundle failed", error)
            BundleResult(null, error.message ?: error.javaClass.simpleName)
        }
        runCatching {
            if (!deletePath(stagingDir)) {
                onWarning("Failed to cleanup staging dir: ${stagingDir.absolutePath}")
            }
        }
        return result
    }

    fun buildShareIntent(
        context: Context,
        file: File,
        authority: String,
        onInfo: (String) -> Unit = {},
        onWarning: (String) -> Unit = {},
    ): Intent {
        require(file.exists() && file.isFile && file.canRead()) {
            "share file unavailable: ${file.absolutePath}"
        }
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ZIP_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        val targets = context.packageManager.queryIntentActivities(intent, 0)
        targets.forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@forEach
            runCatching {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure {
                onWarning(
                    "grantUriPermission failed: pkg=$packageName uri=$uri " +
                        "err=${it.message ?: it.javaClass.simpleName}",
                )
            }
        }
        onInfo(
            "buildShareIntent: file=${file.absolutePath} size=${file.length()} uri=$uri targets=${targets.size}",
        )
        return intent
    }

    fun clearDirectories(
        targets: List<Pair<String, File>>,
        deletePath: (File) -> Boolean = ::deleteRecursively,
        onWarning: (String) -> Unit = {},
    ): ClearResult {
        var success = true
        val details = targets.map { (name, dir) ->
            val cleared = clearDirectoryContents(dir, deletePath, onWarning)
            if (!cleared) success = false
            "$name ${if (cleared) "cleared" else "clear failed"}"
        }
        return ClearResult(success, details.joinToString("; "))
    }

    fun copyDirectory(
        source: File,
        target: File,
        includeFile: (File) -> Boolean = { true },
        onWarning: (String) -> Unit = {},
    ) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).path
            val destination = if (relative.isEmpty()) target else File(target, relative)
            if (file.isDirectory) {
                ensureDirectory(destination, recreateWhenFile = true, onWarning = onWarning)
            } else if (file.exists() && includeFile(file)) {
                val parent = destination.parentFile ?: return@forEach
                if (!ensureDirectory(parent, recreateWhenFile = true, onWarning = onWarning)) {
                    onWarning("Skip copy due to invalid parent dir: ${destination.absolutePath}")
                    return@forEach
                }
                runCatching { file.copyTo(destination, overwrite = true) }
                    .onFailure {
                        onWarning(
                            "Skip copy file failed: src=${file.absolutePath} dst=${destination.absolutePath} " +
                                "err=${it.message ?: it.javaClass.simpleName}",
                        )
                    }
            }
        }
    }

    fun ensureDirectory(
        dir: File,
        recreateWhenFile: Boolean,
        onWarning: (String) -> Unit = {},
    ): Boolean {
        if (dir.exists()) {
            if (dir.isDirectory) return true
            if (!recreateWhenFile) return false
            if (!dir.delete()) {
                onWarning("Failed to delete non-directory path: ${dir.absolutePath}")
                return false
            }
        }
        if (!dir.mkdirs() && !dir.exists()) {
            onWarning("Failed to mkdirs for path: ${dir.absolutePath}")
            return false
        }
        return dir.isDirectory
    }

    fun deleteRecursively(target: File): Boolean {
        if (!target.exists()) return true
        return target.deleteRecursively() || !target.exists()
    }

    fun setFileWorldReadable(file: File, parentDepth: Int) {
        if (!file.exists()) return
        var current: File? = file
        repeat(parentDepth + 1) {
            current?.setReadable(true, false)
            current?.setExecutable(true, false)
            current = current?.parentFile
        }
    }

    private fun zipDirectory(sourceDir: File, outputZip: File, onWarning: (String) -> Unit) {
        val parent = outputZip.parentFile
        if (parent != null && !ensureDirectory(parent, recreateWhenFile = true, onWarning = onWarning)) {
            throw IOException("zip output dir unavailable: ${parent.absolutePath}")
        }
        FileOutputStream(outputZip).use { output ->
            ZipOutputStream(output).use { zip ->
                sourceDir.walkTopDown().filter(File::isFile).forEach { file ->
                    runCatching {
                        zip.putNextEntry(ZipEntry(file.relativeTo(sourceDir).invariantSeparatorsPath))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }.onFailure {
                        onWarning(
                            "Skip zipping unreadable file: ${file.absolutePath} " +
                                "err=${it.message ?: it.javaClass.simpleName}",
                        )
                    }
                }
            }
        }
    }

    private fun clearDirectoryContents(
        dir: File,
        deletePath: (File) -> Boolean,
        onWarning: (String) -> Unit,
    ): Boolean = runCatching {
        if (!ensureDirectory(dir, recreateWhenFile = true, onWarning = onWarning)) return@runCatching false
        var deleted = true
        dir.listFiles().orEmpty().forEach { child ->
            if (!deletePath(child)) {
                deleted = false
                onWarning("Failed to delete log child: ${child.absolutePath}")
            }
        }
        deleted && ensureDirectory(dir, recreateWhenFile = true, onWarning = onWarning)
    }.getOrElse {
        onWarning(
            "Failed to clear directory: ${dir.absolutePath} err=${it.message ?: it.javaClass.simpleName}",
        )
        false
    }
}
