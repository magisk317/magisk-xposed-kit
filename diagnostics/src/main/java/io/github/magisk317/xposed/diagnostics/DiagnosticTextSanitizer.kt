package io.github.magisk317.xposed.diagnostics

import java.io.File
import java.util.Locale

object DiagnosticTextSanitizer {
    private const val DEFAULT_MAX_FILE_BYTES = 20 * 1024 * 1024L

    fun sanitizeDirectory(
        root: File,
        sanitizeText: (String) -> String,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        onWarning: (String) -> Unit = {},
    ) {
        if (!root.exists()) return
        root.walkTopDown()
            .filter(File::isFile)
            .forEach { file ->
                sanitizeTextLogFile(file, sanitizeText, maxFileBytes, onWarning)
            }
    }

    fun sanitizeTextLogFile(
        file: File,
        sanitizeText: (String) -> String,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        onWarning: (String) -> Unit = {},
    ): Boolean {
        if (!shouldSanitizeLogFile(file)) return false
        if (!file.exists()) {
            onWarning("Skip missing diagnostic text file: ${file.name}")
            return false
        }
        if (file.length() > maxFileBytes) {
            return removeUnsafeTextFile(file, "oversized", onWarning)
        }
        return runCatching {
            val original = file.readText()
            val sanitized = sanitizeText(original)
            if (sanitized == original) {
                false
            } else {
                file.writeText(sanitized)
                true
            }
        }.getOrElse { error ->
            removeUnsafeTextFile(file, "sanitization failed", onWarning, error)
        }
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
