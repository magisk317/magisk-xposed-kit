package io.github.magisk317.xposed.backup

import kotlinx.serialization.Serializable

@Serializable
data class CloudBackupSettings(
    val autoBackupEnabled: Boolean = false,
    val autoBackupSource: BackupSource = BackupSource.GOOGLE_DRIVE,
)
