package io.github.magisk317.xposed.runtime.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrameworkIdentityResolverTest {
    @Test
    fun `service values win over the values recorded at the last bind`() {
        val identity = FrameworkIdentityResolver.resolve(
            serviceName = "LSPosed",
            serviceVersion = "1.9.2",
            lastName = "Old",
            lastVersion = "1.0",
        )

        assertEquals(ResolvedFrameworkIdentity("LSPosed", "1.9.2"), identity)
    }

    @Test
    fun `a missing service name falls back to the recorded name`() {
        val identity = FrameworkIdentityResolver.resolve(
            serviceName = null,
            serviceVersion = "1.9.2",
            lastName = "LSPosed",
            lastVersion = "1.0",
        )

        assertEquals(ResolvedFrameworkIdentity("LSPosed", "1.9.2"), identity)
    }

    @Test
    fun `a missing service version falls back to the recorded version independently of the name`() {
        val identity = FrameworkIdentityResolver.resolve(
            serviceName = "LSPosed",
            serviceVersion = null,
            lastName = "Old",
            lastVersion = "1.0",
        )

        assertEquals(ResolvedFrameworkIdentity("LSPosed", "1.0"), identity)
    }

    @Test
    fun `blank and unknown values count as missing and usable values are trimmed`() {
        val identity = FrameworkIdentityResolver.resolve(
            serviceName = "  ",
            serviceVersion = "unknown",
            lastName = " LSPosed ",
            lastVersion = " 1.0 ",
        )

        assertEquals(ResolvedFrameworkIdentity("LSPosed", "1.0"), identity)
    }

    @Test
    fun `values with no usable source resolve to null`() {
        val identity = FrameworkIdentityResolver.resolve(
            serviceName = "unknown",
            serviceVersion = "",
            lastName = null,
            lastVersion = "  unknown ",
        )

        assertEquals(ResolvedFrameworkIdentity(null, null), identity)
    }
}
