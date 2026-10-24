package io.github.magisk317.xposed.diagnostics

import io.github.magisk317.xposed.logging.MagiskLogPolicy

/**
 * How heavy an export is allowed to be.
 *
 * Always derived from the build through [MagiskLogPolicy]: a distributed release stays
 * bounded, a CI or debug build captures everything it can reach. A stored preference never
 * selects it — the manager process and the runtime process have to agree without exchanging
 * state, and a preference that survives an update can leave a release collecting plaintext.
 */
enum class DiagnosticExportMode {
    STANDARD,
    FULL,
    ;

    val usesBoundedLogcat: Boolean
        get() = this == STANDARD

    val capturesRootHeavyDiagnostics: Boolean
        get() = this == FULL

    companion object {
        fun of(policy: MagiskLogPolicy.Snapshot): DiagnosticExportMode =
            if (policy.verboseLogging) FULL else STANDARD

        fun current(): DiagnosticExportMode = of(MagiskLogPolicy.current())
    }
}
