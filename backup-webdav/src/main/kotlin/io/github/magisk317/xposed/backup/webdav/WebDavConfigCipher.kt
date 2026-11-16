package io.github.magisk317.xposed.backup.webdav

import io.github.magisk317.xposed.backup.security.AesGcmEnvelopeCipher
import io.github.magisk317.xposed.backup.security.AesGcmKeyProvider

internal interface WebDavConfigCipher {
    fun encrypt(plaintext: String, aad: ByteArray): String

    fun decrypt(envelope: String, aad: ByteArray): String

    fun isCurrentEnvelope(value: String): Boolean
}

/**
 * Thin facade over the shared [AesGcmEnvelopeCipher] hosted in the backup
 * module; the WebDAV envelope stays "v2" so previously stored configuration
 * decrypts without migration.
 */
internal class AesGcmWebDavConfigCipher(
    keyProvider: AesGcmKeyProvider,
) : WebDavConfigCipher {
    private val delegate = AesGcmEnvelopeCipher(
        keyProvider = keyProvider,
        envelopeVersion = ENVELOPE_VERSION,
    )

    override fun encrypt(plaintext: String, aad: ByteArray): String = delegate.encrypt(plaintext, aad)

    override fun decrypt(envelope: String, aad: ByteArray): String = delegate.decrypt(envelope, aad)

    override fun isCurrentEnvelope(value: String): Boolean = delegate.isCurrentEnvelope(value)

    private companion object {
        const val ENVELOPE_VERSION = "v2"
    }
}
