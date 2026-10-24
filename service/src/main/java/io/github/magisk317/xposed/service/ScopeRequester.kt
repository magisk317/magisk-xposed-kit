package io.github.magisk317.xposed.service

import io.github.libxposed.service.XposedService
import java.util.concurrent.Executor

/**
 * Asks the framework to widen this module's scope.
 *
 * `scope.list` is only re-read when the module APK is installed or updated, so a package a module
 * starts supporting in a later release stays unselected until then. This is the programmatic way to
 * offer exactly those packages to the user without a reinstall.
 */
object ScopeRequester {

    private val DIRECT = Executor { it.run() }

    sealed interface Outcome {

        /** Nothing to ask: every requested package is already granted. */
        data class AlreadyGranted(val requested: List<String>) : Outcome

        /** The user approved these packages. */
        data class Approved(val approved: List<String>) : Outcome

        /** The user declined, or the framework declined with [message]. */
        data class Rejected(val message: String) : Outcome

        /** No live service binder, so no request was made. */
        data object NotReady : Outcome

        /** The call over Binder failed; [cause] carries the framework's reason. */
        data class Failed(val cause: Throwable) : Outcome
    }

    /**
     * Requests the entries of [packages] the framework has not granted yet.
     *
     * The framework answers on a binder thread, so [executor] decides where [onResult] runs. The
     * default delivers inline — right for a coroutine collector, wrong for a UI callback.
     */
    fun requestMissing(
        packages: Collection<String>,
        service: XposedService? = XposedServiceBridge.currentService,
        executor: Executor = DIRECT,
        onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
        onResult: (Outcome) -> Unit,
    ) {
        fun deliver(outcome: Outcome) {
            runCatching { executor.execute { onResult(outcome) } }
                .onFailure { onError("dispatch scope outcome", it) }
        }

        val wanted = packages.toList()
        if (wanted.isEmpty()) {
            deliver(Outcome.AlreadyGranted(emptyList()))
            return
        }
        val current = service ?: run {
            deliver(Outcome.NotReady)
            return
        }
        val granted: List<String> = runCatching { current.scope }.getOrElse { failure ->
            onError("read module scope", failure)
            deliver(Outcome.Failed(failure))
            return
        }
        val missing = ServicePlan.missingTargets(granted, wanted)
        if (missing.isEmpty()) {
            deliver(Outcome.AlreadyGranted(wanted))
            return
        }
        runCatching {
            current.requestScope(
                missing,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        deliver(Outcome.Approved(approved))
                    }

                    override fun onScopeRequestFailed(message: String) {
                        deliver(Outcome.Rejected(message))
                    }
                },
            )
        }.onFailure {
            onError("request module scope", it)
            deliver(Outcome.Failed(it))
        }
    }
}
