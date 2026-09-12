package io.github.magisk317.xposed.logging

import android.content.Context
import android.os.Process
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight log client that runs in the Xposed hook process.
 * Sends [XposedLogEvent]s to the host application via ContentResolver.
 *
 * Usage:
 * ```kotlin
 * XposedLogClient.configure(authority = "com.example.module.log", source = "MyModule")
 * XposedLogClient.i("MyTag", "something happened")
 * ```
 */
object XposedLogClient {
    private const val ZYGOTE_BUFFER_LIMIT = 128
    private const val EXECUTOR_QUEUE_LIMIT = 256

    /**
     * A bounded-loss snapshot of transport state. Counters are read-only values;
     * collecting a snapshot never emits another log event or touches the provider.
     */
    data class DeliverySnapshot(
        /** Events accepted by the bounded executor queue. */
        val acceptedCount: Long,
        /** Events successfully inserted into the host provider. */
        val deliveredCount: Long,
        /** Sum of all events discarded before provider delivery. */
        val droppedCount: Long,
        val droppedQueueFullCount: Long,
        val droppedZygoteOverflowCount: Long,
        val droppedBackoffCount: Long,
        val providerNullCount: Long,
        val providerExceptionCount: Long,
        val queueDepth: Int,
        val zygoteBufferDepth: Int,
        val providerUnavailableUntilMs: Long,
        val lastProviderFailureAtMs: Long,
    )

    private enum class DropReason {
        QUEUE_FULL,
        ZYGOTE_OVERFLOW,
        BACKOFF,
        PROVIDER_NULL,
        PROVIDER_EXCEPTION,
    }

    /**
     * Count of events discarded by this transport. Kept as an AtomicLong for
     * existing callers; use [snapshot] when the reason breakdown is needed.
     */
    val droppedCount: AtomicLong = AtomicLong(0)

    private val acceptedCount = AtomicLong(0)
    private val deliveredCount = AtomicLong(0)
    private val droppedQueueFullCount = AtomicLong(0)
    private val droppedZygoteOverflowCount = AtomicLong(0)
    private val droppedBackoffCount = AtomicLong(0)
    private val providerNullCount = AtomicLong(0)
    private val providerExceptionCount = AtomicLong(0)
    private val sequence = AtomicLong(0)
    private val lastProviderFailureAtMs = AtomicLong(0)

    private val logExecutor = ThreadPoolExecutor(
        0,
        1,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(EXECUTOR_QUEUE_LIMIT),
        RejectedExecutionHandler { _, _ -> recordDrop(DropReason.QUEUE_FULL) },
    )

    @Volatile
    private var authority: String = ""

    @Volatile
    private var source: String = "module"

    @Volatile
    private var localContext: Context? = null

    private val zygoteLock = Any()

    /**
     * Buffered events collected before [attachContext] is called (e.g. during initZygote).
     * Flushed on first successful send after context becomes available.
     */
    private val zygoteBuffer = LinkedBlockingQueue<XposedLogEvent>(ZYGOTE_BUFFER_LIMIT)

    // Cached reflection for ActivityThread.currentApplication() — resolved once, reused forever.
    @Volatile
    private var cachedCurrentApplication: Method? = null

    @Volatile
    private var reflectionResolved = false

    // Once we've tried reflection and it returned null (ActivityThread not ready),
    // stop retrying until attachContext() is called. Avoids repeated invoke() overhead.
    @Volatile
    private var contextLookupFailed = false

    @Volatile
    private var providerUnavailableUntilMs: Long = 0L

    private const val PROVIDER_BACKOFF_MS = 60_000L

    fun configure(authority: String, source: String) {
        this.authority = authority
        this.source = source
    }

    fun attachContext(context: Context) {
        synchronized(zygoteLock) {
            localContext = context.applicationContext ?: context
            contextLookupFailed = false
            providerUnavailableUntilMs = 0L
            flushZygoteBufferLocked()
        }
    }

