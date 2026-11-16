package io.github.magisk317.xposed.backup

import android.content.Context

/**
 * How the auto-backup coordinator obtains a provider for a source. Provider
 * implementations and their configuration (e.g. WebDAV endpoint credentials)
 * live outside this module, so the coordinator only ever sees this seam.
 */
sealed interface CloudBackupProviderResolution {
    /** The provider is configured and ready to run. */
    data class Ready(val provider: CloudBackupProvider) : CloudBackupProviderResolution

    /** The provider cannot run; [reason] is a stable, machine-readable skip reason. */
    data class Skipped(val reason: String) : CloudBackupProviderResolution
}

fun interface CloudBackupProviderResolver {
    /**
     * Resolves the provider for [source], refreshing provider-specific
     * configuration before the backup runs. Must never throw: a provider that
     * cannot run is reported as [CloudBackupProviderResolution.Skipped].
     */
    suspend fun resolve(context: Context, source: BackupSource): CloudBackupProviderResolution
}
