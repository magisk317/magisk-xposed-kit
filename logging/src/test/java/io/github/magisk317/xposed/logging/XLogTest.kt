package io.github.magisk317.xposed.logging

import io.github.magisk317.smscode.xposed.runtime.CoreLogSink
import io.github.magisk317.smscode.xposed.runtime.CoreLogSinkHolder
import io.github.magisk317.smscode.xposed.runtime.CoreRuntime
import io.github.magisk317.smscode.xposed.runtime.CoreRuntimeAccess
import io.github.magisk317.smscode.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.logging.XLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import android.util.Log

class XLogTest {

    @BeforeEach
    fun setup() {
        // Establish the neutral XLog -> CoreLogSinkHolder bridge with the core tag,
        // so log tag/sink state is deterministic and not leaked from other tests.
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
    fun testSinkReceivesFormattedThrowableWithoutAndroidLogStub() {
        var captured: Pair<Int, String>? = null
        XLog.setLogLevel(2)
        XLog.setTestSink { priority, message -> captured = priority to message }

        XLog.e(message = "hook failed: %s", "sms", IllegalStateException("boom"))

        assertEquals(6, captured?.first)
        assertTrue(captured?.second.orEmpty().contains("hook failed: sms"))
        assertTrue(captured?.second.orEmpty().contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun coreSinkReceivesFormattedMessageWhenLogcatIsUnavailableInJvm() {
        var captured: CapturedLog? = null
        XLog.setLogLevel(2)
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
                    captured = CapturedLog(priority, tag, message, force, route, sensitive)
                }
            },
        )

        XLog.i(message = "loaded %s", "package")

        assertEquals(4, captured?.priority)
        assertEquals("smscode-core", captured?.tag)
        assertEquals("loaded package", captured?.message)
        assertFalse(captured?.force ?: true)
        assertEquals(null, captured?.route)
        assertTrue(captured?.sensitive ?: false)
    }

    @Test
    fun routeScopeAppliesExplicitRouteAndForcePolicy() {
        var captured: CapturedLog? = null
        XLog.setLogLevel(2)
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
                    captured = CapturedLog(priority, tag, message, force, route, sensitive)
                }
            },
        )

        XLog.withRoute(LogRoute.SMS_HOOK.id) {
            XLog.w("sms hook failed")
        }

        assertEquals(5, captured?.priority)
        assertEquals("sms_hook", captured?.route)
        assertTrue(captured?.force ?: false)
    }

    @Test
    fun explicitRouteOverloadsWinOverRouteScope() {
        var captured: CapturedLog? = null
        XLog.setLogLevel(2)
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
                    captured = CapturedLog(priority, tag, message, force, route, sensitive)
                }
            },
        )

        XLog.withRoute(LogRoute.SMS_HOOK.id) {
            XLog.log(Log.INFO, LogRoute.FORWARD.id, false, true, "forward event")
        }

        assertEquals(4, captured?.priority)
        assertEquals("forward", captured?.route)
        assertFalse(captured?.force ?: true)
    }

    @Test
    fun twoPositionalStringArgsResolveToTheTagOverload() {
        // Empirically pinned contract: with two positional String args, Kotlin
        // overload resolution prefers (tag, message, vararg) over (message, vararg),
        // so XLog.w("fmt %s", str) treats the FIRST argument as the tag and the
        // SECOND as the raw (unformatted) message. Message-template call sites
        // must name the first argument: XLog.w(message = "fmt %s", str).
        var captured: String? = null
        XLog.setLogLevel(2)
        XLog.setTestSink { _, message -> captured = message }

        XLog.w("probe fmt %s", "hello")

        assertEquals("hello", captured)
    }

    @Test
    fun stringArgsPlusThrowableAlsoResolveToTheTagOverload() {
        // Same resolution rule as the two-String-arg case: a trailing Throwable
        // does not change overload selection, so XLog.e("fmt %s", str, throwable)
        // also lands on (tag, message, vararg) unless message is named.
        var captured: String? = null
        XLog.setLogLevel(2)
        XLog.setTestSink { _, message -> captured = message }

        XLog.e("hook failed: %s", "sms", IllegalStateException("boom"))

        assertTrue(captured.orEmpty().contains("sms"))
        assertTrue(captured.orEmpty().contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun threePositionalStringArgsNeedExplicitMessageNaming() {
        // Contract: 3+ positional args with String first-three are ambiguous
        // at compile time; the named form must keep message semantics.
        var captured: String? = null
        XLog.setLogLevel(2)
        XLog.setTestSink { _, message -> captured = message }

        XLog.w(message = "probe fmt %s %s", "a", "b")

        assertEquals("probe fmt a b", captured)
    }

    @Test
    fun routeScopeRestoresPreviousRouteAfterNestedBlock() {
        val captured = mutableListOf<CapturedLog>()
        XLog.setLogLevel(2)
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
                    captured += CapturedLog(priority, tag, message, force, route, sensitive)
                }
            },
        )

        XLog.withRoute(LogRoute.SMS_HOOK.id) {
            XLog.i("outer")
            XLog.withRoute(LogRoute.FORWARD.id) {
                XLog.i("inner")
            }
            XLog.i("outer again")
        }
        XLog.i("no route")

        assertEquals(listOf("sms_hook", "forward", "sms_hook", null), captured.map { it.route })
    }

    private data class CapturedLog(
        val priority: Int,
        val tag: String,
        val message: String,
        val force: Boolean,
        val route: String?,
        val sensitive: Boolean,
    )
}
