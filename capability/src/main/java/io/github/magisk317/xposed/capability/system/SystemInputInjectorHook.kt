package io.github.magisk317.xposed.capability.system

import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputFallbackPolicy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.widget.Toast
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputAccessibilityResultContract
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputBroadcastContract
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputResultBroadcastContract
import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.capability.runtime.CoreHookPolicyHolder
import io.github.magisk317.xposed.HookHelpers
import io.github.magisk317.xposed.BaseHook
import io.github.magisk317.xposed.HookEnv
import io.github.magisk317.xposed.LoadParam
import io.github.magisk317.xposed.MethodHook
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.capability.prefs.CorePrefs
import io.github.magisk317.xposed.capability.utils.resolveStaticIntFieldOrDefault
import io.github.magisk317.xposed.runNonFatalCatching
import io.github.magisk317.xposed.runNonFatalOrNull
import java.lang.reflect.Method
import java.util.Locale
import io.github.magisk317.xposed.logging.MagiskOtel

class SystemInputInjectorHook : BaseHook() {
    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var registerAttempts = 0

    @Volatile
    private var inputHandler: Handler? = null

    @Volatile
    private var inputThread: HandlerThread? = null

    @Volatile
    private var registeredReceiverContext: Context? = null

    @Volatile
    private var registeredReceiver: BroadcastReceiver? = null

    @Volatile
    private var inputManagerGlobal: Any? = null

    @Volatile
    private var injectMethod: Method? = null

    @Volatile
    private var injectMethodParamCount: Int = 0

    @Volatile
    private var mainHandler: Handler? = null

    @Volatile
    private var amsSystemReadyHooked = false
    @Volatile
    private var suppressionLogged = false
    @Volatile
    private var sendingUidFieldsLogged = false
    @Volatile
    private var lastAutoInputCallerUid: Int = -1
    @Volatile
    private var lastAutoInputCallerUidAt: Long = 0L

    override fun hookInitZygote(): Boolean = true

