package io.github.magisk317.xposed.capability.helper

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputBroadcastContract
import io.github.magisk317.xposed.logging.MagiskOtel
import io.github.magisk317.xposed.logging.XLog

object AutoInputBroadcastHelper {
    fun sendText(
        context: Context,
        text: String?,
        autoEnter: Boolean = false,
        inputIntervalMs: Long = 0L,
        attemptId: Long? = null,
        actionResolver: () -> String,
        tokenProvider: () -> String = { "" },
        broadcaster: (Context, Intent) -> Unit = { senderContext, intent ->
            senderContext.sendOrderedBroadcast(intent, null)
        },
    ) {
        if (text == null) return
        val resolvedAttemptId = attemptId ?: SystemClock.elapsedRealtimeNanos()
        val request = AutoInputBroadcastContract.Request(
            code = text,
            autoEnter = autoEnter,
            inputIntervalMs = inputIntervalMs,
            attemptId = resolvedAttemptId,
        )
        val intent = AutoInputBroadcastContract.buildRequestIntent(
            action = actionResolver(),
            request = request,
            token = tokenProvider(),
        )
        broadcaster(context, intent)
        XLog.i(
            message = "Dispatched ACTION_AUTO_INPUT request: attemptId=%d code_len=%d autoEnter=%s inputIntervalMs=%d",
            resolvedAttemptId,
            text.length,
            autoEnter,
            inputIntervalMs,
        )
        MagiskOtel.event(
            name = "auto.input",
            attributes = mapOf(
                "result" to "ok",
                "duration_ms" to "0",
                "process" to "hook",
                "stage" to "broadcast",
                "code_length" to text.length.toString(),
                "strategy" to if (autoEnter) "broadcast_auto_enter" else "broadcast",
            ),
            statusOk = true,
        )
    }

    fun readReceiverRequest(
        intent: Intent,
        expectedAction: String,
    ): AutoInputBroadcastContract.ReceiverRequest =
        AutoInputBroadcastContract.readReceiverRequest(intent, expectedAction)

    fun logReceiverRequest(
        request: AutoInputBroadcastContract.Request,
        ordered: Boolean,
    ) {
        XLog.w(
            message = "Accessibility auto input request received: attemptId=%d code_len=%d autoEnter=%s inputIntervalMs=%d ordered=%s",
            request.attemptId ?: -1L,
            request.code.length,
            request.autoEnter,
            request.inputIntervalMs,
            ordered,
        )
    }

    fun normalizeIpcToken(token: String?): String? =
        AutoInputBroadcastContract.normalizeIpcToken(token)
}
