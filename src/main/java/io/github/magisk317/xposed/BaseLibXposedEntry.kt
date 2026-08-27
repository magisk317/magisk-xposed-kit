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
import java.util.concurrent.atomic.AtomicInteger

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

    /** Prefix used to keep hook IDs stable and isolated between module consumers. */
    protected open val hookIdPrefix: String = "xposed-kit"

    // -- State --

    protected var processName: String = "unknown"
        private set

    protected val loadedPackages = ConcurrentHashMap<String, ClassLoader>()

    /**
     * Packages whose recorded classLoader came from a last-resort fallback (the callback thread's
     * context loader) rather than an authoritative source, mapped to that fallback loader's
     * identity. Keying on the loader identity is what lets [recordLoadedPackage] tell a repeat of
     * the same fallback (still provisional) apart from a genuinely better loader arriving later
     * (authoritative, and allowed to replace the entry).
     */
    private val provisionalPackages = ConcurrentHashMap<String, Int>()

    /**
     * Dispatch dedup, keyed by dispatch key **plus classLoader identity**. Keying on the package
     * alone lets a dispatch that used a wrong/fallback loader consume the key, so the later
     * callback carrying the real loader is skipped as a duplicate and the package never gets
     * working hooks until the process restarts.
     */
    private val dispatchedPackages = ConcurrentHashMap<String, String>()

    /** Per-package dispatch counter, bounding retries when loader resolution keeps changing. */
    private val dispatchCounts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Set while this thread is inside [dispatchLoad], to detect re-entrant dispatch.
     *
     * Anything a hook or [postDispatch] does that touches `LoadedApk` (most directly
     * `Context.createPackageContext`) runs the framework's `AppComponentFactory` path, which the
     * Xposed framework intercepts and turns into a *nested* `onPackageLoaded` on the same thread.
     * That nested callback sees a `LoadedApk` still under construction: its classLoader field is
     * null and its own `createPackageContext` would recurse again. Bail out instead.
     */
    private val dispatching = ThreadLocal.withInitial { false }

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

        val hookApi = LibXposedHookApi(this, hookIdPrefix)
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
        // Runs inside the target process; an escaping throwable is fatal to the host app.
        runCatching {
            // Re-entrant callback triggered from inside our own dispatch (see [dispatching]).
            // The LoadedApk being reported is half-constructed, so there is nothing safe to do.
            if (dispatching.get() == true) {
                logWarn("skip reentrant packageLoaded: pkg=%s process=%s", param.packageName, processName)
                return@runCatching
            }
            val classLoader = resolvePackageLoadedClassLoader(param) ?: return@runCatching
            recordLoadedPackage(param.packageName, classLoader)
            dispatchLoad(LoadParam(param.packageName, processName, classLoader), phase = "packageLoaded")
        }.onFailure { t ->
            logError("onPackageLoaded failed for pkg=%s process=%s", param.packageName, processName, t)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // onPackageReady always carries the real loader, so it supersedes any provisional entry.
        recordLoadedPackage(param.packageName, param.classLoader, authoritative = true)
        val loadParam = LoadParam(param.packageName, processName, param.classLoader)
        dispatchLoad(loadParam, phase = "packageReady")
    }

    /**
     * Record the classLoader for a package.
     *
     * A loader is authoritative unless it is the very fallback that was just marked provisional
     * for this package. An authoritative loader replaces a previously recorded provisional entry;
     * a provisional one is only recorded when nothing is known yet.
     */
    private fun recordLoadedPackage(
        packageName: String,
        classLoader: ClassLoader,
        authoritative: Boolean =
            provisionalPackages[packageName] != System.identityHashCode(classLoader),
    ) {
        if (authoritative) {
            provisionalPackages.remove(packageName)
            loadedPackages.put(packageName, classLoader)
        } else {
            loadedPackages.putIfAbsent(packageName, classLoader)
        }
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
        val hookApi = LibXposedHookApi(this, hookIdPrefix)
        // Platform type: the framework is not contractually bound to return non-null here.
        @Suppress("USELESS_ELVIS")
        val oldHookHandles = param.oldHookHandles ?: emptyList()
        hookApi.beginHotReload(oldHookHandles)
        val removed = try {
            // The runtime installer may itself create hooks (for example module-specific
            // fallback hooks). Index the old handles before invoking it so those hooks can be
            // replaced instead of being added as a second generation.
            installModuleRuntime(this, hookApi)
            HookEnv.init(hookApi)
            installInitZygoteHooks()
            // Hooks were torn down in onHotReloading, so previous dispatch bookkeeping no longer
            // reflects installed state. Clearing it lets every restored package dispatch again.
            dispatchedPackages.clear()
            dispatchCounts.clear()
            restoreHotReloadState(param.savedInstanceState, oldHookHandles)
            val currentTargets = resolveCurrentProcessTargets(param, oldHookHandles)
            currentTargets.forEach { (pkg, cl) ->
                recordLoadedPackage(pkg, cl)
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
        // Include the loader identity: the same package dispatched with a different classLoader is
        // a genuinely new dispatch, not a duplicate. Without this a fallback loader silently
        // consumes the key and the authoritative callback that follows is dropped.
        val loaderKey = "$dispatchKey#${System.identityHashCode(loadParam.classLoader)}"
        val previousPhase = dispatchedPackages.putIfAbsent(loaderKey, phase)
        if (previousPhase != null) {
            logDebug("skip duplicate package dispatch: pkg=%s process=%s phase=%s previous=%s",
                loadParam.packageName, loadParam.processName, phase, previousPhase)
            return
        }
        // Bound re-dispatch so a loader that keeps changing (or an unstable resolution path)
        // cannot make hooks pile up without limit on one package.
        val attempts = dispatchCounts.computeIfAbsent(dispatchKey) { AtomicInteger() }.incrementAndGet()
        if (attempts > MAX_DISPATCHES_PER_PACKAGE) {
            logWarn("skip package dispatch over limit: pkg=%s process=%s phase=%s attempts=%d",
                loadParam.packageName, loadParam.processName, phase, attempts)
            return
        }
        if (attempts > 1) {
            logInfo(
                "re-dispatching pkg=${loadParam.packageName} process=${loadParam.processName} " +
                    "phase=$phase attempt=$attempts loader=${Integer.toHexString(
                        System.identityHashCode(loadParam.classLoader),
                    )}",
            )
        }
        logDebug("Loaded package: ${loadParam.packageName} process: ${loadParam.processName}")
        if ("android" == loadParam.packageName || "system" == loadParam.packageName) {
            logWarn("Android/system package loaded: pkg=%s process=%s",
                loadParam.packageName, loadParam.processName)
        }
        // Hooks and postDispatch may touch LoadedApk (e.g. createPackageContext), which the
        // framework turns back into a nested onPackageLoaded on this thread. Mark the thread so
        // that nested callback can bail instead of acting on a half-built LoadedApk.
        dispatching.set(true)
        try {
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
        } finally {
            dispatching.set(false)
        }
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
    protected open fun resolvePackageLoadedClassLoader(param: PackageLoadedParam): ClassLoader? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // getDefaultClassLoader() is declared @NonNull, so a plain `val cl = param.x` makes
            // kotlinc emit an Intrinsics null assertion at the call site — the check below would
            // be dead code and the assertion would throw NPE instead. It really can be null (a
            // LoadedApk still under construction, early system_server phases), so read it through
            // a nullable platform-typed accessor to keep the assertion out.
            val cl = readDefaultClassLoader(param)
            if (cl != null) return cl
            logWarn("null defaultClassLoader for pkg=%s process=%s", param.packageName, processName)
        }
        resolveLoadedPackageClassLoader(param.packageName)?.let { return it }
        // Last resort: the callback thread's context loader. On a binder thread this is NOT the
        // target app's loader, so mark the entry provisional and let a later authoritative
        // callback replace it rather than treating it as settled.
        return provisionalClassLoader(param.packageName, "packageLoaded")
    }

    /**
     * Read `PackageLoadedParam.getDefaultClassLoader()` without Kotlin's non-null assertion.
     *
     * The API declares the getter `@NonNull`, so any direct Kotlin call site gets an
     * `Intrinsics.checkNotNullExpressionValue` inserted, which throws NPE before we can handle
     * the null. Going through reflection makes the result a genuinely nullable value.
     */
    private fun readDefaultClassLoader(param: PackageLoadedParam): ClassLoader? = runCatching {
        param.javaClass.getMethod("getDefaultClassLoader").invoke(param) as? ClassLoader
    }.getOrNull()


    private fun provisionalClassLoader(packageName: String, reason: String): ClassLoader {
        val fallback = resolveContextClassLoader()
        provisionalPackages.put(packageName, System.identityHashCode(fallback))
        logWarn("provisional classloader for pkg=%s reason=%s loader=%s",
            packageName, reason, Integer.toHexString(System.identityHashCode(fallback)))
        return fallback
    }

    /**
     * Resolve a target package's classLoader, degrading to a provisional context loader.
     *
     * The provisional marking matters: during hot reload the context loader is usually the
     * module's own loader rather than the target's, so hooks installed against it silently
     * find nothing. Marking the entry provisional lets a later authoritative callback
     * ([onPackageReady]) re-dispatch with the real loader instead of being deduped away.
     */
    private fun resolveTargetClassLoader(packageName: String): ClassLoader {
        return resolveLoadedPackageClassLoader(packageName)
            ?: provisionalClassLoader(packageName, "targetResolveFallback")
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
                mapOf(process to resolveTargetClassLoader(process))
            }
            "com.android.mms", "com.android.mms:mms_service" -> {
                mapOf("com.android.mms" to resolveTargetClassLoader("com.android.mms"))
            }
            "com.google.android.apps.messaging" -> {
                mapOf(process to resolveTargetClassLoader(process))
            }
            "com.android.providers.telephony" -> {
                mapOf(process to resolveTargetClassLoader(process))
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
            "com.google.android.apps.messaging" -> process
            else -> return emptyMap()
        }
        val classLoader = resolveLoadedPackageClassLoader(packageName) ?: return emptyMap()
        return mapOf(packageName to classLoader)
    }

    private fun dispatchCurrentLoadedTargets(param: ModuleLoadedParam, phase: String) {
        resolveCurrentLoadedTargets(param).forEach { (pkg, cl) ->
            recordLoadedPackage(pkg, cl)
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
                // The old hooks were already torn down by onHotReloading, so dropping the package
                // here loses its hooks until the process restarts. Keep it with a provisional
                // loader instead: the dispatch may find nothing, but the entry stays replaceable
                // by a later authoritative onPackageReady.
                loadedPackages.put(pkg, provisionalClassLoader(pkg, "hotReloadUnresolved"))
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

        /**
         * Upper bound on dispatches for one package|process. Allows an authoritative loader to
         * supersede a provisional one without letting an unstable resolution path install hooks
         * without limit.
         */
        private const val MAX_DISPATCHES_PER_PACKAGE = 3
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
