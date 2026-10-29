package io.github.magisk317.xposed.runtime.contract.autoinput

import android.content.Intent

object AutoInputBroadcastContract {
    private const val ACTION_AUTO_INPUT_SUFFIX = "ACTION_AUTO_INPUT"
    private const val ACTION_SHOW_TOAST_SUFFIX = "ACTION_SHOW_TOAST"
    private const val ACTION_AUTO_INPUT_RESULT_SUFFIX = "ACTION_AUTO_INPUT_RESULT"

    const val EXTRA_CODE = "code"
    const val EXTRA_AUTO_ENTER = "autoEnter"
    const val EXTRA_INPUT_INTERVAL_MS = "inputIntervalMs"
    const val EXTRA_ATTEMPT_ID = "attemptId"
    const val EXTRA_IPC_TOKEN = "ipc_token"

    data class Request(
        val code: String,
        val autoEnter: Boolean,
        val inputIntervalMs: Long,
        val attemptId: Long?,
    )

    sealed class ReceiverRequest {
        object Ignored : ReceiverRequest()

        data class BlankCode(
            val request: Request,
        ) : ReceiverRequest()

        data class Accepted(
            val request: Request,
        ) : ReceiverRequest()
    }

    fun buildRequestIntent(
        action: String,
        request: Request,
        token: String? = null,
        intentFactory: (String) -> Intent = ::Intent,
    ): Intent {
        return putRequestExtras(intentFactory(action), request).apply {
            normalizeIpcToken(token)?.let { putExtra(EXTRA_IPC_TOKEN, it) }
        }
    }

    fun putRequestExtras(
        intent: Intent,
        request: Request,
    ): Intent {
        return intent.apply {
            putExtra(EXTRA_CODE, request.code)
            putExtra(EXTRA_AUTO_ENTER, request.autoEnter)
            putExtra(EXTRA_INPUT_INTERVAL_MS, request.inputIntervalMs)
            request.attemptId?.let { putExtra(EXTRA_ATTEMPT_ID, it) }
        }
    }

    fun readReceiverRequest(
        intent: Intent,
        expectedAction: String,
    ): ReceiverRequest {
        if (intent.action != expectedAction) return ReceiverRequest.Ignored
        val request = Request(
            code = intent.getStringExtra(EXTRA_CODE).orEmpty(),
            autoEnter = intent.getBooleanExtra(EXTRA_AUTO_ENTER, false),
            inputIntervalMs = intent.getLongExtra(EXTRA_INPUT_INTERVAL_MS, 0L).coerceAtLeast(0L),
            attemptId = intent.getLongExtra(EXTRA_ATTEMPT_ID, -1L).takeIf { it >= 0L },
        )
        return if (request.code.isBlank()) {
            ReceiverRequest.BlankCode(request)
        } else {
            ReceiverRequest.Accepted(request)
        }
    }

    fun normalizeIpcToken(token: String?): String? = token?.trim()?.takeIf { it.isNotEmpty() }

    fun resolveActionAutoInput(actionNamespace: String): String =
        actionNamespace.qualifyAction(ACTION_AUTO_INPUT_SUFFIX)

    fun resolveActionShowToast(actionNamespace: String): String =
        actionNamespace.qualifyAction(ACTION_SHOW_TOAST_SUFFIX)

    fun resolveActionAutoInputResult(actionNamespace: String): String =
        actionNamespace.qualifyAction(ACTION_AUTO_INPUT_RESULT_SUFFIX)

    private fun String.qualifyAction(suffix: String): String =
        "${trim().trimEnd('.')}.$suffix"
}
