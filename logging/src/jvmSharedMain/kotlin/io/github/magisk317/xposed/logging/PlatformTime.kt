package io.github.magisk317.xposed.logging

// Both targets of this module are JVM-family, so the wall clock has exactly one real
// implementation; it lives in the shared source set rather than being duplicated.
internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()
