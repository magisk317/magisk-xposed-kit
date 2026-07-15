package io.github.magisk317.xposed.logging

import android.util.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XLogTest {

    @AfterEach
    fun tearDown() {
        XLog.resetForTest()
        LogSanitizerConfig.setEnabled(true)
    }

    @Test
    fun `sensitive messages are sanitized before every output`() {
        val order = mutableListOf<String>()
        val outputMessages = mutableListOf<String>()
        XLog.setLogPrinterForTest { _, tag, message ->
            order += if (tag == "LSPosed-Bridge") "xposed" else "logcat"
            outputMessages += message
        }
        XLog.configure(
            tag = "test",
            logLevel = Log.VERBOSE,
            logToXposed = true,
            sink = LogSink { event ->
                order += "sink"
                outputMessages += event.message
            },
            sanitizer = LogSanitizer { message ->
                order += "sanitize"
                message.replace("plaintext-secret", "***")
            },
        )

        XLog.i("token=%s", "plaintext-secret")

        assertEquals(listOf("sanitize", "logcat", "xposed", "sink"), order)
        assertEquals(3, outputMessages.size)
        assertTrue(outputMessages.all { "plaintext-secret" !in it })
    }

    @Test
    fun `test sink receives sanitized formatted message`() {
        var captured: String? = null
        XLog.configure(
            tag = "test",
            logLevel = Log.VERBOSE,
            sanitizer = DefaultLogSanitizer,
        )
        XLog.setTestSink { _, message -> captured = message }

        XLog.i("token=%s", "plaintext-secret")

        assertFalse(captured.orEmpty().contains("plaintext-secret"))
    }

    @Test
    fun `non-sensitive messages bypass sanitizer`() {
        var sanitizerCalls = 0
        var captured: String? = null
        XLog.configure(
            tag = "test",
            logLevel = Log.VERBOSE,
            sink = LogSink { event -> captured = event.message },
            sanitizer = LogSanitizer {
                sanitizerCalls += 1
                "unexpected"
            },
        )
        XLog.setLogPrinterForTest { _, _, _ -> }

        XLog.log(Log.INFO, null, false, false, "public=%s", "plaintext")

        assertEquals(0, sanitizerCalls)
        assertEquals("public=plaintext", captured)
    }

    @Test
    fun `sensitive debug switch preserves bounded plaintext`() {
        var captured: String? = null
        LogSanitizerConfig.setEnabled(false)
        XLog.configure(tag = "test", logLevel = Log.VERBOSE)
        XLog.setTestSink { _, message -> captured = message }
        val rawMessage = "token=plaintext-secret " + "x".repeat(2000)

        XLog.i(rawMessage)

        assertTrue(captured.orEmpty().startsWith("token=plaintext-secret "))
        assertTrue(captured.orEmpty().endsWith("...(len=${rawMessage.length})"))
        assertFalse(captured.orEmpty().contains("x".repeat(801)))
    }

    @Test
    fun `failing sanitizer falls back without leaking plaintext`() {
        var captured: String? = null
        XLog.configure(
            tag = "test",
            logLevel = Log.VERBOSE,
            sanitizer = LogSanitizer { error("sanitizer failed") },
        )
        XLog.setTestSink { _, message -> captured = message }

        XLog.i("token=plaintext-secret")

        assertFalse(captured.orEmpty().contains("plaintext-secret"))
    }
}