    /**
     * Return transport counters without producing a diagnostic log. This is
     * intentionally a pull API so a provider outage cannot recursively generate
     * more provider work.
     */
    fun snapshot(): DeliverySnapshot = DeliverySnapshot(
        acceptedCount = acceptedCount.get(),
        deliveredCount = deliveredCount.get(),
        droppedCount = droppedCount.get(),
        droppedQueueFullCount = droppedQueueFullCount.get(),
        droppedZygoteOverflowCount = droppedZygoteOverflowCount.get(),
        droppedBackoffCount = droppedBackoffCount.get(),
        providerNullCount = providerNullCount.get(),
        providerExceptionCount = providerExceptionCount.get(),
        queueDepth = logExecutor.queue.size,
        zygoteBufferDepth = zygoteBuffer.size,
        providerUnavailableUntilMs = providerUnavailableUntilMs,
        lastProviderFailureAtMs = lastProviderFailureAtMs.get(),
    )

    fun v(tag: String, message: String?, sensitive: Boolean = false) =
        emit("V", tag, message, null, sensitive = sensitive)
    fun d(tag: String, message: String?, sensitive: Boolean = false) =
        emit("D", tag, message, null, sensitive = sensitive)
    fun i(tag: String, message: String?, sensitive: Boolean = false) =
        emit("I", tag, message, null, sensitive = sensitive)
    fun w(tag: String, message: String?, sensitive: Boolean = false) =
        emit("W", tag, message, null, sensitive = sensitive)
    fun e(tag: String, message: String?, throwable: Throwable? = null, sensitive: Boolean = false) =
        emit("E", tag, message, throwable, sensitive = sensitive)

    fun append(
        priority: Int,
        tag: String,
        message: String,
        force: Boolean = false,
        route: String? = null,
        sensitive: Boolean = false,
        throwableText: String? = null,
    ) {
        emit(
            levelFor(priority),
            tag,
            message,
            null,
            force = force,
            route = route,
            sensitive = sensitive,
            throwableText = throwableText,
        )
    }

    fun send(record: LogRecord) {
        send(XposedLogEvent.fromRecord(record, sequence.incrementAndGet()))
    }

    fun send(event: XposedLogEvent) {
        val sanitizedEvent = sanitizeEvent(event)
        // Fast path: context already available, no lock or reflection needed
        val fastCtx = localContext
        if (fastCtx != null) {
            submitToExecutor(fastCtx, sanitizedEvent)
            return
        }
        synchronized(zygoteLock) {
            val ctx = localContext ?: currentApplicationContext()
            if (ctx == null) {
                // No context yet (Zygote phase) — buffer for later flush
                while (zygoteBuffer.size >= ZYGOTE_BUFFER_LIMIT) {
                    if (zygoteBuffer.poll() != null) {
                        recordDrop(DropReason.ZYGOTE_OVERFLOW)
                    }
                }
                if (!zygoteBuffer.offer(sanitizedEvent)) {
                    // The queue is only accessed under zygoteLock, so this should
                    // be unreachable; count it defensively if the invariant changes.
                    recordDrop(DropReason.ZYGOTE_OVERFLOW)
                }
                return
            }
            submitToExecutor(ctx, sanitizedEvent)
        }
    }

    private fun submitToExecutor(ctx: Context, event: XposedLogEvent) {
        if (isProviderBackoffActive()) {
            recordDrop(DropReason.BACKOFF)
            return
        }
        logExecutor.execute {
            // A provider failure can happen after this task was queued. Check
            // again on the worker so a backoff does not flood the provider.
            if (isProviderBackoffActive()) {
                recordDrop(DropReason.BACKOFF)
            } else {
                try {
                    val uri = XposedLogEvent.appendUri(authority)
                    // Missing host provider logs "Failed to find provider info" and returns null
                    // without throwing. Treat null as unavailable and back off hard.
                    val inserted = ctx.contentResolver.insert(uri, event.toContentValues())
                    if (inserted == null) {
                        markProviderUnavailable()
                        recordDrop(DropReason.PROVIDER_NULL)
                    } else {
                        deliveredCount.incrementAndGet()
                        providerUnavailableUntilMs = 0L
                    }
                } catch (_: Exception) {
                    markProviderUnavailable()
                    recordDrop(DropReason.PROVIDER_EXCEPTION)
                }
            }
        }
        acceptedCount.incrementAndGet()
    }

