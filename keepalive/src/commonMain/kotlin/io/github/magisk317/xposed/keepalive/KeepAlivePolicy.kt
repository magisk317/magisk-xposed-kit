package io.github.magisk317.xposed.keepalive

/**
 * Platform-neutral keep-alive decisions.
 *
 * Every function is pure and takes the target package/process as a parameter, so the
 * policy is reusable from common code and testable without a framework runtime.
 * Process and system mutation stay in the Android adapter.
 */
object KeepAlivePolicy {
    const val FOREGROUND_APP_ADJ = 0
    const val STANDBY_BUCKET_ACTIVE = 10

    /** Framework's "automatic kill" reason; only these are candidates for suppression. */
    private const val AUTOMATIC_KILL_REASON = 13
    private val restrictedBuckets = setOf(20, 30, 40, 45, 50)
    private val automaticKillSubReasons = setOf(2, 3, 4, 6, 15, 18)

    /** Desired oom_adj for [processName], or null when no override applies. */
    fun desiredOomAdj(
        enabled: Boolean,
        target: Boolean,
        targetProcess: String,
        processName: String?,
        currentAdj: Int,
    ): Int? = FOREGROUND_APP_ADJ.takeIf {
        enabled && target && processName == targetProcess && currentAdj > FOREGROUND_APP_ADJ
    }

    /**
     * Desired standby bucket for [packageName], or null when no override applies.
     *
     * Only the restricted buckets are promoted. Buckets already better than ACTIVE are
     * left untouched: forcing ACTIVE on an EXEMPTED app would demote it.
     */
    fun desiredStandbyBucket(
        enabled: Boolean,
        target: Boolean,
        targetPackage: String,
        packageName: String?,
        currentBucket: Int,
    ): Int? = STANDBY_BUCKET_ACTIVE.takeIf {
        enabled && target && packageName == targetPackage && currentBucket in restrictedBuckets
    }

    /** Desired idle state for [packageName], or null when no override applies. */
    fun desiredIdleState(
        enabled: Boolean,
        target: Boolean,
        targetPackage: String,
        packageName: String?,
        idle: Boolean,
    ): Boolean? = false.takeIf {
        enabled && target && packageName == targetPackage && idle
    }

    /**
     * True when an automatic kill of [processName] should be suppressed.
     *
     * [nameMapping] and [pidMapping] must both confirm that the record is the live one
     * for the process. Sparing a stale record would protect the wrong process while the
     * real one is still killable.
     */
    fun shouldSuppressKill(
        enabled: Boolean,
        target: Boolean,
        targetProcess: String,
        processName: String?,
        reason: Int?,
        subReason: Int?,
        nameMapping: Boolean,
        pidMapping: Boolean,
    ): Boolean = enabled && target &&
        processName == targetProcess &&
        reason == AUTOMATIC_KILL_REASON &&
        subReason in automaticKillSubReasons &&
        nameMapping &&
        pidMapping

    /** True when an automatic package-wide kill of [packageName] should be suppressed. */
    fun shouldSuppressPackageKill(
        enabled: Boolean,
        target: Boolean,
        targetPackage: String,
        packageName: String?,
        reason: Int?,
        subReason: Int?,
        callerWillRestart: Boolean?,
        doit: Boolean?,
        evenPersistent: Boolean?,
        setRemoved: Boolean?,
        uninstalling: Boolean?,
    ): Boolean = enabled && target &&
        packageName == targetPackage &&
        reason == AUTOMATIC_KILL_REASON &&
        subReason in automaticKillSubReasons &&
        callerWillRestart == false &&
        doit == true &&
        evenPersistent == false &&
        setRemoved == false &&
        uninstalling == false
}
