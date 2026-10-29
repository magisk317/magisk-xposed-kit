package io.github.magisk317.xposed.capability.runtime

import android.util.Log
import io.github.magisk317.xposed.logging.MagiskOtel

interface CoreRuntimeAccess {
    val logTag: String
    val logLevel: Int
    val logToXposed: Boolean
    val debug: Boolean
    val applicationId: String
    val actionNamespace: String
}

object CoreRuntime {
    @Volatile
    private var accessRef: CoreRuntimeAccess? = null

    fun install(access: CoreRuntimeAccess) {
        accessRef = access
        io.github.magisk317.xposed.logging.XLog.configure(
            tag = access.logTag,
            logLevel = access.logLevel,
            logToXposed = access.logToXposed,
            sink = io.github.magisk317.xposed.logging.LogSink { event ->
                CoreLogSinkHolder.append(
                    priority = event.priority,
                    tag = event.tag,
                    message = event.message,
                    force = event.force,
                    route = event.route,
                    sensitive = event.sensitive,
                    throwableText = event.throwableText ?: event.throwable?.stackTraceToString(),
                )
            },
        )
        MagiskOtel.event(
            name = "hook.load",
            attributes = mapOf(
                "result" to "ok",
                "duration_ms" to "0",
                "process" to "hook",
                "application_id_present" to access.applicationId.isNotBlank().toString(),
            ),
            statusOk = true,
        )
    }

    // Test seam: consumers reset the capability runtime between unit tests.
    fun resetForTest() {
        accessRef = null
        io.github.magisk317.xposed.logging.XLog.resetForTest()
    }

    val access: CoreRuntimeAccess
        get() = accessRef ?: DefaultAccess

    private object DefaultAccess : CoreRuntimeAccess {
        override val logTag: String = "xposed-capability"
        override val logLevel: Int = Log.INFO
        override val logToXposed: Boolean = false
        override val debug: Boolean = false
        override val applicationId: String = ""
        override val actionNamespace: String = "io.github.magisk317.xposed"
    }
}