    override fun initZygote() {
        XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
            initZygoteRouted()
        }
    }

    private fun initZygoteRouted() {
        runNonFatalCatching {
            // Redmi K60 Ultra (Redmi 23078RKD5C) Android 16 feedback:
            // system_server starts very early, ActivityThread.systemMain might be missed.
            val atClazz = HookHelpers.findClass("android.app.ActivityThread", null)
            val systemMainMethod = HookHelpers.findMethodExactIfExists(atClazz, "systemMain")
            systemMainMethod?.let {
                HookEnv.api.hookMethod(
                    it,
                    object : MethodHook() {
                        override val stableId: String = "system_input.activity_thread_system_main"

                        override fun afterHookedMethod(param: MethodHookParam) {
                            XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
                                XLog.i("XSmsCode: ActivityThread.systemMain hook triggered")
                                val activityThreadClass = HookHelpers.findClass("android.app.ActivityThread", null)
                                val activityThread = HookHelpers.callStaticMethod(
                                    activityThreadClass,
                                    "currentActivityThread",
                                )
                                val systemContext = HookHelpers.callMethod(activityThread, "getSystemContext") as? Context
                                if (systemContext != null) {
                                    scheduleRegister(systemContext)
                                } else {
                                    XLog.i("XSmsCode: systemContext is null in ActivityThread.systemMain hook")
                                }
                            }
                        }
                    },
                )
            }
            XLog.w("SystemInputInjectorHook: hooked ActivityThread.systemMain in zygote")
            XLog.i("XSmsCode: hooked ActivityThread.systemMain in zygote")
        }.onFailure { t ->
            XLog.e(message = "SystemInputInjectorHook: failed to hook ActivityThread.systemMain in zygote", t)
            XLog.i("XSmsCode: failed to hook ActivityThread.systemMain in zygote: ${t.message}")
        }
    }

    override fun hookOnLoadPackage(): Boolean = true

    override fun onLoadPackage(param: LoadParam) {
        if (param.packageName != ANDROID_PACKAGE || !isSystemServerProcess(param.processName)) {
            return
        }

        XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
            onLoadPackageRouted(param)
        }
    }

    private fun onLoadPackageRouted(lpparam: LoadParam) {
        // Fallback for Redmi K60 Ultra (Android 16): 
        // If systemMain was already executed, try immediate initialization or hook systemReady.
        XLog.i("XSmsCode: SystemInputInjectorHook loading for android package")

        hookBroadcastCallerUid(lpparam.classLoader)

        runNonFatalCatching {
            // Attempt 1: Check if already ready
            val activityThreadClass = HookHelpers.findClass("android.app.ActivityThread", lpparam.classLoader)
            val activityThread = HookHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            if (activityThread != null) {
                val systemContext = HookHelpers.callMethod(activityThread, "getSystemContext") as? Context
                if (systemContext != null) {
                    if (CoreHookPolicyHolder.shouldSuppressSystemHooks(systemContext, "SystemInputInjectorHook#onLoadPackage")) {
                        logSuppressedOnce("onLoadPackage")
                        receiverRegistered = true
                        return
                    }
                    XLog.w("XSmsCode: System context available in onLoadPackage, registering receiver")
                    XLog.i("XSmsCode: System context available in onLoadPackage, registering receiver")
                    scheduleRegister(systemContext)
                    if (receiverRegistered) return
                }
            }
        }.onFailure { t ->
            XLog.w("Failed to get system context in onLoadPackage: ${t.message}")
        }

        hookAmsSystemReadyFallback(lpparam.classLoader)
    }

    private fun hookAmsSystemReadyFallback(classLoader: ClassLoader?) {
        if (amsSystemReadyHooked) return
        runNonFatalCatching {
            val amsClass = HookHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            val methods = amsClass.declaredMethods.filter { it.name == "systemReady" }
            if (methods.isEmpty()) {
                XLog.w("SystemInputInjectorHook: no ActivityManagerService.systemReady method found, skip fallback hook")
                return
            }
            methods.forEach { method ->
                HookEnv.api.hookMethod(
                    method,
                    object : MethodHook() {
                        override val stableId: String = "system_input.ams_system_ready"

                        override fun afterHookedMethod(param: MethodHookParam) {
                            XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
                                if (receiverRegistered) return@withRoute
                                XLog.i("XSmsCode: ActivityManagerService.systemReady hook triggered")
                                val context = resolveSystemContext(param.thisObject)
                                if (context != null) {
                                    if (CoreHookPolicyHolder.shouldSuppressSystemHooks(context, "SystemInputInjectorHook#systemReady")) {
                                        logSuppressedOnce("systemReady")
                                        receiverRegistered = true
                                        return@withRoute
                                    }
                                    scheduleRegister(context)
                                }
                            }
                        }
                    },
                )
            }
            amsSystemReadyHooked = true
            XLog.w("SystemInputInjectorHook: hooked ActivityManagerService.systemReady overloads as fallback")
        }.onFailure { t ->
            XLog.e(message = "SystemInputInjectorHook: failed to hook AMS.systemReady overloads", t)
        }
    }

    private fun resolveSystemContext(systemService: Any?): Context? {
        if (systemService == null) return null
        return runNonFatalOrNull { HookHelpers.getObjectField(systemService, "mContext") as? Context }
            ?: runNonFatalOrNull { HookHelpers.getObjectField(systemService, "mSystemContext") as? Context }
    }

    private fun scheduleRegister(context: Context) {
        if (receiverRegistered) return
        getMainHandler().postDelayed(
            { XLog.withRoute(LogRoute.SYSTEM_INPUT.id) { registerReceiver(context) } },
            DELAY_REGISTER,
        )
    }

    @Volatile
    private var broadcastHooked = false

    private fun hookBroadcastCallerUid(classLoader: ClassLoader?) {
        if (broadcastHooked) return
        runNonFatalCatching {
            val queueClass = HookHelpers.findClassIfExists(
                "com.android.server.am.BroadcastQueueImpl",
                classLoader,
            ) ?: HookHelpers.findClassIfExists(
                "com.android.server.am.BroadcastQueue",
                classLoader,
            ) ?: return

            val methods = queueClass.declaredMethods.filter { it.name == "enqueueBroadcastLocked" }
            if (methods.isEmpty()) return

            methods.forEach { method ->
                HookEnv.api.hookMethod(
                    method,
                    object : MethodHook() {
                        override val stableId: String = "system_input.broadcast_enqueue_caller_uid"

                        override fun afterHookedMethod(param: MethodHookParam) {
                            XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
                                val record = param.args.firstOrNull() ?: return@withRoute
                                val intent = extractBroadcastIntent(record) ?: return@withRoute
                                val action = intent.action ?: return@withRoute
                                if (action != resolveActionAutoInput() && action != resolveActionShowToast()) {
                                    return@withRoute
                                }
                                val (uid, pkg) = extractCallerInfo(record)
                                if (uid >= 0) {
                                    lastAutoInputCallerUid = uid
                                    lastAutoInputCallerUidAt = SystemClock.elapsedRealtime()
                                }
                                XLog.w(
                                    message = "Diag broadcast enqueue: action=%s callerUid=%d callerPkg=%s record=%s",
                                    action,
                                    uid,
                                    pkg ?: "unknown",
                                    record.javaClass.name,
                                )
                            }
                        }
                    },
                )
            }
            broadcastHooked = true
            XLog.w("SystemInputInjectorHook: hooked BroadcastQueue enqueue for caller uid")
        }.onFailure { t ->
            XLog.w(message = "SystemInputInjectorHook: failed to hook BroadcastQueue: %s", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun extractBroadcastIntent(record: Any): Intent? {
        if (record is Intent) return record
        return runNonFatalOrNull { HookHelpers.getObjectField(record, "intent") as? Intent }
            ?: runNonFatalOrNull { HookHelpers.callMethod(record, "getIntent") as? Intent }
    }

    private fun extractCallerInfo(record: Any): Pair<Int, String?> {
        val uidFields = listOf("callerUid", "callingUid", "uid")
        for (field in uidFields) {
            runNonFatalOrNull { HookHelpers.getIntField(record, field) }?.let { return it to null }
        }
        var callerPkg = runNonFatalOrNull { HookHelpers.getObjectField(record, "callerPackage") as? String }
        val callerApp = runNonFatalOrNull { HookHelpers.getObjectField(record, "callerApp") }
        if (callerApp != null) {
            runNonFatalOrNull { HookHelpers.getIntField(callerApp, "uid") }?.let { uid ->
                return uid to callerPkg
            }
            runNonFatalOrNull { HookHelpers.getObjectField(callerApp, "info") }?.let { info ->
                if (callerPkg == null) {
                    callerPkg = runNonFatalOrNull { HookHelpers.getObjectField(info, "packageName") as? String }
                        ?: runNonFatalOrNull { HookHelpers.callMethod(info, "getPackageName") as? String }
                }
                runNonFatalOrNull { HookHelpers.getIntField(info, "uid") }?.let { uid ->
                    return uid to callerPkg
                }
            }
        }
        return -1 to callerPkg
    }
    private fun registerReceiver(context: Context) {
        runNonFatalCatching {
            if (receiverRegistered) return
            if (CoreHookPolicyHolder.shouldSuppressSystemHooks(context, "SystemInputInjectorHook#registerReceiver")) {
                logSuppressedOnce("registerReceiver")
                receiverRegistered = true
                return
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
                    val sendingUid = resolveSendingUid(this)
                    val appUid = context.applicationInfo.uid
                    val senderPackages = resolvePackagesForUid(context, sendingUid)
                    if (!shouldAllowAutoInputSenderUid(
                            sendingUid = sendingUid,
                            systemContextUid = appUid,
                            senderPackages = senderPackages,
                            modulePackageName = io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.applicationId,
                        )
                    ) {
                        XLog.w(
                            message = "SystemServer input request rejected from uid=%d packages=%s",
                            sendingUid,
                            senderPackages.joinToString(limit = 6).ifBlank { "<none>" },
                        )
                        return@withRoute
                    }
                    if (intent.action == resolveActionShowToast()) {
                        val text = intent.getStringExtra("text")
                        val duration = intent.getIntExtra("duration", Toast.LENGTH_LONG)
                        XLog.w(
                            message = "Diag system toast request: uid=%d text_len=%d duration=%d",
                            sendingUid,
                            text?.length ?: 0,
                            duration,
                        )
                        if (text.isNullOrEmpty()) {
                            XLog.w("SystemServer toast request ignored: empty text")
                            return@withRoute
                        }
                        showToast(context, text, duration)
                        return@withRoute
                    }
                    val request = when (
                        val receiverRequest = AutoInputBroadcastContract.readReceiverRequest(
                            intent = intent,
                            expectedAction = resolveActionAutoInput(),
                        )
                    ) {
                        AutoInputBroadcastContract.ReceiverRequest.Ignored -> return@withRoute
                        is AutoInputBroadcastContract.ReceiverRequest.BlankCode -> {
                            val request = receiverRequest.request
                            XLog.w(
                                message = "Diag system receiver onReceive: uid=%d code_len=%d autoEnter=%s inputIntervalMs=%d",
                                sendingUid,
                                request.code.length,
                                request.autoEnter,
                                request.inputIntervalMs,
                            )
                            XLog.w("SystemServer received input request with empty code")
                            sendAutoInputResult(
                                context = context,
                                attemptId = request.attemptId,
                                success = false,
                                reason = "empty_code",
                            )
                            return@withRoute
                        }
                        is AutoInputBroadcastContract.ReceiverRequest.Accepted -> receiverRequest.request
                    }
                    val inputIntervalMs = request.inputIntervalMs
                    val safeInputIntervalMs = inputIntervalMs.coerceAtMost(MAX_SYNC_INPUT_INTERVAL_MS)
                    XLog.w(
                        message = "Diag system receiver onReceive: uid=%d code_len=%d autoEnter=%s inputIntervalMs=%d",
                        sendingUid,
                        request.code.length,
                        request.autoEnter,
                        inputIntervalMs,
                    )
                    if (safeInputIntervalMs != inputIntervalMs) {
                        XLog.w(
                            message = "SystemServer input interval clamped: requested=%d applied=%d",
                            inputIntervalMs,
                            safeInputIntervalMs,
                        )
                    }
                    val result = runNonFatalCatching {
                        performInjectText(request.code, request.autoEnter, safeInputIntervalMs)
                    }.getOrElse { error ->
                        XLog.e(message = "SystemServer performInjectText failed", error)
                        InjectResult(success = false, failReason = "inject_exception")
                    }
                    sendAutoInputResult(context, request.attemptId, result.success, result.failReason)
                    if (result.success) {
                        XLog.i(
                            message = "SystemServer injection success, aborting broadcast to prevent accessibility fallback: attemptId=%d",
                            request.attemptId ?: -1L,
                        )
                        abortBroadcast()
                    } else {
                        XLog.w(
                            message = "SystemServer injection failed, allowing broadcast to continue for accessibility fallback: attemptId=%d reason=%s",
                            request.attemptId ?: -1L,
                            result.failReason ?: "unknown",
                        )
                    }
                    }
                }
            }
            val filter = IntentFilter(resolveActionAutoInput())
            filter.addAction(resolveActionShowToast())
            filter.priority = RECEIVER_PRIORITY_SYSTEM_PRIMARY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registeredReceiverContext = context
            registeredReceiver = receiver
            receiverRegistered = true
            XLog.w("SystemInputInjectorReceiver registered")
            XLog.i("XSmsCode: SystemInputInjectorReceiver registered")
        }.onFailure { t ->
            registerAttempts += 1
            XLog.e(message = "Failed to register receiver", t)
            XLog.i("XSmsCode: Failed to register receiver: ${t.message}")
            if (registerAttempts < MAX_REGISTER_ATTEMPTS) {
                scheduleRegister(context)
            } else {
                XLog.i("XSmsCode: registerReceiver give up after $registerAttempts attempts")
            }
        }
    }

    private fun showToast(context: Context, text: String, duration: Int) {
        getMainHandler().post {
            XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
                runNonFatalCatching {
                    val toast = Toast.makeText(context, text, duration)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        toast.addCallback(
                            object : Toast.Callback() {
                                override fun onToastShown() {
                                    XLog.w(message = "Diag system toast shown: text_len=%d", text.length)
                                }

                                override fun onToastHidden() {
                                    XLog.w(message = "Diag system toast hidden: text_len=%d", text.length)
                                }
                            },
                        )
                    }
                    toast.show()
                    XLog.w(message = "SystemServer toast show invoked: text_len=%d duration=%d", text.length, duration)
                }.onFailure { t ->
                    XLog.e(message = "Failed to show toast from System Server", t)
                }
            }
        }
    }

    private fun resolveAccessibilityOrderedResult(
        receiver: BroadcastReceiver,
    ): AutoInputAccessibilityResultContract.OrderedResult? {
        val extras = runNonFatalOrNull { receiver.getResultExtras(false) } ?: return null
        return AutoInputAccessibilityResultContract.readOrderedResult(extras)
    }

    private fun shouldSkipFallbackAfterAccessibility(
        context: Context,
        result: AutoInputAccessibilityResultContract.OrderedResult,
    ): Boolean {
        return AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
            success = result.success,
            reason = result.reason,
            windowPackage = result.windowPackage,
            modulePackage = io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.applicationId,
            homePackages = resolveHomePackages(context),
        )
    }

    private fun resolveHomePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runNonFatalCatching {
            val infos = queryIntentActivitiesCompat(context.packageManager, intent)
            infos.mapNotNull { it.activityInfo?.packageName?.takeIf(String::isNotBlank) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun resolvePackagesForUid(context: Context, uid: Int): Set<String> {
        if (uid < 0) return emptySet()
        return runNonFatalOrNull {
            context.packageManager.getPackagesForUid(uid)
                ?.filter(String::isNotBlank)
                ?.toSet()
        }.orEmpty()
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun queryIntentActivitiesCompat(
        packageManager: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(matchDefaultOnlyFlag().toLong()),
            )
        }
        val result = runNonFatalOrNull {
            val method = packageManager.javaClass.getMethod(
                "queryIntentActivities",
                Intent::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(packageManager, intent, matchDefaultOnlyFlag())
        }
        return (result as? List<*>)?.filterIsInstance<ResolveInfo>().orEmpty()
    }

    private fun matchDefaultOnlyFlag(): Int {
        return resolveStaticIntFieldOrDefault(
            PackageManager::class.java,
            "MATCH_DEFAULT_ONLY",
            MATCH_DEFAULT_ONLY_FALLBACK,
        )
    }

    private fun logSuppressedOnce(stage: String) {
        if (suppressionLogged) return
        synchronized(this) {
            if (suppressionLogged) return
            XLog.w(message = "SystemInputInjectorHook suppressed: stage=%s", stage)
            suppressionLogged = true
        }
    }

    private fun resolveSendingUid(receiver: BroadcastReceiver): Int {
        val cached = lastAutoInputCallerUid
        if (cached >= 0) {
            val ageMs = SystemClock.elapsedRealtime() - lastAutoInputCallerUidAt
            if (ageMs in 0..CACHED_UID_TTL_MS) {
                return cached
            }
        }
        // Try system API if available
        val direct = runNonFatalCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HookHelpers.callMethod(receiver, "getSendingUid") as Int
            } else {
                null
            }
        }.getOrElse { t ->
            XLog.w("Failed to get sendingUid: ${t.message}")
            null
        }
        if (direct != null) return direct

        val pendingResult = runNonFatalOrNull { HookHelpers.callMethod(receiver, "getPendingResult") }
            ?: runNonFatalOrNull { HookHelpers.getObjectField(receiver, "mPendingResult") }
        if (pendingResult != null) {
            val candidates = listOf("mSentFromUid", "mSendingUid", "mSenderUid", "mCallingUid")
            for (field in candidates) {
                runNonFatalOrNull { HookHelpers.getIntField(pendingResult, field) }?.let { return it }
            }
            logPendingResultFieldsOnce(pendingResult)
        } else {
            logPendingResultMissingOnce()
        }
        return -1
    }

    @Volatile
    private var pendingResultMissingLogged = false

    private fun logPendingResultMissingOnce() {
        if (pendingResultMissingLogged) return
        synchronized(this) {
            if (pendingResultMissingLogged) return
            pendingResultMissingLogged = true
        }
        XLog.w("Diag pendingResult is null; unable to resolve sendingUid")
    }

    private fun logPendingResultFieldsOnce(pendingResult: Any) {
        if (sendingUidFieldsLogged) return
        synchronized(this) {
            if (sendingUidFieldsLogged) return
            sendingUidFieldsLogged = true
        }
        val fields = generateSequence(pendingResult.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { "${it.name}:${it.type.name}" }
            .distinct()
            .sorted()
            .joinToString(limit = 80, truncated = "...")
        XLog.w(message = "Diag pendingResult fields: %s", fields)
    }

    private fun getInputHandler(): Handler {
        val cached = inputHandler
        if (cached != null) return cached
        return synchronized(this) {
            val existing = inputHandler
            if (existing != null) {
                existing
            } else {
                val thread = HandlerThread("xsmscode-input")
                thread.start()
                inputThread = thread
                Handler(thread.looper).also { inputHandler = it }
            }
        }
    }

    private fun getMainHandler(): Handler {
        val cached = mainHandler
        if (cached != null) return cached
        return synchronized(this) {
            val existing = mainHandler
            if (existing != null) {
                existing
            } else {
                val mainLooper = Looper.getMainLooper()
                val handler = if (mainLooper != null) {
                    Handler(mainLooper)
                } else {
                    // Fallback for early zygote stage when main looper isn't ready yet.
                    getInputHandler()
                }
                mainHandler = handler
                handler
            }
        }
    }

    private fun getInputManagerGlobal(): Pair<Any, Method>? {
        val cachedManager = inputManagerGlobal
        val cachedMethod = injectMethod
        if (cachedManager != null && cachedMethod != null) return cachedManager to cachedMethod
        return synchronized(this) {
            val manager = inputManagerGlobal
            val method = injectMethod
            if (manager != null && method != null) {
                manager to method
            } else {
                runNonFatalCatching {
	                    val classCandidates = buildList {
	                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
	                            add("android.hardware.input.InputManagerGlobal")
                            add("android.hardware.input.InputManager")
                        } else {
                            add("android.hardware.input.InputManager")
                            add("android.hardware.input.InputManagerGlobal")
                        }
                    }
                    val errors = mutableListOf<String>()
                    for (className in classCandidates) {
                        val resolved = runNonFatalCatching {
                            val inputManagerClass = HookHelpers.findClass(className, null)
                            val instance = HookHelpers.callStaticMethod(inputManagerClass, "getInstance") ?: continue
                            val inject = findInjectMethod(inputManagerClass)
                            inputManagerGlobal = instance
                            injectMethod = inject
                            injectMethodParamCount = inject.parameterTypes.size
                            XLog.i(
                                message = "Resolved input injector: class=%s, method=%s",
                                className,
                                inject.toGenericString(),
                            )
                            instance to inject
                        }.getOrElse { t ->
                            errors += "$className -> ${t::class.java.simpleName}: ${t.message}"
                            null
                        }
                        if (resolved != null) {
                            return@synchronized resolved
                        }
                    }
	                    throw NoSuchMethodError(
	                        "No compatible injectInputEvent found. Details: ${errors.joinToString(" | ")}",
	                    )
	                }.getOrElse { t ->
	                    XLog.e(message = "Failed to resolve InputManagerGlobal", t)
	                    null
                }
            }
        }
    }

    private fun findInjectMethod(inputManagerClass: Class<*>): Method {
        val candidates = (inputManagerClass.declaredMethods + inputManagerClass.methods).distinctBy {
            "${it.name}#${it.parameterTypes.joinToString(",") { p -> p.name }}"
        }
        val preferred = candidates.firstOrNull { method ->
            if (method.name != "injectInputEvent") return@firstOrNull false
            val params = method.parameterTypes
            params.size == 2 && params[0] == InputEvent::class.java && params[1] == Int::class.javaPrimitiveType
        }
        if (preferred != null) {
            preferred.isAccessible = true
            return preferred
        }

        val fallback = candidates.firstOrNull { method ->
            if (method.name != "injectInputEvent") return@firstOrNull false
            val params = method.parameterTypes
            when (params.size) {
                2 -> InputEvent::class.java.isAssignableFrom(params[0]) &&
                    (params[1] == Int::class.javaPrimitiveType || params[1] == Int::class.java)
                3 -> InputEvent::class.java.isAssignableFrom(params[0]) &&
                    (params[1] == Int::class.javaPrimitiveType || params[1] == Int::class.java) &&
                    (params[2] == Int::class.javaPrimitiveType || params[2] == Int::class.java)
                else -> false
            }
        }
        if (fallback != null) {
            fallback.isAccessible = true
            return fallback
        }

        val signatureDump = candidates
            .filter { it.name == "injectInputEvent" }
            .joinToString("; ") { it.toGenericString() }
            .ifBlank { "none" }
        throw NoSuchMethodException("injectInputEvent signatures: $signatureDump")
    }

    private fun invokeInject(manager: Any, method: Method, event: InputEvent, mode: Int): Boolean {
        val result = when (injectMethodParamCount) {
            2 -> method.invoke(manager, event, mode)
            3 -> method.invoke(manager, event, mode, 0)
            else -> return false
        }
        return when (result) {
            is Boolean -> result
            is Number -> result.toInt() != 0
            else -> false
        }
    }

    private data class InjectResult(
        val success: Boolean,
        val failReason: String?,
    )

    private fun performInjectText(
        text: String,
        autoEnter: Boolean = false,
        inputIntervalMs: Long = 0L,
    ): InjectResult {
        val startedAt = System.nanoTime()
        fun emit(result: String, statusOk: Boolean = true, reason: String? = null) {
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            val attrs = mutableMapOf(
                "result" to result,
                "duration_ms" to durationMs.toString(),
                "process" to "system",
                "source" to "injector",
                "code_length" to text.length.toString(),
            )
            if (reason != null) attrs["reason"] = reason
            MagiskOtel.event(name = "auto.input", attributes = attrs, statusOk = statusOk)
        }

        val managerPair = getInputManagerGlobal() ?: run {
            emit(result = "error", statusOk = false, reason = "manager_unresolved")
            return InjectResult(false, "manager_unresolved")
        }
        val (manager, method) = managerPair
        val mode = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        var injectedCount = 0
        val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        text.forEachIndexed { index, char ->
            val events = keyCharacterMap.getEvents(charArrayOf(char))
            if (events == null) {
                XLog.w(message = "Failed to create key events for char: %s", char.toString())
                return@forEachIndexed
            }
            for (event in events) {
                val result = invokeInject(manager, method, event, mode)
                if (result) injectedCount += 1
            }
            if (inputIntervalMs > 0L && index < text.lastIndex) {
                Thread.sleep(inputIntervalMs)
            }
        }
        XLog.w(message = "Injected key characters from System Server, count=%d", injectedCount)

        var success = injectedCount > 0
        var failReason: String? = null
        if (autoEnter) {
            val now = android.os.SystemClock.uptimeMillis()
            val downEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0)
            val upEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0)

            val downResult = invokeInject(manager, method, downEvent, mode)
            val upResult = invokeInject(manager, method, upEvent, mode)

            if (downResult && upResult) {
                XLog.w("Injected KEYCODE_ENTER from System Server")
            } else {
                XLog.e("Failed to inject KEYCODE_ENTER: down=$downResult, up=$upResult")
                failReason = "enter_failed"
                success = false
            }
        }
        emit(
            result = if (success) "ok" else "error",
            statusOk = success,
            reason = failReason ?: if (success) (if (autoEnter) "auto_enter" else "typed") else "inject_failed",
        )
        return InjectResult(success, failReason)
    }

    override fun onHotReloading() {
        XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
            registeredReceiver?.let { receiver ->
                runNonFatalCatching {
                    registeredReceiverContext?.unregisterReceiver(receiver)
                    XLog.w("SystemInputInjectorReceiver unregistered for hot reload")
                }.onFailure { t ->
                    XLog.w(message = "SystemInputInjectorReceiver unregister failed: %s", t.message ?: t.javaClass.simpleName)
                }
            }
            mainHandler?.removeCallbacksAndMessages(null)
            inputHandler?.removeCallbacksAndMessages(null)
            inputThread?.quitSafely()
            registeredReceiver = null
            registeredReceiverContext = null
            receiverRegistered = false
            inputHandler = null
            inputThread = null
            mainHandler = null
            inputManagerGlobal = null
            injectMethod = null
            injectMethodParamCount = 0
        }
    }

    private fun injectText(
        context: Context,
        text: String,
        autoEnter: Boolean = false,
        inputIntervalMs: Long = 0L,
        attemptId: Long? = null,
    ) {
        getInputHandler().post {
            XLog.withRoute(LogRoute.SYSTEM_INPUT.id) {
                val result = performInjectText(text, autoEnter, inputIntervalMs)
                sendAutoInputResult(context, attemptId, result.success, result.failReason)
            }
        }
    }

    private fun sendAutoInputResult(
        context: Context,
        attemptId: Long?,
        success: Boolean,
        reason: String?,
    ) {
        if (attemptId == null) return
        runNonFatalCatching {
            val appId = io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.applicationId
            val token = normalizeIpcToken(CorePrefs.getString(KEY_IPC_TOKEN, ""))
            val intent = AutoInputResultBroadcastContract.buildResultIntent(
                action = resolveActionAutoInputResult(),
                packageName = appId,
                result = AutoInputResultBroadcastContract.Result(
                    attemptId = attemptId,
                    success = success,
                    reason = reason?.takeIf { it.isNotBlank() },
                ),
                token = token,
            )
            if (token == null) {
                XLog.w("Auto input result ipc token empty; intent sent without security token")
            }
            context.sendBroadcast(intent)
            XLog.w(
                "Diag auto input result broadcast: attemptId=%d success=%s reason=%s",
                attemptId,
                success,
                reason ?: "<none>",
            )
        }.onFailure { error ->
            XLog.w(
                message = "Send auto input result failed: %s",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    companion object {
        private const val ANDROID_PACKAGE = "android"
        private const val SYSTEM_PROCESS = "system"
        private const val SYSTEM_SERVER_PROCESS = "system_server"
        private const val DELAY_REGISTER = 500L
        private const val MAX_REGISTER_ATTEMPTS = 10
        private const val CACHED_UID_TTL_MS = 10_000L
        private const val MAX_SYNC_INPUT_INTERVAL_MS = 200L
        private const val RECEIVER_PRIORITY_SYSTEM_PRIMARY = 2000
        private const val MATCH_DEFAULT_ONLY_FALLBACK = 0x00010000

        @Suppress("unused")
        fun resolveActionAutoInput(): String =
            AutoInputBroadcastContract.resolveActionAutoInput(
                io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.actionNamespace,
            )

        fun resolveActionShowToast(): String =
            AutoInputBroadcastContract.resolveActionShowToast(
                io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.actionNamespace,
            )

        fun resolveActionAutoInputResult(): String =
            AutoInputBroadcastContract.resolveActionAutoInputResult(
                io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.actionNamespace,
            )

        val KEY_IPC_TOKEN: String
            get() = AutoInputBroadcastContract.EXTRA_IPC_TOKEN
        val EXTRA_IPC_TOKEN: String
            get() = AutoInputBroadcastContract.EXTRA_IPC_TOKEN
        val EXTRA_ACCESSIBILITY_HANDLED: String
            get() = AutoInputAccessibilityResultContract.EXTRA_HANDLED
        val EXTRA_ACCESSIBILITY_SUCCESS: String
            get() = AutoInputAccessibilityResultContract.EXTRA_SUCCESS
        val EXTRA_ACCESSIBILITY_REASON: String
            get() = AutoInputAccessibilityResultContract.EXTRA_REASON
        val EXTRA_ACCESSIBILITY_STRATEGY: String
            get() = AutoInputAccessibilityResultContract.EXTRA_STRATEGY
        val EXTRA_ACCESSIBILITY_WINDOW_PACKAGE: String
            get() = AutoInputAccessibilityResultContract.EXTRA_WINDOW_PACKAGE

        fun normalizeIpcToken(token: String?): String? = AutoInputBroadcastContract.normalizeIpcToken(token)

        fun shouldAllowAutoInputSenderUid(
            sendingUid: Int,
            systemContextUid: Int,
            senderPackages: Collection<String>,
            modulePackageName: String,
        ): Boolean {
            if (sendingUid == -1 ||
                sendingUid == Process.SYSTEM_UID ||
                sendingUid == Process.PHONE_UID ||
                sendingUid == systemContextUid
            ) {
                return true
            }
            val modulePackage = modulePackageName.takeIf(String::isNotBlank)
            return senderPackages.any { packageName ->
                packageName in TRUSTED_AUTO_INPUT_SENDER_PACKAGES || packageName == modulePackage
            }
        }

        private val TRUSTED_AUTO_INPUT_SENDER_PACKAGES = setOf(
            "com.android.phone",
            "com.xiaomi.phone",
            "com.android.providers.telephony",
            "com.android.mms",
            "com.google.android.apps.messaging",
        )

        private fun isSystemServerProcess(processName: String): Boolean {
            return processName == ANDROID_PACKAGE ||
                processName == SYSTEM_PROCESS ||
                processName == SYSTEM_SERVER_PROCESS
        }
    }
}
