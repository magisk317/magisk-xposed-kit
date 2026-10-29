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

data class RuntimeDiagnosticsSnapshot(
    val lastServiceBindAtMs: Long = 0L,
    val lastServiceFrameworkName: String = "",
    val lastServiceFrameworkVersion: String = "",
    val lastHookAtMs: Long = 0L,
    val lastHookPackage: String = "",
    val lastHookProcess: String = "",
    val lastHookSource: String = "",
    val hookProcesses: List<HookProcessStatus> = emptyList(),
    val lastCallNotifySource: CallNotificationSourceSnapshot = CallNotificationSourceSnapshot(),
    val hookInstalls: List<HookInstallStatus> = emptyList(),
    val lastNotifyEvent: NotifyEventSnapshot = NotifyEventSnapshot(),
)

data class RuntimeStatusInputs(
    val runtimeConnected: Boolean,
    val hasHookHeartbeat: Boolean,
)

data class RuntimeStatusState(
    val isEnabled: Boolean = false,
    val runtimeConnected: Boolean = false,
    val diagnostics: RuntimeDiagnosticsSnapshot = RuntimeDiagnosticsSnapshot(),
)
