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

    /**
     * Count of events discarded due to queue saturation. Useful for diagnostics.
     */
    val droppedCount: AtomicLong = AtomicLong(0)

    private val logExecutor = ThreadPoolExecutor(
        0, 1, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(256),
        RejectedExecutionHandler { _, _ -> droppedCount.incrementAndGet() },
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

    fun configure(authority: String, source: String) {
        this.authority = authority
        this.source = source
    }

    fun attachContext(context: Context) {
        synchronized(zygoteLock) {
            localContext = context.applicationContext ?: context
            contextLookupFailed = false
            flushZygoteBufferLocked()
        }
    }

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
    ) {
        emit(levelFor(priority), tag, message, null, force = force, route = route, sensitive = sensitive)
    }

    fun send(event: XposedLogEvent) {
        // Fast path: context already available, no lock needed
        val fastCtx = localContext
        if (fastCtx != null) {
            submitToExecutor(fastCtx, event)
            return
        }
        synchronized(zygoteLock) {
            val ctx = localContext ?: currentApplicationContext()
            if (ctx == null) {
                // No context yet (Zygote phase) — buffer for later flush
                while (zygoteBuffer.size >= ZYGOTE_BUFFER_LIMIT) {
                    zygoteBuffer.poll()
                }
                zygoteBuffer.offer(event)
                return
            }
            submitToExecutor(ctx, event)
        }
    }

    private fun submitToExecutor(ctx: Context, event: XposedLogEvent) {
        runCatching {
            logExecutor.execute {
                runCatching {
                    val uri = XposedLogEvent.appendUri(authority)
                    ctx.contentResolver.insert(uri, event.toContentValues())
                }
            }
        }
        // If execute() is rejected (queue full), the RejectedExecutionHandler increments droppedCount
    }

    private fun emit(
        level: String,
        tag: String,
        message: String?,
        throwable: Throwable?,
        force: Boolean = false,
        route: String? = null,
        sensitive: Boolean = false,
    ) {
        val event = XposedLogEvent(
            source = source,
            level = level,
            tag = tag,
            message = message ?: "",
            throwable = throwable?.stackTraceToString() ?: "",
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

    private fun flushZygoteBufferLocked() {
        val ctx = localContext ?: return
        while (true) {
            val event = zygoteBuffer.poll() ?: break
            submitToExecutor(ctx, event)
        }
    }

    private fun currentApplicationContext(): Context? {
        if (contextLookupFailed) return null
        if (reflectionResolved) {
            val ctx = cachedCurrentApplication?.let { method ->
                runCatching { (method.invoke(null) as? Context)?.applicationContext }.getOrNull()
            }
            if (ctx == null) contextLookupFailed = true
            return ctx
        }
        val ctx = runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getDeclaredMethod("currentApplication").also {
                it.isAccessible = true
                cachedCurrentApplication = it
            }
            reflectionResolved = true
            (method.invoke(null) as? Context)?.applicationContext
        }.getOrNull()
        if (ctx == null) contextLookupFailed = true
        return ctx
    }

    private fun levelFor(priority: Int): String = when (priority) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        Log.INFO -> "I"
        Log.DEBUG -> "D"
        else -> "V"
    }
}
