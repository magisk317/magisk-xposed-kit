package io.github.magisk317.xposed.logging

/** Result of accepting a record into the local delivery pipeline. */
enum class DeliveryResult {
    ENQUEUED,
    CLOSED,
    QUEUE_FULL,
}
