package io.github.magisk317.xposed.logging

/**
 * Shared vocabulary builder for stage/result MagiskOtel events.
 *
 * result / duration_ms / process / stage / reason / source are the de-facto contract
 * attributes both products emit; hosts keep their event names and add product-specific
 * attributes through [extra].
 */
object StageEventEmitter {
    fun emit(
        name: String,
        result: String,
        stage: String,
        reason: String,
        source: String,
        process: String = "hook",
        durationMs: Long = 0L,
        msgType: String? = null,
        eventIdPresent: Boolean = false,
        statusOk: Boolean = true,
        extra: Map<String, String> = emptyMap(),
    ) {
        val attrs = mutableMapOf(
            "result" to result,
            "duration_ms" to durationMs.toString(),
            "process" to process,
            "stage" to stage,
            "reason" to reason,
            "source" to source,
        )
        msgType?.let { attrs["msg_type"] = it }
        if (eventIdPresent) {
            attrs["event_id_present"] = "true"
        }
        attrs.putAll(extra)
        MagiskOtel.event(name = name, attributes = attrs, statusOk = statusOk)
    }
}
