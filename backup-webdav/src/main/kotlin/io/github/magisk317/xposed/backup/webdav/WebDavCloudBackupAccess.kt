package io.github.magisk317.xposed.backup.webdav

import android.content.Context
import io.github.magisk317.xposed.backup.BackupPayloadSource
import io.github.magisk317.xposed.backup.BackupSource
import io.github.magisk317.xposed.backup.CloudBackupMeta
import io.github.magisk317.xposed.backup.CloudBackupProviderResolution
import io.github.magisk317.xposed.backup.CloudBackupProviderResolver
import io.github.magisk317.xposed.backup.CloudBackupSettingsStore
import io.github.magisk317.xposed.logging.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-facing facade over the WebDAV backup stack: persists endpoint
 * configuration through the kit store and runs provider operations through the
 * kit resolver, which re-reads the persisted configuration on every call.
 * Consumers that keep their own bridge/UI types wrap this class instead of
 * reimplementing the resolve-and-cast loop.
 *
 * Config persistence touches the Keystore and SharedPreferences; hosts should
 * call it from a background scope (the shared UI model and the coordinator do).
 */
class WebDavCloudBackupAccess(
    private val payloadSource: BackupPayloadSource,
    defaultRemotePath: String,
) {
    val resolver: CloudBackupProviderResolver =
        WebDavOnlyCloudBackupProviderResolver(payloadSource, defaultRemotePath)

    private val defaultRemotePath = defaultRemotePath

    fun webDavConfig(context: Context): WebDavConfig? =
        WebDavConfigStore.getConfig(context)

    fun saveWebDavConfig(context: Context, config: WebDavConfig): WebDavConfig? {
        val normalized = config.normalized() ?: return null
        if (!WebDavConfigStore.saveConfig(context, normalized)) {
            XLog.w("WebDAV config could not be stored securely.")
            return null
        }
        return normalized
    }

    fun removeWebDavConfig(context: Context) {
        WebDavConfigStore.removeConfig(context)
    }

    suspend fun testWebDavConnection(context: Context, config: WebDavConfig): Boolean {
        val normalized = config.normalized() ?: return false
        return withContext(Dispatchers.IO) {
            WebDavBackupManager(context.applicationContext ?: context, payloadSource)
                .apply { updateConfig(normalized) }
                .testConnection()
                .isSuccess
        }
    }

    suspend fun uploadBackup(context: Context): Boolean =
        runProvider(context) { provider -> provider.uploadBackup().isSuccess } ?: false

    suspend fun listBackups(context: Context): List<CloudBackupMeta>? =
        runProvider(context) { provider -> provider.listBackups().getOrNull() }

    suspend fun restoreBackup(context: Context, backupId: String): Boolean =
        runProvider(context) { provider -> provider.restoreFromBackup(backupId).isSuccess } ?: false

    suspend fun deleteBackup(context: Context, backupId: String): Boolean =
        runProvider(context) { provider -> provider.deleteBackup(backupId).isSuccess } ?: false

    fun isAutoBackupEnabled(context: Context): Boolean =
        CloudBackupSettingsStore.isAutoBackupEnabled(context, BackupSource.WEBDAV)

    suspend fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        CloudBackupSettingsStore.setAutoBackup(context, BackupSource.WEBDAV, enabled)
    }

    private suspend fun <T> runProvider(
        context: Context,
        operation: suspend (WebDavCloudBackupProvider) -> T,
    ): T? {
        val resolution = resolver.resolve(context, BackupSource.WEBDAV)
        val provider = when (resolution) {
            is CloudBackupProviderResolution.Ready -> resolution.provider as? WebDavCloudBackupProvider
            is CloudBackupProviderResolution.Skipped -> {
                XLog.w(message = "WebDAV backup provider skipped: reason=%s", resolution.reason)
                return null
            }
        } ?: run {
            XLog.w("WebDAV backup provider resolved to an unexpected type")
            return null
        }
        return operation(provider)
    }

    private fun WebDavConfig.normalized(): WebDavConfig? {
        val serverUrl = serverUrl.trim()
        val username = username.trim()
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) return null
        return copy(
            serverUrl = serverUrl,
            username = username,
            remotePath = remotePath.trim().ifBlank { defaultRemotePath },
        )
    }
}
