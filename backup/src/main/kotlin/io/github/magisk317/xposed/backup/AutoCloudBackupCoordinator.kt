package io.github.magisk317.xposed.backup

import android.content.Context
import io.github.magisk317.xposed.logging.MagiskOtel
import io.github.magisk317.xposed.logging.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutoCloudBackupCoordinator(
    private val context: Context,
    private val providerResolver: CloudBackupProviderResolver,
) {
    private val mutex = Mutex()
    private var pendingJob: Job? = null

    fun schedule(scope: CoroutineScope, reason: String) {
        pendingJob?.cancel()
        pendingJob = scope.launch(Dispatchers.IO) {
            delay(AUTO_BACKUP_DEBOUNCE_MS)
            runBackup(reason)
        }
    }

    private suspend fun runBackup(reason: String) {
        val startedAt = System.nanoTime()
        mutex.withLock {
            val settings = CloudBackupSettingsStore.getSettings(context)
            if (!settings.autoBackupEnabled) {
                XLog.i(message = "Auto cloud backup skipped: disabled reason=%s", reason)
                emitBackup(startedAt, result = "skip", reason = "disabled", source = reason)
                return
            }

            val provider = when (val resolution = providerResolver.resolve(context, settings.autoBackupSource)) {
                is CloudBackupProviderResolution.Skipped -> {
                    XLog.w(
                        message = "Auto cloud backup skipped: provider not runnable source=%s reason=%s",
                        settings.autoBackupSource.name,
                        reason,
                    )
                    emitBackup(startedAt, result = "skip", reason = resolution.reason, source = reason)
                    return
                }
                is CloudBackupProviderResolution.Ready -> resolution.provider
            }

            if (!provider.isAvailable()) {
                XLog.w(
                    message = "Auto cloud backup skipped: provider unavailable source=%s reason=%s",
                    settings.autoBackupSource.name,
                    reason,
                )
                emitBackup(
                    startedAt,
                    result = "skip",
                    reason = "provider_unavailable",
                    source = reason,
                    backupSource = settings.autoBackupSource.name,
                )
                return
            }

            XLog.i(
                message = "Auto cloud backup start: source=%s reason=%s",
                settings.autoBackupSource.name,
                reason,
            )
            val result = provider.uploadBackup()
            result.fold(
                onSuccess = { backupId ->
                    XLog.i(
                        message = "Auto cloud backup success: source=%s reason=%s backupId=%s",
                        settings.autoBackupSource.name,
                        reason,
                        backupId,
                    )
                    emitBackup(
                        startedAt,
                        result = "ok",
                        reason = "uploaded",
                        source = reason,
                        backupSource = settings.autoBackupSource.name,
                    )
                },
                onFailure = { error ->
                    XLog.e(
                        message = "Auto cloud backup failed: source=%s reason=%s error=%s",
                        settings.autoBackupSource.name,
                        reason,
                        error.message ?: error.javaClass.simpleName,
                    )
                    emitBackup(
                        startedAt,
                        result = "error",
                        reason = "upload_failed",
                        source = reason,
                        backupSource = settings.autoBackupSource.name,
                        statusOk = false,
                        errorClass = error.javaClass.simpleName,
                    )
                },
            )
        }
    }

    private fun emitBackup(
        startedAt: Long,
        result: String,
        reason: String,
        source: String,
        backupSource: String? = null,
        statusOk: Boolean = true,
        errorClass: String? = null,
    ) {
        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        val attrs = mutableMapOf(
            "result" to result,
            "duration_ms" to durationMs.toString(),
            "process" to "app",
            "stage" to "auto_cloud",
            "reason" to reason,
            "source" to source,
        )
        if (!backupSource.isNullOrBlank()) {
            attrs["backup_source"] = backupSource
        }
        if (!errorClass.isNullOrBlank()) {
            attrs["error_class"] = errorClass
        }
        MagiskOtel.event(name = "prefs.backup", attributes = attrs, statusOk = statusOk)
    }

    private companion object {
        const val AUTO_BACKUP_DEBOUNCE_MS = 1500L
    }
}
