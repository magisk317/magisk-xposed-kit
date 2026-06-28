package io.github.magisk317.xposed

import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Base class for Xposed module entries backed by libxposed.
 *
 * Consolidates the boilerplate shared across XposedSmsCode, xinyi-relay, and MiPushFramework:
 * - API version gating
 * - Hot reload state serialization/deserialization
 * - ClassLoader resolution via ActivityThread reflection
 * - System server ClassLoader sentinel class detection
 * - Hook dispatch loop with deduplication
 *
 * Subclasses must implement:
 * - [hooks]: the list of [BaseHook] instances to dispatch
 * - [installModuleRuntime]: install the module-specific runtime (HookEnv, CoreRuntime, bridges, etc.)
 *
 * Subclasses may override:
 * - [resolvePackageLoadedClassLoader]: customize classLoader resolution per-package
 * - [resolveCurrentProcessTargets]: customize which targets to dispatch during hot reload
 * - [buildDispatchKey]: customize deduplication key
 * - [minApiVersion]: change the minimum required API version
 * - [logTag]: change the log tag
 */
abstract class BaseLibXposedEntry : XposedModule {

    // -- Abstract contract --

    /** The hook instances to dispatch for each loaded package. */
    protected abstract val hooks: List<BaseHook>

    /**
     * Install module-specific runtime components. Called during [onModuleLoaded] and [onHotReloaded].
     * Implementations should install [HookEnv], [XposedRuntime], and any module-specific bridges here.
     *
     * @param module This xposed module instance.
     * @param hookApi The [LibXposedHookApi] to install into [HookEnv].
     */
    protected abstract fun installModuleRuntime(module: XposedModule, hookApi: LibXposedHookApi)

    // -- Configurable --

    /** Minimum libxposed API version. Subclasses can override. */
    protected open val minApiVersion: Int = MIN_LIBXPOSED_API_VERSION

    /** Log tag for this module. Subclasses can override. */
    protected open val logTag: String = "BaseLibXposedEntry"

    // -- State --

    protected var processName: String = "unknown"
        private set

    protected val loadedPackages = ConcurrentHashMap<String, ClassLoader>()
    private val dispatchedPackages = ConcurrentHashMap<String, String>()

    // -- Constructors --

    @Suppress("unused", "UnusedParameter")
    constructor(xposed: XposedInterface, loadedParam: ModuleLoadedParam) : super()

    constructor() : super()

    // -- Lifecycle --

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        val api = apiVersion
        if (api < minApiVersion) {
            Log.w(logTag, "skipped: apiVersion=$api < $minApiVersion")
            return
        }

        val hookApi = LibXposedHookApi(this)
        installModuleRuntime(this, hookApi)
        HookEnv.init(hookApi)
        processName = if (param.isSystemServer) "android" else param.processName

        installInitZygoteHooks()
        dispatchCurrentLoadedTargets(param, phase = "moduleLoadedCurrentProcess")
        logInfo("onModuleLoaded api=$apiVersion process=$processName framework=$frameworkName($frameworkVersionCode)")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        loadedPackages.put("android", param.classLoader)
        val loadParam = LoadParam("android", processName, param.classLoader)
        dispatchLoad(loadParam, phase = "systemServerStarting")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val classLoader = resolvePackageLoadedClassLoader(param)
        loadedPackages.putIfAbsent(param.packageName, classLoader)
        val loadParam = LoadParam(param.packageName, processName, classLoader)
        dispatchLoad(loadParam, phase = "packageLoaded")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        loadedPackages.put(param.packageName, param.classLoader)
        val loadParam = LoadParam(param.packageName, processName, param.classLoader)
        dispatchLoad(loadParam, phase = "packageReady")
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return runCatching {
            param.setSavedInstanceState(createHotReloadState())
            cleanupForHotReload()
            true
        }.getOrElse { t ->
            Log.e(logTag, "hot reload rejected: ${t.message}", t)
            false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val hookApi = LibXposedHookApi(this)
        installModuleRuntime(this, hookApi)
        HookEnv.init(hookApi)
        val oldHookHandles = param.oldHookHandles
        hookApi.beginHotReload(oldHookHandles)
        val removed = try {
            installInitZygoteHooks()
            restoreHotReloadState(param.savedInstanceState, oldHookHandles)
            val currentTargets = resolveCurrentProcessTargets(param, oldHookHandles)
            currentTargets.forEach { (pkg, cl) ->
                loadedPackages.putIfAbsent(pkg, cl)
            }
            loadedPackages.forEach { (pkg, cl) ->
                dispatchLoad(LoadParam(pkg, processName, cl), phase = "hotReload")
            }
            hookApi.finishHotReload()
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            hookApi.abortHotReload()
            Log.e(logTag, "hot reload failed", t)
            throw t
        }
        logInfo("onHotReloaded: replaced hooks, removed $removed stale hooks")
    }

