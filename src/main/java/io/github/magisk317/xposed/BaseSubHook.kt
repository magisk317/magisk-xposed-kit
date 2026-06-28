package io.github.magisk317.xposed

/**
 * Base class for sub-hooks that operate within a parent hook's classLoader context.
 *
 * The parent hook instantiates sub-hooks with the target classLoader, then calls [startHook].
 */
abstract class BaseSubHook(@JvmField protected val mClassLoader: ClassLoader) {
    abstract fun startHook()
}
