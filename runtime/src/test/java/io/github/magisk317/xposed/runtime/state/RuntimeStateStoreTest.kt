package io.github.magisk317.xposed.runtime.state

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeStateStoreTest {

    @AfterEach
    fun tearDown() {
        RuntimeStateStore.setRuntimeConnected(false)
    }

    @Test
    fun `the store owns the live connection flag`() {
        assertFalse(RuntimeStateStore.isRuntimeConnected())

        RuntimeStateStore.setRuntimeConnected(true)
        assertTrue(RuntimeStateStore.isRuntimeConnected())

        RuntimeStateStore.setRuntimeConnected(false)
        assertFalse(RuntimeStateStore.isRuntimeConnected())
    }
}
