package io.github.magisk317.xposed.backup.webdav

import kotlinx.serialization.Serializable

@Serializable
data class WebDavConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remotePath: String = DEFAULT_REMOTE_PATH,
) {
    /** Base URL without a trailing slash; transports build request URLs through HttpUrl so every path segment is encoded. */
    val baseUrl: String
        get() = serverUrl.trimEnd('/')

    companion object {
        const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"
        const val DEFAULT_REMOTE_PATH = "/xinyi-relay/backups/"
    }
}
