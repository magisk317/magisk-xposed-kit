package io.github.magisk317.xposed.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RuntimeStateProviderAddressTest {

    @Test
    fun `the authority is derived from the module id`() {
        assertEquals(
            "io.github.magisk317.xinyi.relay.db.provider",
            RuntimeStateProviderAddress.authorityOf("io.github.magisk317.xinyi.relay"),
        )
    }

    @Test
    fun `the hooked app's package never becomes the authority`() {
        // The hook process runs inside another package, so addressing the provider by that
        // package silently no-ops: that is exactly the signal the activation status used to
        // depend on.
        assertNotEquals(
            RuntimeStateProviderAddress.authorityOf("com.android.phone"),
            RuntimeStateProviderAddress.authorityOf("io.github.magisk317.xinyi.relay"),
        )
    }
}
