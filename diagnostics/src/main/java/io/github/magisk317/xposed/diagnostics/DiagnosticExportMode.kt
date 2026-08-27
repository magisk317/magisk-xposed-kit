package io.github.magisk317.xposed.diagnostics

enum class DiagnosticExportMode {
    STANDARD,
    FULL,
    ;

    val usesBoundedLogcat: Boolean
        get() = this == STANDARD

    val capturesRootHeavyDiagnostics: Boolean
        get() = this == FULL

    companion object {
        fun fromDebugLogging(debugLoggingEnabled: Boolean): DiagnosticExportMode =
            if (debugLoggingEnabled) FULL else STANDARD
    }
}
