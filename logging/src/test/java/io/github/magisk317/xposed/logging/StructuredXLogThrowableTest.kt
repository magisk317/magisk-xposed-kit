package io.github.magisk317.xposed.logging

import android.util.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredXLogThrowableTest {
    @AfterEach
    fun tearDown() {
        XLog.resetForTest()
        LogSanitizerConfig.setEnabled(false)
    }

    @Test
    fun `xlog forwards throwable text separately from the event`() {
        var captured: LogEvent? = null
        XLog.configure(
            tag = "test",
            logLevel = Log.VERBOSE,
            sink = LogSink { captured = it },
        )
        XLog.setLogPrinterForTest { _, _, _ -> }

        XLog.e("hook failed: %s", "sms", IllegalStateException("boom"))

        assertTrue(captured?.message.orEmpty().contains("hook failed: sms"))
        assertTrue(captured?.throwableText.orEmpty().contains("IllegalStateException: boom"))
    }
}
