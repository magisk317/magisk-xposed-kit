package io.github.magisk317.xposed.diagnostics

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object DiagnosticTextSanitizer {
    private const val STREAM_BUFFER_CHARS = 128 * 1024
    private const val SANITIZE_PARALLELISM_CAP = 8

    fun sanitizeDirectory(
        root: File,
        sanitizeText: (String) -> String,
        maxFileBytes: Long = Long.MAX_VALUE,
        onWarning: (String) -> Unit = {},
        includeFile: (File) -> Boolean = { true },
        parallel: Boolean = true,
    ) {
        if (!root.exists()) return
        val files = root.walkTopDown()
            .filter(File::isFile)
            .filter(includeFile)
            .filter(::shouldSanitizeLogFile)
            .toList()
        if (files.isEmpty()) return

        if (!parallel || files.size == 1) {
            files.forEach { file ->
                sanitizeTextLogFile(file, sanitizeText, maxFileBytes, onWarning)
            }
            return
        }

        val workers = Runtime.getRuntime().availableProcessors()
            .coerceAtLeast(2)
            .coerceAtMost(SANITIZE_PARALLELISM_CAP)
            .coerceAtMost(files.size)
        val pool = Executors.newFixedThreadPool(workers)
        val failures = AtomicInteger(0)
        try {
            val futures: List<Future<*>> = files.map { file ->
                pool.submit {
                    runCatching {
                        sanitizeTextLogFile(file, sanitizeText, maxFileBytes, onWarning)
                    }.onFailure {
                        failures.incrementAndGet()
                        onWarning(
                            "Parallel sanitize failed: ${file.name} " +
                                "err=${it.message ?: it.javaClass.simpleName}",
                        )
                    }
                }
            }
            futures.forEach { future ->
                runCatching { future.get() }
            }
        } finally {
            pool.shutdown()
            runCatching { pool.awaitTermination(2, TimeUnit.MINUTES) }
        }
        if (failures.get() > 0) {
            onWarning("sanitizeDirectory completed with failures=${failures.get()}")
        }
    }

    fun sanitizeTextLogFile(
        file: File,
        sanitizeText: (String) -> String,
        maxFileBytes: Long = Long.MAX_VALUE,
        onWarning: (String) -> Unit = {},
    ): Boolean {
        if (!shouldSanitizeLogFile(file)) return false
        if (!file.exists()) {
            onWarning("Skip missing diagnostic text file: ${file.name}")
            return false
        }
        // maxFileBytes is retained for API compatibility but no longer truncates or deletes.
        // Full content is always preserved and stream-sanitized.
        if (maxFileBytes > 0L && maxFileBytes < Long.MAX_VALUE && file.length() > maxFileBytes) {
            onWarning(
                "Sanitizing large diagnostic text file without truncation: ${file.name} " +
                    "size=${file.length()} softLimit=$maxFileBytes",
            )
        }
        return runCatching {
            sanitizeStreaming(file, sanitizeText)
        }.getOrElse { error ->
            removeUnsafeTextFile(file, "sanitization failed", onWarning, error)
        }
    }

    private fun sanitizeStreaming(file: File, sanitizeText: (String) -> String): Boolean {
        val parent = file.parentFile ?: return false
        val temp = File(parent, ".sanitize_${file.name}.${Thread.currentThread().id}.tmp")
        if (temp.exists() && !temp.delete()) {
            throw IllegalStateException("Unable to clear temp sanitize file: ${temp.name}")
        }
        var changed = false
        BufferedReader(
            InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8),
            STREAM_BUFFER_CHARS,
        ).use { reader ->
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(temp), StandardCharsets.UTF_8),
                STREAM_BUFFER_CHARS,
            ).use { writer ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val sanitized = sanitizeText(line)
                    if (sanitized != line) changed = true
                    writer.write(sanitized)
                    writer.newLine()
                }
            }
        }
        if (!changed) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return true
    }

    private fun removeUnsafeTextFile(
        file: File,
        reason: String,
        onWarning: (String) -> Unit,
        cause: Throwable? = null,
    ): Boolean {
        if (!file.exists() || file.delete()) {
            onWarning("Removed $reason diagnostic text file: ${file.name}")
            return true
        }
        throw IllegalStateException(
            "Unable to sanitize or remove diagnostic text file: ${file.name}",
            cause,
        )
    }

    fun shouldSanitizeLogFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        return name.endsWith(".jsonl") ||
            name.endsWith(".log") ||
            name.endsWith(".txt") ||
            name == "props"
    }
}
