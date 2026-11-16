package io.github.magisk317.xposed.backup.webdav

import android.content.Context
import io.github.magisk317.xposed.backup.BackupPayloadSource
import io.github.magisk317.xposed.backup.BackupSource
import io.github.magisk317.xposed.backup.CloudBackupMeta
import io.github.magisk317.xposed.logging.MagiskOtel
import io.github.magisk317.xposed.logging.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebDAV transport for the cloud backup stack. What goes into the archive and
 * how a restore is applied is injected through [BackupPayloadSource], so this
 * manager carries no knowledge of the app's backup format.
 */
class WebDavBackupManager(
    private val context: Context,
    private val payloadSource: BackupPayloadSource,
) {
    private fun emitBackup(
        stage: String,
        statusOk: Boolean,
        reason: String,
        startedAt: Long,
        errorClass: String? = null,
    ) {
        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        val attrs = mutableMapOf(
            "result" to if (statusOk) "ok" else "error",
            "duration_ms" to durationMs.toString(),
            "process" to "app",
            "stage" to stage,
            "reason" to reason,
            "source" to "webdav",
        )
        if (!errorClass.isNullOrBlank()) attrs["error_class"] = errorClass
        MagiskOtel.event(
            name = if (stage.startsWith("restore")) "prefs.restore" else "prefs.backup",
            attributes = attrs,
            statusOk = statusOk,
        )
    }
    // Updated from the UI thread while uploads may run on Dispatchers.IO; the
    // volatile markers keep the pair visible across threads.
    @Volatile
    private var config: WebDavConfig? = null
    @Volatile
    private var client: WebDavClient? = null

    fun updateConfig(newConfig: WebDavConfig) {
        config = newConfig
        client = WebDavClient(newConfig, context.cacheDir)
    }

    fun getConfig(): WebDavConfig? = config

    suspend fun uploadBackup(): Result<CloudBackupMeta> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val currentClient = client ?: run {
            emitBackup(stage = "webdav_upload", statusOk = false, reason = "not_configured", startedAt = startedAt)
            return@withContext Result.failure(IllegalStateException("WebDAV not configured"))
        }
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            // The payload source owns the archive identity: upload under the
            // produced file's own name so every app's backups stay recognisable
            // (and scannable by prefix) in a shared WebDAV folder.
            val tempFile = payloadSource.produceBackup(context)
            val fileName = tempFile.name
            try {
                val fileSize = tempFile.length()
                if (!tempFile.isFile || fileSize <= 0L) {
                    throw IllegalStateException("Local backup file missing or empty")
                }
                XLog.i(message = "WebDAV backup export ready: name=%s size=%d", fileName, fileSize)

                currentClient.uploadFile(tempFile, fileName).getOrThrow()
                XLog.i(message = "WebDAV backup upload success: name=%s size=%d", fileName, fileSize)

                CloudBackupMeta(
                    id = fileName,
                    name = fileName,
                    size = fileSize,
                    modifiedTime = timestamp,
                    source = BackupSource.WEBDAV,
                )
            } finally {
                if (tempFile.exists()) {
                    val deleted = tempFile.delete()
                    XLog.i(message = "WebDAV backup temp cleanup: name=%s deleted=%s", fileName, deleted)
                }
            }
        }.also { result ->
            emitBackup(
                stage = "webdav_upload",
                statusOk = result.isSuccess,
                reason = if (result.isSuccess) "uploaded" else "upload_failed",
                errorClass = result.exceptionOrNull()?.javaClass?.simpleName,
                startedAt = startedAt,
            )
        }
    }

    suspend fun listBackups(): Result<List<CloudBackupMeta>> = withContext(Dispatchers.IO) {
        val currentClient = client ?: return@withContext Result.failure(IllegalStateException("WebDAV not configured"))
        runCatching {
            val files = currentClient.listFiles().getOrThrow()
            val prefix = payloadSource.archivePrefix
            files.filter { !it.isDirectory && (prefix.isBlank() || it.name.startsWith(prefix)) }
                .map { file ->
                    CloudBackupMeta(
                        id = file.name,
                        name = file.name,
                        size = file.size,
                        modifiedTime = file.lastModified,
                        source = BackupSource.WEBDAV,
                    )
                }
        }
    }

    suspend fun downloadBackup(fileName: String): Result<File> = withContext(Dispatchers.IO) {
        val currentClient = client ?: return@withContext Result.failure(IllegalStateException("WebDAV not configured"))
        runCatching {
            currentClient.downloadFile(fileName).getOrThrow()
        }
    }

    suspend fun deleteBackup(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentClient = client ?: return@withContext Result.failure(IllegalStateException("WebDAV not configured"))
        runCatching {
            currentClient.deleteFile(fileName).getOrThrow()
        }
    }

    suspend fun restoreFromBackup(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        runCatching {
            val tempFile = downloadBackup(fileName).getOrThrow()
            try {
                payloadSource.applyRestore(context, tempFile)
            } finally {
                tempFile.delete()
            }
        }.also { result ->
            emitBackup(
                stage = "webdav_restore",
                statusOk = result.isSuccess,
                reason = if (result.isSuccess) "restored" else "restore_failed",
                errorClass = result.exceptionOrNull()?.javaClass?.simpleName,
                startedAt = startedAt,
            )
        }
    }

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val currentClient = client ?: run {
            emitBackup(stage = "webdav_test", statusOk = false, reason = "not_configured", startedAt = startedAt)
            return@withContext Result.failure(IllegalStateException("WebDAV not configured"))
        }
        runCatching {
            currentClient.ensureDirectory().getOrThrow()
        }.also { result ->
            emitBackup(
                stage = "webdav_test",
                statusOk = result.isSuccess,
                reason = if (result.isSuccess) "connected" else "test_failed",
                errorClass = result.exceptionOrNull()?.javaClass?.simpleName,
                startedAt = startedAt,
            )
        }
    }
}
