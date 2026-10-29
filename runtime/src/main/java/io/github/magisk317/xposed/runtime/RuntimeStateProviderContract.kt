package io.github.magisk317.xposed.runtime

/** Host-neutral provider-call names and extras used by injected hooks. */
object RuntimeStateProviderContract {
    const val METHOD_RECORD_HOOK_HEARTBEAT = "xposed.runtime.record_hook_heartbeat"
    const val METHOD_RECORD_HOOK_PROCESS_ONLINE = "xposed.runtime.record_hook_process_online"
    const val METHOD_RECORD_CALL_NOTIFICATION_SOURCE = "xposed.runtime.record_call_notification_source"
    const val METHOD_RECORD_HOOK_INSTALL = "xposed.runtime.record_hook_install"
    const val METHOD_RECORD_NOTIFY_EVENT = "xposed.runtime.record_notify_event"

    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_PROCESS_NAME = "process_name"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_VERBOSE_LOGGING = "verbose_logging"
    const val EXTRA_MODULE_VERSION = "module_version"
    const val EXTRA_CHANNEL_ID = "channel_id"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_HOOK = "hook"
    const val EXTRA_HOOK_STATUS = "hook_status"
    const val EXTRA_HOOK_DETAIL = "hook_detail"
    const val EXTRA_NOTIFY_SOURCE = "notify_source"
    const val EXTRA_MSG_TYPE = "msg_type"
    const val EXTRA_SKIP_REASON = "skip_reason"
    const val RESULT_OK = "ok"
}
