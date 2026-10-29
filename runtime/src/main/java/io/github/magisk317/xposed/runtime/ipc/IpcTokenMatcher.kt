package io.github.magisk317.xposed.runtime.ipc

import java.security.MessageDigest

object IpcTokenMatcher {
    private const val MAX_TOKEN_BYTES = 4 * 1024

    fun matches(expectedToken: String?, receivedToken: String?): Boolean {
        if (expectedToken.isNullOrBlank() || receivedToken.isNullOrBlank()) return false
        val expectedBytes = expectedToken.toByteArray(Charsets.UTF_8)
        val receivedBytes = receivedToken.toByteArray(Charsets.UTF_8)
        if (expectedBytes.size > MAX_TOKEN_BYTES || receivedBytes.size > MAX_TOKEN_BYTES) return false
        return MessageDigest.isEqual(expectedBytes, receivedBytes)
    }
}
