package io.github.magisk317.xposed.runtime.contract.autoinput

import android.content.Intent

object AutoInputResultBroadcastContract {
    val EXTRA_ATTEMPT_ID: String
        get() = AutoInputBroadcastContract.EXTRA_ATTEMPT_ID
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_REASON = "reason"

    data class Result(
        val attemptId: Long,
        val success: Boolean,
        val reason: String?,
    )

    sealed class ReceiverResult {
        object Ignored : ReceiverResult()
        object MissingAttemptId : ReceiverResult()

        data class Accepted(
            val result: Result,
        ) : ReceiverResult()
    }

    fun buildResultIntent(
        action: String,
        packageName: String?,
        result: Result,
        token: String? = null,
        intentFactory: (String) -> Intent = ::Intent,
    ): Intent {
        return putResultExtras(intentFactory(action), result).apply {
            packageName?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
            AutoInputBroadcastContract.normalizeIpcToken(token)
                ?.let { putExtra(AutoInputBroadcastContract.EXTRA_IPC_TOKEN, it) }
        }
    }

    fun putResultExtras(
        intent: Intent,
        result: Result,
    ): Intent {
        return intent.apply {
            putExtra(EXTRA_ATTEMPT_ID, result.attemptId)
            putExtra(EXTRA_SUCCESS, result.success)
            if (!result.success && result.reason != null) {
                putExtra(EXTRA_REASON, result.reason)
            }
        }
    }

    fun readResult(
        intent: Intent,
        expectedAction: String,
    ): ReceiverResult {
        if (intent.action != expectedAction) return ReceiverResult.Ignored
        val attemptId = intent.getLongExtra(EXTRA_ATTEMPT_ID, -1L)
        if (attemptId <= 0L) return ReceiverResult.MissingAttemptId
        return ReceiverResult.Accepted(
            Result(
                attemptId = attemptId,
                success = intent.getBooleanExtra(EXTRA_SUCCESS, false),
                reason = intent.getStringExtra(EXTRA_REASON),
            ),
        )
    }
}