    private fun emit(
        level: String,
        tag: String,
        message: String?,
        throwable: Throwable?,
        force: Boolean = false,
        route: String? = null,
        sensitive: Boolean = false,
        throwableText: String? = null,
    ) {
        val event = XposedLogEvent(
            source = source,
            level = level,
            tag = tag,
            message = message ?: "",
            throwable = throwableText ?: throwable?.stackTraceToString() ?: "",
            route = route,
            force = force,
            sensitive = sensitive,
            packageName = localContext?.packageName ?: "",
            processName = ProcessNameResolver.currentProcessName(),
            pid = Process.myPid(),
            uid = Process.myUid(),
        )
        send(event)
    }

    private fun sanitizeEvent(event: XposedLogEvent): XposedLogEvent = event.copy(
        message = DefaultLogSanitizer.sanitizeIfEnabled(event.message),
        throwable = DefaultLogSanitizer.sanitizeIfEnabled(event.throwable),
        sanitized = true,
    )

    private fun flushZygoteBufferLocked() {
        val ctx = localContext ?: return
        while (true) {
            val event = zygoteBuffer.poll() ?: break
            submitToExecutor(ctx, event)
        }
    }

    private fun currentApplicationContext(): Context? {
        if (contextLookupFailed) return null
        val ctx = if (reflectionResolved) {
            cachedCurrentApplication?.let { method ->
                runCatching {
                    (method.invoke(null) as? Context)?.let { it.applicationContext ?: it }
                }.getOrNull()
            }
        } else {
            runCatching {
                val clazz = Class.forName("android.app.ActivityThread")
                val method = clazz.getDeclaredMethod("currentApplication").also {
                    it.isAccessible = true
                    cachedCurrentApplication = it
                }
                reflectionResolved = true
                (method.invoke(null) as? Context)?.let { it.applicationContext ?: it }
            }.getOrNull()
        }
        if (ctx == null) {
            contextLookupFailed = true
        } else {
            // send() calls this while holding zygoteLock. Cache the successful
            // lookup so normal hook traffic uses the lock-free fast path.
            localContext = ctx
            contextLookupFailed = false
        }
        return ctx
    }

    private fun isProviderBackoffActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs < providerUnavailableUntilMs
    }

    private fun markProviderUnavailable() {
        val now = System.currentTimeMillis()
        providerUnavailableUntilMs = now + PROVIDER_BACKOFF_MS
        lastProviderFailureAtMs.set(now)
    }

    /**
     * Record only in-memory state. Do not call Logger, XLog, ContentResolver or
     * any other transport from this method: drop diagnostics must not recurse.
     */
    private fun recordDrop(reason: DropReason) {
        droppedCount.incrementAndGet()
        when (reason) {
            DropReason.QUEUE_FULL -> droppedQueueFullCount.incrementAndGet()
            DropReason.ZYGOTE_OVERFLOW -> droppedZygoteOverflowCount.incrementAndGet()
            DropReason.BACKOFF -> droppedBackoffCount.incrementAndGet()
            DropReason.PROVIDER_NULL -> providerNullCount.incrementAndGet()
            DropReason.PROVIDER_EXCEPTION -> providerExceptionCount.incrementAndGet()
        }
    }

    /** Test-only state reset; it does not alter the bounded executor itself. */
    internal fun resetForTest() {
        synchronized(zygoteLock) {
            zygoteBuffer.clear()
            localContext = null
            cachedCurrentApplication = null
            reflectionResolved = false
            contextLookupFailed = false
            providerUnavailableUntilMs = 0L
        }
        logExecutor.queue.clear()
        droppedCount.set(0)
        acceptedCount.set(0)
        deliveredCount.set(0)
        droppedQueueFullCount.set(0)
        droppedZygoteOverflowCount.set(0)
        droppedBackoffCount.set(0)
        providerNullCount.set(0)
        providerExceptionCount.set(0)
        lastProviderFailureAtMs.set(0L)
    }

    private fun levelFor(priority: Int): String = when (priority) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        Log.INFO -> "I"
        Log.DEBUG -> "D"
        else -> "V"
    }
}
