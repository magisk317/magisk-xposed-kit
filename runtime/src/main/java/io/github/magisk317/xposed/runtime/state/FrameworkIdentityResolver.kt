package io.github.magisk317.xposed.runtime.state

data class ResolvedFrameworkIdentity(val name: String?, val version: String?)

object FrameworkIdentityResolver {
    /**
     * Service values win when the service is bound; otherwise the values recorded at the last bind are used.
     * Blank values and the placeholder "unknown" are treated as missing and become null.
     */
    fun resolve(
        serviceName: String?,
        serviceVersion: String?,
        lastName: String?,
        lastVersion: String?,
    ): ResolvedFrameworkIdentity = ResolvedFrameworkIdentity(
        name = pick(serviceName, lastName),
        version = pick(serviceVersion, lastVersion),
    )

    private fun pick(primary: String?, fallback: String?): String? = clean(primary) ?: clean(fallback)

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" }
}
