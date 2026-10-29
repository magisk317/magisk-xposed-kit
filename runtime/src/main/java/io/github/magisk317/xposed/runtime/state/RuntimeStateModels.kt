package io.github.magisk317.xposed.runtime.state

data class HookProcessStatus(
    val packageName: String = "",
    val processName: String = "",
    val lastSeenAtMs: Long = 0L,
    val source: String = "",
    val moduleVersion: Int = 0,
)

data class HookInstallStatus(
    val hook: String = "",
    val status: String = "",
    val packageName: String = "",
    val processName: String = "",
    val detail: String = "",
    val lastSeenAtMs: Long = 0L,
)

data class CallNotificationSourceSnapshot(
    val packageName: String = "",
    val channelId: String = "",
    val category: String = "",
    val lastSeenAtMs: Long = 0L,
)

data class NotifyEventSnapshot(
    val source: String = "",
    val packageName: String = "",
    val channelId: String = "",
    val messageType: String = "",
    val skipReason: String = "",
    val lastSeenAtMs: Long = 0L,
)

data class RuntimeStateSnapshot(
    val lastServiceBindAtMs: Long = 0L,
    val lastServiceFrameworkName: String = "",
    val lastServiceFrameworkVersion: String = "",
    val hookProcesses: List<HookProcessStatus> = emptyList(),
    val hookInstalls: List<HookInstallStatus> = emptyList(),
    val lastCallNotificationSource: CallNotificationSourceSnapshot = CallNotificationSourceSnapshot(),
    val lastNotifyEvent: NotifyEventSnapshot = NotifyEventSnapshot(),
)
