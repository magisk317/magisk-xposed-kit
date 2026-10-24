package io.github.magisk317.xposed.keepalive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private data class FakeMethod(val shape: MethodShape)

private val shape = MethodShape(
    owner = "com.android.server.am.ProcessRecord",
    name = "killLocked",
    parameterTypes = listOf("java.lang.String", "int"),
    returnType = "void",
)

private val target = IndexedHookTarget(
    capability = KeepAliveCapability.KILL_GUARD,
    shape = shape,
    valueIndex = 1,
)

class KeepAliveTargetsTest {

    @Test
    fun `exact descriptor resolves`() {
        val method = FakeMethod(shape)
        val resolution = resolveTargets(listOf(target), listOf(method)) { it.shape }
        assertTrue(resolution is MethodResolution.Resolved<FakeMethod>)
        assertEquals(method, (resolution as MethodResolution.Resolved<FakeMethod>).method)
    }

    /** A name-only match must not bind the hook to an arbitrary overload. */
    @Test
    fun `different parameter types do not resolve`() {
        val other = FakeMethod(shape.copy(parameterTypes = listOf("java.lang.String", "boolean")))
        val resolution = resolveTargets(listOf(target), listOf(other)) { it.shape }
        assertTrue(resolution is MethodResolution.Missing)
    }

    @Test
    fun `different return type does not resolve`() {
        val other = FakeMethod(shape.copy(returnType = "boolean"))
        val resolution = resolveTargets(listOf(target), listOf(other)) { it.shape }
        assertTrue(resolution is MethodResolution.Missing)
    }

    @Test
    fun `different owner does not resolve`() {
        val other = FakeMethod(shape.copy(owner = "com.android.server.am.ProcessList"))
        val resolution = resolveTargets(listOf(target), listOf(other)) { it.shape }
        assertTrue(resolution is MethodResolution.Missing)
    }

    /**
     * Two descriptors matching means the argument indexes cannot be trusted, so the
     * capability stays uninstalled rather than picking one.
     */
    @Test
    fun `duplicate descriptors are ambiguous`() {
        val resolution = resolveTargets(
            targets = listOf(target, target.copy(packageIndex = 0)),
            candidates = listOf(FakeMethod(shape), FakeMethod(shape)),
            describe = { it.shape },
        )
        assertTrue(resolution is MethodResolution.Ambiguous<*>)
        assertEquals(4, (resolution as MethodResolution.Ambiguous<*>).candidates.size)
    }
}
