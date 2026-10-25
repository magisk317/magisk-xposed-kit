package io.github.magisk317.xposed.keepalive

import io.github.magisk317.xposed.BaseHook
import io.github.magisk317.xposed.LoadParam
import io.github.magisk317.xposed.findHookClass
import io.github.magisk317.xposed.hook
import io.github.magisk317.xposed.logging.XLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Everything the shared keep-alive hook needs from a consumer. */
class KeepAliveConfig(
    /** Package whose processes the hook protects. */
    val targetPackage: String,
    /** Live switch snapshot; read on every hook invocation. */
    val flagsProvider: KeepAliveFlagsProvider,
    /** Capabilities to install; defaults to all of them. */
    val capabilities: Set<KeepAliveCapability> = KeepAliveCapability.entries.toSet(),
    /** Optional telemetry receiver for keep-alive decisions. */
    val eventSink: KeepAliveEventSink? = null,
    /** Package names that host system_server. */
    val systemPackages: Set<String> = setOf("android"),
    /** Process names that host system_server. */
    val systemProcesses: Set<String> = setOf("android"),
    val logTag: String = "KeepAliveHook",
)

/**
 * Shared system_server keep-alive hook.
 *
 * Targets are matched by exact method descriptor, so a framework layout change leaves a
 * capability uninstalled instead of binding it to an arbitrary overload. All decisions
 * come from [KeepAlivePolicy]; all framework mutation goes through the platform adapter.
 *
 * Doze is deliberately not part of this hook. Exemption belongs on the real DeviceIdle
 * whitelist: overriding a DeviceIdleController result would stall the idle state machine
 * for every app rather than exempting the target.
 */
