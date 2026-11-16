package io.github.magisk317.xposed.backup

/**
 * Kit-owned trigger contract for scheduling an automatic cloud backup. The
 * consuming app wires its own triggers (lifecycle events, content observers)
 * through this shape instead of depending on a transport implementation.
 */
interface AutoBackupTrigger {
    fun scheduleAutoBackup(reason: String)
}
