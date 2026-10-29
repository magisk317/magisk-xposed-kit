package io.github.magisk317.xposed.runtime.contract.logging

import java.util.Locale

enum class LogRoute(val id: String) {
    APP("app"),
    SMS_HOOK("sms_hook"),
    NMS_HOOK("nms_hook"),
    SYSTEM_INPUT("system_input"),
    PERMISSION_HOOK("permission_hook"),
    FORWARD("forward"),
    SENDER("sender"),
    ROOT_DB("root_db"),
    ;

    companion object {
        fun normalize(route: String?): String {
            val normalized = route?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.id == normalized }?.id ?: APP.id
        }

        fun fromId(route: String?): LogRoute {
            val normalized = normalize(route)
            return entries.firstOrNull { it.id == normalized } ?: APP
        }
    }
}