open class KeepAliveHook(
    private val config: KeepAliveConfig,
) : BaseHook() {

    private val platform = KeepAlivePlatformAdapter(config.targetPackage)
    private val lastLogAt = ConcurrentHashMap<String, Long>()

    override fun onLoadPackage(param: LoadParam) {
        if (!shouldInstall(param)) return

        XLog.i(config.logTag, "loading in system_server; target=${config.targetPackage}")
        hookOomAdjuster(param.classLoader)
        hookKillProcess(param.classLoader)
        hookPackageKill(param.classLoader)
        hookAppStandbyController(param.classLoader)
        emit(stage = "hook_install", result = "ok", reason = "installed")
    }

    // ── OomAdjuster ──────────────────────────────────────────────────────────

    private fun hookOomAdjuster(classLoader: ClassLoader) {
        if (KeepAliveCapability.OOM_APPLY !in config.capabilities) return
        val owner = resolveOomOwner(classLoader) ?: run {
            XLog.d(config.logTag, "skip oom_apply: no supported OomAdjuster target")
            return
        }
        val targets = KeepAliveHookTargets.oomApply.filter { it.shape.owner == owner.name }
        val resolved = resolveTarget(KeepAliveCapability.OOM_APPLY, owner, targets) ?: return
        resolved.method.hook {
            doBefore {
                val record = args.getOrNull(0) ?: return@doBefore
                if (platform.adjustOomAdj(record, flags)) {
                    logRateLimited("oom_adjust", "updated OomAdjuster process state for ${config.targetPackage}")
                }
            }
        }
    }

    private fun resolveOomOwner(classLoader: ClassLoader): Class<*>? {
        return KeepAliveHookTargets.oomApplyOwners.firstNotNullOfOrNull { className ->
            runCatching { findHookClass(className, classLoader) }
                .onFailure { XLog.d(config.logTag, "skip oom target $className: ${it.message}") }
                .getOrNull()
        }
    }

    // ── ProcessRecord kill ──────────────────────────────────────────────────

    private fun hookKillProcess(classLoader: ClassLoader) {
        if (KeepAliveCapability.KILL_GUARD !in config.capabilities) return
        runCatching {
            val owner = findHookClass("com.android.server.am.ProcessRecord", classLoader)
            val resolved = resolveTarget(KeepAliveCapability.KILL_GUARD, owner, KeepAliveHookTargets.killLocked)
                ?: return
            val reasonIndex = requireNotNull(resolved.target.valueIndex)
            val subReasonIndex = requireNotNull(resolved.target.secondaryValueIndex)
            resolved.method.hook {
                doBefore {
                    val record = thisObject ?: return@doBefore
                    val mappings = platform.activeRecordMappings(record)
                    val reason = args.getOrNull(reasonIndex) as? Int
                    val subReason = args.getOrNull(subReasonIndex) as? Int
                    if (
                        KeepAlivePolicy.shouldSuppressKill(
                            enabled = flags.ready,
                            target = flags.antiKill,
                            targetProcess = config.targetPackage,
                            processName = mappings.processName,
                            reason = reason,
                            subReason = subReason,
                            nameMapping = mappings.nameCurrent,
                            pidMapping = mappings.pidCurrent,
                        )
                    ) {
                        result = null
                        logRateLimited(
                            "kill_guard",
                            "suppressed current ${config.targetPackage} automatic kill reason=$reason subReason=$subReason",
                        )
                        emit(
                            stage = "kill_guard",
                            result = "skip",
                            reason = "target_protected",
                            extra = mapOf(
                                "reason_code" to reason.toString(),
                                "sub_reason_code" to subReason.toString(),
                            ),
                        )
                    }
                }
            }
        }.onFailure {
            XLog.e(config.logTag, "failed to hook ProcessRecord.killLocked", it)
        }
    }

    // ── ProcessList package kill ────────────────────────────────────────────

    private fun hookPackageKill(classLoader: ClassLoader) {
        if (KeepAliveCapability.PACKAGE_KILL_GUARD !in config.capabilities) return
        runCatching {
            val owner = findHookClass("com.android.server.am.ProcessList", classLoader)
            val resolved = resolveTarget(
                KeepAliveCapability.PACKAGE_KILL_GUARD,
                owner,
                KeepAliveHookTargets.packageKill,
            ) ?: return
            val packageIndex = requireNotNull(resolved.target.packageIndex)
            val reasonIndex = requireNotNull(resolved.target.valueIndex)
            val subReasonIndex = requireNotNull(resolved.target.secondaryValueIndex)
            resolved.method.hook {
                doBefore {
                    val packageName = args.getOrNull(packageIndex) as? String
                    val reason = args.getOrNull(reasonIndex) as? Int
                    val subReason = args.getOrNull(subReasonIndex) as? Int
                    // These indexes are part of the exact API 33 descriptor above:
                    // callerWillRestart=4, doit=6, evenPersistent=7, setRemoved=8,
                    // uninstalling=9. Unknown or malformed values fail open.
                    if (
                        KeepAlivePolicy.shouldSuppressPackageKill(
                            enabled = flags.ready,
                            target = flags.antiKill,
                            targetPackage = config.targetPackage,
                            packageName = packageName,
                            reason = reason,
                            subReason = subReason,
                            callerWillRestart = args.getOrNull(4) as? Boolean,
                            doit = args.getOrNull(6) as? Boolean,
                            evenPersistent = args.getOrNull(7) as? Boolean,
                            setRemoved = args.getOrNull(8) as? Boolean,
                            uninstalling = args.getOrNull(9) as? Boolean,
                        )
                    ) {
                        result = false
                        logRateLimited(
                            "package_kill_guard",
                            "suppressed current ${config.targetPackage} package automatic kill " +
                                "reason=$reason subReason=$subReason",
                        )
                    }
                }
            }
        }.onFailure {
            XLog.e(config.logTag, "failed to hook ProcessList.killPackageProcessesLSP", it)
        }
    }

    // ── AppStandbyController ────────────────────────────────────────────────

    private fun hookAppStandbyController(classLoader: ClassLoader) {
        if (!config.capabilities.containsAny(
                KeepAliveCapability.STANDBY_BUCKET,
                KeepAliveCapability.STANDBY_IDLE,
                KeepAliveCapability.STANDBY_FORCE_IDLE,
            )
        ) return
        runCatching {
            val owner = findHookClass("com.android.server.usage.AppStandbyController", classLoader)
            hookStandbyBucket(owner)
            hookIdleEntry(owner, KeepAliveHookTargets.appIdle)
            hookIdleEntry(owner, KeepAliveHookTargets.forceIdle)
        }.onFailure {
            XLog.e(config.logTag, "failed to hook AppStandbyController", it)
        }
    }

    private fun hookStandbyBucket(owner: Class<*>) {
        KeepAliveHookTargets.standbyBucket.forEach { target ->
            hookStandbyBucketTarget(owner, target)
        }
    }

    private fun hookStandbyBucketTarget(owner: Class<*>, target: IndexedHookTarget) {
        val resolved = resolveTarget(target.capability, owner, listOf(target)) ?: return
        val packageIndex = requireNotNull(resolved.target.packageIndex)
        val bucketIndex = requireNotNull(resolved.target.valueIndex)
        resolved.method.hook {
            doBefore {
                val packageName = args.getOrNull(packageIndex) as? String ?: return@doBefore
                val currentBucket = args.getOrNull(bucketIndex) as? Int ?: return@doBefore
                val targetBucket = KeepAlivePolicy.desiredStandbyBucket(
                    enabled = flags.ready,
                    target = flags.standbyBypass,
                    targetPackage = config.targetPackage,
                    packageName = packageName,
                    currentBucket = currentBucket,
                ) ?: return@doBefore
                args[bucketIndex] = targetBucket
                logRateLimited(
                    "standby_bucket",
                    "forced ${config.targetPackage} standby bucket ACTIVE from $currentBucket",
                )
            }
        }
    }

    private fun hookIdleEntry(owner: Class<*>, targets: List<IndexedHookTarget>) {
        val capability = targets.single().capability
        val resolved = resolveTarget(capability, owner, targets) ?: return
        val packageIndex = requireNotNull(resolved.target.packageIndex)
        val idleIndex = requireNotNull(resolved.target.valueIndex)
        resolved.method.hook {
            doBefore {
                val packageName = args.getOrNull(packageIndex) as? String ?: return@doBefore
                val idle = args.getOrNull(idleIndex) as? Boolean ?: return@doBefore
                val targetIdle = KeepAlivePolicy.desiredIdleState(
                    enabled = flags.ready,
                    target = flags.standbyBypass,
                    targetPackage = config.targetPackage,
                    packageName = packageName,
                    idle = idle,
                ) ?: return@doBefore
                args[idleIndex] = targetIdle
                logRateLimited("${capability}_idle", "kept ${config.targetPackage} out of forced app idle")
            }
        }
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    /** True when [param] is the system_server process this hook targets. */
    protected fun shouldInstall(param: LoadParam): Boolean =
        param.packageName in config.systemPackages && param.processName in config.systemProcesses

    protected val flags: KeepAliveFlags
        get() = config.flagsProvider.current()

    private fun resolveTarget(
        capability: KeepAliveCapability,
        owner: Class<*>,
        targets: List<IndexedHookTarget>,
    ): MethodResolution.Resolved<Method>? {
        return when (val resolution = KeepAliveHookTargets.resolve(owner, targets)) {
            is MethodResolution.Resolved<Method> -> {
                resolution.method.isAccessible = true
                XLog.i(
                    config.logTag,
                    "hook $capability installed ${KeepAliveHookTargets.describe(resolution.target)}",
                )
                resolution
            }
            MethodResolution.Missing -> {
                XLog.w(config.logTag, "hook $capability unavailable: exact descriptor missing")
                null
            }
            is MethodResolution.Ambiguous<*> -> {
                XLog.w(
                    config.logTag,
                    "hook $capability unavailable: ${resolution.candidates.size} exact descriptors matched",
                )
                null
            }
        }
    }

    private fun emit(
        stage: String,
        result: String,
        reason: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val sink = config.eventSink ?: return
        runCatching {
            sink.event(
                EVENT_NAME,
                mapOf(
                    "result" to result,
                    "duration_ms" to "0",
                    "process" to "system_server",
                    "stage" to stage,
                    "reason" to reason,
                    "target_package" to config.targetPackage,
                ) + extra,
            )
        }.onFailure {
            // The sink is the reporting channel itself: a failing event must not recurse into the hook.
        }
    }

    private fun logRateLimited(key: String, message: String) {
        val now = System.currentTimeMillis()
        val previous = lastLogAt.put(key, now)
        if (previous == null || now - previous >= LOG_INTERVAL_MS) {
            XLog.i(config.logTag, message)
        }
    }

    private companion object {
        const val LOG_INTERVAL_MS = 60_000L
        const val EVENT_NAME = "push.keepalive"
    }
}

private fun <T> Set<T>.containsAny(vararg values: T): Boolean = values.any { it in this }
