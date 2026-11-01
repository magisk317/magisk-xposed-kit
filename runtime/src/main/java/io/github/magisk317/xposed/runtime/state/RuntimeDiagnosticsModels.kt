package io.github.magisk317.xposed.runtime.state

data class HookProcessStatus(
    val packageName: String = "",
    val processName: String = "",
    val lastSeenAtMs: Long = 0L,
    val source: String = "",
    val moduleVersion: Int = 0,
)

data class CallNotificationSourceSnapshot(
    val packageName: String = "",
    val channelId: String = "",
    val category: String = "",
    val lastSeenAtMs: Long = 0L,
)

data class HookInstallStatus(
    val hook: String = "",
    val status: String = "",
    val packageName: String = "",
    val processName: String = "",
    val detail: String = "",
    val lastSeenAtMs: Long = 0L,
)

data class NotifyEventSnapshot(
    val source: String = "",
    val packageName: String = "",
    val channelId: String = "",
    val msgType: String = "",
    val skipReason: String = "",
    val lastSeenAtMs: Long = 0L,
)

/**
 * A process the framework reports as running the module code.
 *
 * The framework is the only side that knows which processes carry the module, so this snapshot
 * is what keeps the activation status independent from the hook having to wake the app through a
 * content provider the system may refuse to start.
 */
data class FrameworkHookTargetStatus(
    val packageName: String = "",
    val processName: String = "",
    val pid: Int = 0,
    val uid: Int = 0,
    val state: String = "",
    val lastSeenAtMs: Long = 0L,
)

data class RuntimeDiagnosticsSnapshot(
    val lastServiceBindAtMs: Long = 0L,
    val lastServiceFrameworkName: String = "",
    val lastServiceFrameworkVersion: String = "",
    val lastServiceDeathAtMs: Long = 0L,
    val lastHookAtMs: Long = 0L,
    val lastHookPackage: String = "",
    val lastHookProcess: String = "",
    val lastHookSource: String = "",
    val hookProcesses: List<HookProcessStatus> = emptyList(),
    val lastCallNotifySource: CallNotificationSourceSnapshot = CallNotificationSourceSnapshot(),
    val hookInstalls: List<HookInstallStatus> = emptyList(),
    val lastNotifyEvent: NotifyEventSnapshot = NotifyEventSnapshot(),
    val lastFrameworkHookAtMs: Long = 0L,
    val frameworkHookTargets: List<FrameworkHookTargetStatus> = emptyList(),
)

data class RuntimeStatusInputs(
    val runtimeConnected: Boolean,
    val hasHookHeartbeat: Boolean,
    /** The framework bound to this app at least once since the device booted. */
    val hasServiceBind: Boolean = false,
    /** The framework reports at least one process that currently runs the module code. */
    val hasFrameworkHookTarget: Boolean = false,
)

data class RuntimeStatusState(
    val isEnabled: Boolean = false,
    val runtimeConnected: Boolean = false,
    val diagnostics: RuntimeDiagnosticsSnapshot = RuntimeDiagnosticsSnapshot(),
)
