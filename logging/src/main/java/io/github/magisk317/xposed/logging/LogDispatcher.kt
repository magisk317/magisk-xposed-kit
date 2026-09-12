package io.github.magisk317.xposed.logging

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Result of accepting a record into the local delivery pipeline. */
enum class DeliveryResult {
    ENQUEUED,
    CLOSED,
    QUEUE_FULL,
}

data class DispatcherSnapshot(
    val enqueuedCount: Long,
    val deliveredCount: Long,
    val droppedCount: Long,
    val sinkFailureCount: Long,
    val queueDepth: Int,
    val closed: Boolean,
)

/**
 * Non-blocking, bounded structured-log dispatcher.
 *
 * The caller never waits for a sink. A full queue is reported to the caller
 * and counted in memory; diagnostics must not recursively log the failure.
 */
class LogDispatcher(
    private val sinks: List<StructuredLogSink>,
    private val sanitizer: StructuredLogSanitizer = StructuredLogSanitizer(),
    queueCapacity: Int = 256,
    threadName: String = "structured-log-dispatcher",
) : StructuredLogSink, AutoCloseable {
    private val enqueuedCount = AtomicLong(0)
    private val deliveredCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val sinkFailureCount = AtomicLong(0)
    private val queue = ArrayBlockingQueue<Runnable>(queueCapacity.also {
        require(it > 0) { "queueCapacity must be positive" }
    })
    private val executor = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        queue,
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
    )

    @Volatile
    private var closed = false

    override fun append(record: LogRecord) {
        dispatch(record)
    }

    fun dispatch(record: LogRecord): DeliveryResult {
        if (closed) {
            droppedCount.incrementAndGet()
            return DeliveryResult.CLOSED
        }
        val safeRecord = sanitizer.sanitize(record)
        return try {
            executor.execute {
                var delivered = true
                sinks.forEach { sink ->
                    runCatching { sink.append(safeRecord) }
                        .onFailure {
                            delivered = false
                            sinkFailureCount.incrementAndGet()
                        }
                }
                if (delivered) deliveredCount.incrementAndGet()
            }
            enqueuedCount.incrementAndGet()
            DeliveryResult.ENQUEUED
        } catch (_: RejectedExecutionException) {
            droppedCount.incrementAndGet()
            DeliveryResult.QUEUE_FULL
        }
    }

    fun snapshot(): DispatcherSnapshot = DispatcherSnapshot(
        enqueuedCount = enqueuedCount.get(),
        deliveredCount = deliveredCount.get(),
        droppedCount = droppedCount.get(),
        sinkFailureCount = sinkFailureCount.get(),
        queueDepth = queue.size,
        closed = closed,
    )

    override fun close() {
        if (closed) return
        closed = true
        executor.shutdown()
    }
}