    // -- Hook dispatch --

    private fun installInitZygoteHooks() {
        for (hook in hooks) {
            if (hook.hookInitZygote()) {
                runCatching { hook.initZygote() }
                    .onFailure { Log.e(logTag, "initZygote failed: ${hook.javaClass.simpleName}", it) }
            }
        }
    }

    protected open fun dispatchLoad(loadParam: LoadParam, phase: String) {
        val dispatchKey = buildDispatchKey(loadParam)
        val previousPhase = dispatchedPackages.putIfAbsent(dispatchKey, phase)
        if (previousPhase != null) {
            logDebug("skip duplicate package dispatch: pkg=%s process=%s phase=%s previous=%s",
                loadParam.packageName, loadParam.processName, phase, previousPhase)
            return
        }
        logDebug("Loaded package: ${loadParam.packageName} process: ${loadParam.processName}")
        if ("android" == loadParam.packageName || "system" == loadParam.packageName) {
            logWarn("Android/system package loaded: pkg=%s process=%s",
                loadParam.packageName, loadParam.processName)
        }
        for (hook in hooks) {
            val hookName = hook.javaClass.simpleName
            val shouldLoad = hook.hookOnLoadPackage()
            if (!shouldLoad) continue
            runCatching {
                hook.onLoadPackage(loadParam)
            }.onFailure { throwable ->
                logError("%s failed for pkg=%s process=%s", hookName,
                    loadParam.packageName, loadParam.processName, throwable)
            }
        }
        postDispatch(loadParam)
    }

    /**
     * Called after all hooks have been dispatched for a package.
     * Subclasses can override to add package-specific logic (diagnostics, extra hooks, etc.)
     * without replacing the entire dispatch pipeline.
     */
    protected open fun postDispatch(loadParam: LoadParam) {}

    protected open fun buildDispatchKey(loadParam: LoadParam): String {
        return "${loadParam.packageName}|${loadParam.processName.ifBlank { loadParam.packageName }}"
    }

    // -- ClassLoader resolution --

