package io.github.magisk317.xposed.diagnostics

/**
 * Reads the per-module commit hash that `magisk-build-logic` stamps into `BuildConfig`.
 *
 * Reflection on purpose: a host may pin an older build-logic than this library expects, and a
 * missing stamp has to degrade to [UNKNOWN] rather than break that host's compile.
 */
object DiagnosticBuildStamps {
    const val UNKNOWN = "unknown"

    val kitCommitHash: String by lazy {
        moduleCommitHash("io.github.magisk317.xposed.diagnostics.BuildConfig")
    }

    fun moduleCommitHash(buildConfigClassName: String): String = runCatching {
        Class.forName(buildConfigClassName)
            .getField("MODULE_COMMIT_HASH")
            .get(null)
            ?.toString()
            .orEmpty()
    }.getOrDefault(UNKNOWN).ifBlank { UNKNOWN }
}
