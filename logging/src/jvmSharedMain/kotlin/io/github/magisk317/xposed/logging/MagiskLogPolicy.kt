package io.github.magisk317.xposed.logging

/**
 * The repository-wide log policy, derived from the build and set once per process.
 *
 * Public releases do not emit application-owned logs at all. Debug builds and explicitly
 * internal CI builds are diagnostic builds: they keep raw text, persist logs, and allow export.
 * Some CI APK/AAB jobs deliberately use a release Android build type for signing and Play
 * compatibility, so hosts must pass the diagnostic-channel decision rather than re-derive it
 * from `BuildConfig.DEBUG` inside this shared module.
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
        val loggingEnabled: Boolean,
        val verboseLogging: Boolean,
        val sanitizesText: Boolean,
    ) {
        /** Only a verbose build may collect the expensive root-side diagnostics. */
        val capturesRootHeavyDiagnostics: Boolean
            get() = verboseLogging

        /**
         * Diagnostic builds may produce an archive. Public releases have neither
         * an archive nor an application-owned log stream to package.
         */
        val allowsLogExport: Boolean
            get() = loggingEnabled
    }

    /** Release-safe default: a process that never installed a policy emits nothing. */
    @Volatile
    private var currentSnapshot: Snapshot = Snapshot(
        loggingEnabled = false,
        verboseLogging = false,
        sanitizesText = true,
    )

    fun evaluate(isDiagnosticBuild: Boolean): Snapshot {
        return Snapshot(
            loggingEnabled = isDiagnosticBuild,
            verboseLogging = isDiagnosticBuild,
            sanitizesText = !isDiagnosticBuild,
        )
    }

    fun install(isDiagnosticBuild: Boolean): Snapshot {
        val snapshot = evaluate(isDiagnosticBuild = isDiagnosticBuild)
        currentSnapshot = snapshot
        LogSanitizerConfig.setEnabled(snapshot.sanitizesText)
        return snapshot
    }

    fun current(): Snapshot = currentSnapshot

    /** Clamp a stored retention preference; anything unusable falls back to the default. */
    fun retentionDays(configuredDays: Int): Int =
        if (configuredDays < MIN_LOG_RETENTION_DAYS) DEFAULT_LOG_RETENTION_DAYS else configuredDays
}
