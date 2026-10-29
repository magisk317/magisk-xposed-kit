package io.github.magisk317.xposed.capability.utils

import io.github.magisk317.xposed.logging.XLog
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleUtilsTest {

    @BeforeEach
    fun setUp() {
        XLog.setTestSink { _, _ -> }
    }

    @AfterEach
    fun tearDown() {
        ModuleUtils.moduleVersionResolverForTesting = null
        ModuleUtils.setRuntimeActivated(false)
        XLog.setTestSink(null)
    }

    @Test
    fun isModuleEnabled_returnsFalseWhenModuleVersionLookupThrows() {
        ModuleUtils.moduleVersionResolverForTesting = {
            throw NullPointerException("module version unavailable")
        }

        assertFalse(ModuleUtils.isModuleEnabled())
    }

    @Test
    fun isModuleEnabled_returnsTrueWhenModuleVersionLookupSucceeds() {
        ModuleUtils.moduleVersionResolverForTesting = { 111 }

        assertTrue(ModuleUtils.isModuleEnabled())
    }
}
