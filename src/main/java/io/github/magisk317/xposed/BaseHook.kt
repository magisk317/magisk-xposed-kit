package io.github.magisk317.xposed

/**
 * Base class for Xposed hook implementations.
 *
 * Subclasses implement [onLoadPackage] to install hooks when a target package is loaded.
 * Override [hookOnLoadPackage] to control whether this hook should be dispatched (default true).
 * Override [hookInitZygote] and [initZygote] for zygote-level hooks.
 * Override [onHotReloading] for cleanup before hot reload.
 */
open class BaseHook {

    /** If true, [onLoadPackage] will be called for each loaded package. */
    open fun hookOnLoadPackage(): Boolean = true

    /**
     * If true, [onLoadPackage] is deferred from the packageLoaded callback to a callback where
     * the target classLoader is fully constructed (packageReady, hotReload,
     * moduleLoadedCurrentProcess, systemServerStarting).
     *
     * Enable this for hooks that load target classes, install process-wide probes, or reflect
     * over members: running them inside `LoadedApk.createAppFactory` can deadlock on class
     * initialization while the framework still holds the LoadedApk lock.
     *
     * The default is false, preserving the historical packageLoaded dispatch.
     */
    open fun deferUntilPackageReady(): Boolean = false

    /** If true, [initZygote] will be called during module initialization. */
    open fun hookInitZygote(): Boolean = false

    /** Called during zygote initialization if [hookInitZygote] returns true. */
    @Throws(Throwable::class)
    open fun initZygote() {}

    /** Called when a package is loaded, if [hookOnLoadPackage] returns true. */
    @Throws(Throwable::class)
    open fun onLoadPackage(param: LoadParam) {}

    /** Called before hot reload to allow cleanup. */
    @Throws(Throwable::class)
    open fun onHotReloading() {}

    /**
     * Convenience: install this hook's hooks into the given classLoader.
     * Subclasses can override to provide custom dispatch logic.
     */
    open fun hook(classLoader: ClassLoader) {
        val param = LoadParam("", "", classLoader)
        onLoadPackage(param)
    }

    /**
     * Convenience: install this hook's hooks with full load param context.
     */
    open fun hook(param: LoadParam) {
        onLoadPackage(param)
    }
}
