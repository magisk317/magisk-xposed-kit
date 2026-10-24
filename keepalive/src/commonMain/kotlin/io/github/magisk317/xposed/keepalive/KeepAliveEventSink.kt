package io.github.magisk317.xposed.keepalive

/**
 * Receives keep-alive decisions for telemetry.
 *
 * Optional by design: the hook only logs when no sink is configured. Emitting is
 * best-effort, so a telemetry failure can never escalate into a hook failure.
 */
fun interface KeepAliveEventSink {
    fun event(name: String, attributes: Map<String, String>)
}
