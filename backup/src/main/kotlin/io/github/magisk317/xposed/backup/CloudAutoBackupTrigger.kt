package io.github.magisk317.xposed.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CloudAutoBackupTrigger(
    private val coordinator: AutoCloudBackupCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoBackupTrigger {
    override fun scheduleAutoBackup(reason: String) {
        coordinator.schedule(scope, reason)
    }
}
