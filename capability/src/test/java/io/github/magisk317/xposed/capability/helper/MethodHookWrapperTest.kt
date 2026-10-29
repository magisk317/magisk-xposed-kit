package io.github.magisk317.xposed.capability.helper

import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.SimpleMethodHookParam
import io.github.magisk317.xposed.capability.runtime.CoreLogSink
import io.github.magisk317.xposed.capability.runtime.CoreLogSinkHolder
import io.github.magisk317.xposed.capability.runtime.CoreRuntime
import io.github.magisk317.xposed.capability.runtime.CoreRuntimeAccess
import io.github.magisk317.xposed.logging.XLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MethodHookWrapperTest {

    @BeforeEach
    fun setup() {
        // Establish the neutral XLog -> CoreLogSinkHolder bridge with the core tag,
        // so this test does not depend on another test having installed it first.
        CoreRuntime.install(
            object : CoreRuntimeAccess {
                override val logTag = "smscode-core"
                override val logLevel = android.util.Log.VERBOSE
                override val logToXposed = false
                override val debug = true
                override val applicationId = "io.github.magisk317.smscode"
                override val actionNamespace = "io.github.magisk317.smscode"
            },
        )
    }

    @AfterEach
    fun tearDown() {
        CoreRuntime.resetForTest()
        CoreLogSinkHolder.install(null)
    }

    @Test
    fun wrapperAppliesRouteToHookCallbackLogs() {
        var capturedRoute: String? = null
        CoreLogSinkHolder.install(
            object : CoreLogSink {
                override fun append(
                    priority: Int,
                    tag: String,
                    message: String,
                    force: Boolean,
                    route: String?,
                    sensitive: Boolean,
                    throwableText: String?,
                ) {
                    capturedRoute = route
                }
            },
        )
        val hook = object : MethodHookWrapper(LogRoute.PERMISSION_HOOK) {
            override fun after(param: MethodHookParam) {
                XLog.i("permission callback")
            }
        }

        hook.afterHookedMethod(
            SimpleMethodHookParam(
                method = String::class.java.getMethod("length"),
                thisObject = "sms",
                args = emptyArray(),
            ),
        )

        assertEquals("permission_hook", capturedRoute)
    }
}
