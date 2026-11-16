package io.github.magisk317.xposed.backup.webdav

import android.content.Context
import io.github.magisk317.xposed.backup.BackupPayloadSource
import io.github.magisk317.xposed.backup.BackupSource
import io.github.magisk317.xposed.backup.CloudBackupProviderResolution
import io.github.magisk317.xposed.backup.CloudBackupProviderResolver

/**
 * Single-source (WebDAV) resolver for apps that offer no other cloud backup
 * transport. It owns one long-lived manager/provider pair so repeated
 * resolutions do not rebuild the OkHttp stack, and still re-reads the persisted
 * configuration on every resolution so callers always operate on the current
 * endpoint credentials. The persisted configuration is re-normalized here
 * (trimming plus [defaultRemotePath]) because older builds may have stored it
 * verbatim.
 */
class WebDavOnlyCloudBackupProviderResolver(
    private val payloadSource: BackupPayloadSource,
    private val defaultRemotePath: String,
) : CloudBackupProviderResolver {

    private val lock = Any()

    @Volatile
    private var manager: WebDavBackupManager? = null

    @Volatile
    private var provider: WebDavCloudBackupProvider? = null

    override suspend fun resolve(
        context: Context,
        source: BackupSource,
    ): CloudBackupProviderResolution {
        if (source != BackupSource.WEBDAV) {
            return CloudBackupProviderResolution.Skipped(REASON_UNSUPPORTED_SOURCE)
        }
        val appContext = context.applicationContext ?: context
        val config = WebDavConfigStore.getConfig(appContext)
            ?: return CloudBackupProviderResolution.Skipped(REASON_NOT_CONFIGURED)
        val normalizedConfig = config.copy(
            serverUrl = config.serverUrl.trim(),
            username = config.username.trim(),
            remotePath = config.remotePath.trim().ifBlank { defaultRemotePath },
        )

        val currentManager = synchronized(lock) {
            manager ?: WebDavBackupManager(appContext, payloadSource).also {
                manager = it
                provider = WebDavCloudBackupProvider(appContext, it)
            }
        }
        currentManager.updateConfig(normalizedConfig)
        return CloudBackupProviderResolution.Ready(requireNotNull(provider))
    }

    companion object {
        const val REASON_UNSUPPORTED_SOURCE = "unsupported_source"
        const val REASON_NOT_CONFIGURED = "webdav_not_configured"
    }
}
