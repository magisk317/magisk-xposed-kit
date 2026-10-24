package io.github.magisk317.xposed.logging

/**
 * The repository-wide log policy, derived from the build and set once per process.
 *
 * Formal releases print as little as possible, sanitize whatever they write, and refuse to
 * export a bundle. Debug builds are the capture channel, so they keep raw text and export
 * everything they can reach. A CI flag never widens a release: a Play beta AAB is still a
 * distributed release, so the build type alone selects the capture channel.
 *
 * The decision comes from build flags only. A stored preference is never consulted: a
 * preference survives an update, and a released app that stopped honoring it after the UI
 * switch was removed would otherwise keep emitting plaintext behind the user's back.
 *
 * Hosts call [install] from application and hook entry points, passing their own
 * [BuildConfig] values, and then read [current] instead of re-deriving the policy.
 */
object MagiskLogPolicy {
    /** Bundles and stored logs are kept for two days unless the user raises it. */
    const val DEFAULT_LOG_RETENTION_DAYS = 2
    const val MIN_LOG_RETENTION_DAYS = 1

    data class Snapshot(
        val verboseLogging: Boolean,
        val sanitizesText: Boolean,
    ) {
        /** Only a verbose build may collect the expensive root-side diagnostics. */
        val capturesRootHeavyDiagnostics: Boolean
            get() = verboseLogging

        /**
         * A release keeps its logs on-device. Only a debug build or an explicit
         * local plaintext override may produce an archive.
         */
        val allowsLogExport: Boolean
            get() = verboseLogging || !sanitizesText
    }

    /** Release-safe default: a process that never installed a policy sanitizes and stays quiet. */
    @Volatile
    private var currentSnapshot: Snapshot = Snapshot(verboseLogging = false, sanitizesText = true)

    fun evaluate(
        isDebugBuild: Boolean,
        allowPlaintextLogs: Boolean = false,
    ): Snapshot {
        return Snapshot(
            verboseLogging = isDebugBuild,
            sanitizesText = !(isDebugBuild || allowPlaintextLogs),
        )
    }

    fun install(
        isDebugBuild: Boolean,
        allowPlaintextLogs: Boolean = false,
    ): Snapshot {
        val snapshot = evaluate(
            isDebugBuild = isDebugBuild,
            allowPlaintextLogs = allowPlaintextLogs,
        )
        currentSnapshot = snapshot
        LogSanitizerConfig.setEnabled(snapshot.sanitizesText)
        return snapshot
    }

    fun current(): Snapshot = currentSnapshot

    /** Clamp a stored retention preference; anything unusable falls back to the default. */
    fun retentionDays(configuredDays: Int): Int =
        if (configuredDays < MIN_LOG_RETENTION_DAYS) DEFAULT_LOG_RETENTION_DAYS else configuredDays
}
