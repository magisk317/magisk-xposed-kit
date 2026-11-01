package io.github.magisk317.xposed.runtime.state

/**
 * The shared activation rule.
 *
 * A module counts as active when the framework is reachable, when the framework bound to the app
 * at least once this boot, when the framework reports a process that currently runs the module
 * code, or when the hook itself reported a heartbeat. Every term is an independent proof of life,
 * so losing one of them — a content provider the system refuses to start, a hook that cannot
 * reach the app — must never turn a working module into "not activated".
 */
object RuntimeActivationRule {

    fun isActivated(inputs: RuntimeStatusInputs): Boolean =
        inputs.runtimeConnected ||
            inputs.hasServiceBind ||
            inputs.hasFrameworkHookTarget ||
            inputs.hasHookHeartbeat

    /**
     * Derives the activation inputs from a persisted snapshot.
     *
     * The time based terms are scoped to the current boot: a snapshot written before the last
     * reboot proves nothing about the framework that is running now. A service bind also stops
     * counting once the framework announced that the binder died.
     */
    fun evaluate(
        snapshot: RuntimeDiagnosticsSnapshot,
        bootStartAtMs: Long,
        runtimeConnected: Boolean,
    ): RuntimeStatusInputs {
        val hasServiceBind = snapshot.lastServiceBindAtMs > 0L &&
            snapshot.lastServiceBindAtMs >= bootStartAtMs &&
            snapshot.lastServiceDeathAtMs < snapshot.lastServiceBindAtMs
        val hasFrameworkHookTarget = snapshot.lastFrameworkHookAtMs > 0L &&
            snapshot.lastFrameworkHookAtMs >= bootStartAtMs &&
            snapshot.frameworkHookTargets.isNotEmpty()
        return RuntimeStatusInputs(
            runtimeConnected = runtimeConnected,
            hasHookHeartbeat = snapshot.lastHookAtMs > 0L && snapshot.lastHookAtMs >= bootStartAtMs,
            hasServiceBind = hasServiceBind,
            hasFrameworkHookTarget = hasFrameworkHookTarget,
        )
    }
}
