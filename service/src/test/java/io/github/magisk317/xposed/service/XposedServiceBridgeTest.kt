package io.github.magisk317.xposed.service

import io.github.libxposed.service.XposedServiceHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bind/died transitions need a live `IXposedService`, which a JVM unit test cannot construct, so
 * this pins only what the bridge owns outright: the call-once wrapper around `registerListener`.
 */
class XposedServiceBridgeTest {

    @Test
    fun `a failed registration stays retriable and a good one happens once`() {
        val seen = mutableListOf<XposedServiceHelper.OnServiceListener>()
        val errors = mutableListOf<String>()
        assertEquals(ServiceState.Waiting, XposedServiceBridge.state.value)

        val first = XposedServiceBridge.register(
            onError = { operation, _ -> errors.add(operation) },
            registrar = { throw IllegalStateException("framework not reachable") },
        )
        assertFalse(first)
        assertEquals(listOf("register Xposed service listener"), errors)

        val second = XposedServiceBridge.register(
            onError = { operation, _ -> errors.add(operation) },
            registrar = { seen.add(it) },
        )
        assertTrue(second)
        assertEquals(1, seen.size)

        assertFalse(XposedServiceBridge.register(registrar = { seen.add(it) }))
        assertEquals(1, seen.size)
        assertFalse(XposedServiceBridge.isBound)
        assertNull(XposedServiceBridge.currentService)
    }
}