    /**
     * Resolve the classLoader for a package during [onPackageLoaded].
     * On Q+ uses [PackageLoadedParam.defaultClassLoader]; on pre-Q falls back
     * to ActivityThread reflection for devices where defaultClassLoader is unreliable.
     */
    protected open fun resolvePackageLoadedClassLoader(param: PackageLoadedParam): ClassLoader {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return param.defaultClassLoader
        }
        return resolveLoadedPackageClassLoader(param.packageName) ?: resolveContextClassLoader()
    }

    /**
     * Resolve the classLoader for a loaded package by name.
     * Uses ActivityThread reflection to access mPackages/mResourcePackages.
     * Falls back to the current Application's classLoader when the package matches the host process.
     */
    protected fun resolveLoadedPackageClassLoader(packageName: String): ClassLoader? {
        if (packageName == "android" || packageName == "system") {
            return resolveSystemServerClassLoader()
        }
        val resolved = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            listOf("mPackages", "mResourcePackages").firstNotNullOfOrNull { fieldName ->
                val field = activityThreadClass.getDeclaredField(fieldName).apply { isAccessible = true }
                val packages = field.get(activityThread) as? Map<*, *> ?: return@firstNotNullOfOrNull null
                val loadedApkRef = packages[packageName] as? WeakReference<*> ?: return@firstNotNullOfOrNull null
                val loadedApk = loadedApkRef.get() ?: return@firstNotNullOfOrNull null
                loadedApk::class.java
                    .getDeclaredMethod("getClassLoader")
                    .apply { isAccessible = true }
                    .invoke(loadedApk) as? ClassLoader
            }
        }.getOrElse { t ->
            logWarn("hot reload classloader resolve failed for $packageName: ${t.message}")
            null
        }
        if (resolved != null) return resolved
        // Fallback: if this package is the host app, use its classLoader directly
        val app = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            activityThreadClass.getDeclaredMethod("currentApplication").invoke(null) as? android.app.Application
        }.getOrNull()
        if (app != null && app.packageName == packageName) {
            logInfo("hot reload classloader fallback via currentApplication for $packageName")
            return app.classLoader
        }
        return null
    }

    protected fun resolveLoadedPackageClassLoader(
        packageName: String,
        oldHookHandles: List<XposedInterface.HookHandle>,
    ): ClassLoader? {
        if (packageName == "android" || packageName == "system") {
            resolveSystemServerClassLoaderFromOldHookHandles(oldHookHandles)?.let { return it }
        }
        return resolveLoadedPackageClassLoader(packageName)
    }

    /**
     * Resolve the system server classLoader.
     * During hot reload, callback thread context classloaders may point to the module or
     * binder runtime instead of services.jar. Callers that have old hook handles should use
     * [resolveSystemServerClassLoader] with handles so the previous hooked class can identify
     * the real system server classloader.
     */
    protected open fun resolveSystemServerClassLoader(): ClassLoader? {
        resolveSystemServerClassLoaderFromLoadedClass()?.let { return it }
        val candidates = listOfNotNull(
            runCatching { android.os.Looper.getMainLooper().thread.contextClassLoader }.getOrNull(),
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()
        return candidates.firstOrNull { canLoadSystemServerHooks(it) } ?: candidates.firstOrNull()
    }

    protected open fun resolveSystemServerClassLoader(
        oldHookHandles: List<XposedInterface.HookHandle>,
    ): ClassLoader? {
        return resolveSystemServerClassLoaderFromOldHookHandles(oldHookHandles)
            ?: resolveSystemServerClassLoaderFromLoadedClass()
            ?: resolveSystemServerClassLoader()
    }

    protected fun resolveContextClassLoader(): ClassLoader {
        return Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    }

    /**
     * Resolve classLoaders for targets that should be dispatched during hot reload.
     * Default handles system server and common telephony processes (phone, mms, telephony provider).
     * Subclasses override to customize target resolution.
     */
    protected open fun resolveCurrentProcessTargets(param: ModuleLoadedParam): Map<String, ClassLoader> {
        val process = if (param.isSystemServer) "android" else param.processName
        return when (process) {
            "android", "system", "system_server" -> {
                resolveSystemServerClassLoader()?.let { mapOf("android" to it) } ?: emptyMap()
            }
            "com.android.phone", "com.xiaomi.phone" -> {
                val classLoader = resolveLoadedPackageClassLoader(process) ?: resolveContextClassLoader()
                mapOf(process to classLoader)
            }
            "com.android.mms", "com.android.mms:mms_service" -> {
                val classLoader = resolveLoadedPackageClassLoader("com.android.mms") ?: resolveContextClassLoader()
                mapOf("com.android.mms" to classLoader)
            }
            "com.android.providers.telephony" -> {
                val classLoader = resolveLoadedPackageClassLoader(process) ?: resolveContextClassLoader()
                mapOf(process to classLoader)
            }
            else -> emptyMap()
        }
    }

    /**
     * Hot-reload variant of [resolveCurrentProcessTargets].
     *
     * Existing consumers can keep overriding the old signature. For system server hot reloads,
     * this base implementation first resolves a verified services.jar classloader from old
     * hook handles, then falls back to the consumer's old override.
     */
    protected open fun resolveCurrentProcessTargets(
        param: ModuleLoadedParam,
        oldHookHandles: List<XposedInterface.HookHandle>,
    ): Map<String, ClassLoader> {
        val process = if (param.isSystemServer) "android" else param.processName
        if (process == "android" || process == "system" || process == "system_server") {
            resolveSystemServerClassLoader(oldHookHandles)?.let { return mapOf("android" to it) }
        }
        return resolveCurrentProcessTargets(param)
    }

    /**
     * Resolve targets that were already loaded before module initialization.
     * Called during [onModuleLoaded] to dispatch hooks to long-running processes
     * (e.g. com.android.phone) that won't trigger [onPackageReady].
     * Default handles common telephony processes. Subclasses override to customize.
     */
    protected open fun resolveCurrentLoadedTargets(param: ModuleLoadedParam): Map<String, ClassLoader> {
        val process = if (param.isSystemServer) "android" else param.processName
        if (process == "android" || process == "system" || process == "system_server") {
            return emptyMap()
        }
        val packageName = when (process) {
            "com.android.phone", "com.xiaomi.phone", "com.android.providers.telephony" -> process
            "com.android.mms", "com.android.mms:mms_service" -> "com.android.mms"
            else -> return emptyMap()
        }
        val classLoader = resolveLoadedPackageClassLoader(packageName) ?: return emptyMap()
        return mapOf(packageName to classLoader)
    }

    private fun dispatchCurrentLoadedTargets(param: ModuleLoadedParam, phase: String) {
        resolveCurrentLoadedTargets(param).forEach { (pkg, cl) ->
            loadedPackages.putIfAbsent(pkg, cl)
            dispatchLoad(LoadParam(pkg, processName, cl), phase = phase)
        }
    }

    // -- Hot reload state --

    private fun createHotReloadState(): Bundle {
        return Bundle().apply {
            putString(STATE_PROCESS_NAME, processName)
            putStringArrayList(STATE_LOADED_PACKAGES, ArrayList(loadedPackages.keys.sorted()))
        }
    }

    private fun cleanupForHotReload() {
        for (hook in hooks) {
            runCatching { hook.onHotReloading() }
                .onFailure { t ->
                    Log.e(logTag, "Hot reload cleanup failed: ${hook.javaClass.name}", t)
                }
        }
    }

    private fun restoreHotReloadState(
        savedState: Any?,
        oldHookHandles: List<XposedInterface.HookHandle> = emptyList(),
    ) {
        val state = savedState as? Bundle ?: return
        processName = state.getString(STATE_PROCESS_NAME) ?: processName
        val packages = state.getStringArrayList(STATE_LOADED_PACKAGES) ?: return
        loadedPackages.clear()
        packages.forEach { pkg ->
            val classLoader = resolveLoadedPackageClassLoader(pkg, oldHookHandles)
            if (classLoader == null) {
                Log.w(logTag, "Hot reload skipped package without classloader: $pkg")
            } else {
                loadedPackages.put(pkg, classLoader)
            }
        }
    }

    private fun resolveSystemServerClassLoaderFromOldHookHandles(
        oldHookHandles: List<XposedInterface.HookHandle>,
    ): ClassLoader? {
        val classLoaders = oldHookHandles.mapNotNull { handle ->
            runCatching { handle.executable.declaringClass.classLoader }.getOrNull()
        }.distinct()
        return classLoaders.firstOrNull { canLoadSystemServerHooks(it) }
            ?.also { logDebug("hot reload system-server classloader resolved from old hook handle") }
    }

    private fun resolveSystemServerClassLoaderFromLoadedClass(): ClassLoader? {
        return runCatching {
            Class.forName(SYSTEM_SERVER_PRIMARY_CLASS).classLoader
        }.getOrNull()?.also {
            logDebug("hot reload system-server classloader resolved from loaded class")
        }
    }

    private fun canLoadSystemServerHooks(classLoader: ClassLoader): Boolean {
        return SYSTEM_SERVER_SENTINEL_CLASSES.any { className ->
            runCatching { Class.forName(className, false, classLoader) }.isSuccess
        }
    }

    // -- Logging helpers --

    protected fun logDebug(format: String, vararg args: Any?) {
        XposedRuntime.log(Log.DEBUG, logTag, format.format(*args))
    }

    protected fun logInfo(message: String) {
        XposedRuntime.log(Log.INFO, logTag, message)
    }

    protected fun logWarn(format: String, vararg args: Any?) {
        XposedRuntime.log(Log.WARN, logTag, format.format(*args))
    }

    protected fun logError(format: String, vararg args: Any?) {
        val last = args.lastOrNull()
        val throwable = if (last is Throwable) last else null
        val msg = if (throwable != null) {
            format.format(*args.dropLast(1).toTypedArray())
        } else {
            format.format(*args)
        }
        XposedRuntime.log(Log.ERROR, logTag, msg, throwable)
    }

    companion object {
        private const val MIN_LIBXPOSED_API_VERSION = 102
        private const val STATE_PROCESS_NAME = "processName"
        private const val STATE_LOADED_PACKAGES = "loadedPackages"
        private const val SYSTEM_SERVER_PRIMARY_CLASS = "com.android.server.notification.NotificationManagerService"
        private val SYSTEM_SERVER_SENTINEL_CLASSES = listOf(
            "com.android.server.SystemServer",
            "com.android.server.SystemService",
            SYSTEM_SERVER_PRIMARY_CLASS,
            "com.android.server.am.ActivityManagerService",
        )
    }
}
