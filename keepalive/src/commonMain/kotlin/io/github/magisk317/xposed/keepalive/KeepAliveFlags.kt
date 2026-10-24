package io.github.magisk317.xposed.keepalive

/**
 * Runtime snapshot of the keep-alive switches.
 *
 * [ready] is false until a snapshot has been read successfully. A provider failure
 * invalidates the whole snapshot rather than keeping the previous one: retaining a
 * stale decision would leave anti-kill or resource-bypass behaviour active after the
 * user revoked it.
 */
data class KeepAliveFlags(
    val ready: Boolean = false,
    val oomAdj: Boolean = false,
    val antiKill: Boolean = false,
    val standbyBypass: Boolean = false,
    val dozeBypass: Boolean = false,
)

/** Supplies the current [KeepAliveFlags] snapshot. */
fun interface KeepAliveFlagsProvider {
    fun current(): KeepAliveFlags
}

/** Hook capabilities a consumer may opt into. */
enum class KeepAliveCapability {
    /** Promote the target's oom_adj to foreground. */
    OOM_APPLY,

    /** Suppress an automatic kill of the target process record. */
    KILL_GUARD,

    /** Suppress an automatic package-wide kill of the target. */
    PACKAGE_KILL_GUARD,

    /** Promote the target's standby bucket out of the restricted set. */
    STANDBY_BUCKET,

    /** Keep the target out of forced app idle. */
    STANDBY_IDLE,

    /** Keep the target out of forced idle state. */
    STANDBY_FORCE_IDLE,
}
