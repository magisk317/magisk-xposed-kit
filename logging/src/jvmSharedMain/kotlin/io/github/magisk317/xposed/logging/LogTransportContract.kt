package io.github.magisk317.xposed.logging

/** Versioned cross-process envelope shared by all consumers. */
data class LogEnvelope(
    val eventId: String,
    val sequence: Long,
    val record: LogRecord,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
) {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(sequence >= 0L) { "sequence must not be negative" }
        require(protocolVersion > 0) { "protocolVersion must be positive" }
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 2
    }
}

enum class StoreAppendResult {
    APPENDED,
    QUOTA_EXCEEDED,
    REJECTED,
    CLOSED,
}

/** Host-side persistence contract; concrete paths and retention stay in consumers. */
fun interface LogRecordStore {
    fun append(record: LogRecord): StoreAppendResult
}

interface LogTransport {
    fun send(envelope: LogEnvelope): DeliveryResult
    fun close()
}

/** Result of accepting a record into the local delivery pipeline. */
enum class DeliveryResult {
    ENQUEUED,
    CLOSED,
    QUEUE_FULL,
}
