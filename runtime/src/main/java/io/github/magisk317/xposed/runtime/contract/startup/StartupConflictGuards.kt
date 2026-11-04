package io.github.magisk317.xposed.runtime.contract.startup

/**
 * Startup mutual-exclusion guard shared by the product apps.
 *
 * The bypass build flag skips the peer-installed check (for co-install development);
 * the log line names the running product so bypasses are attributable.
 */
object StartupConflictGuards {
    /**
     * Returns true when startup must stay blocked because the peer product is installed.
     * [isPeerInstalled] stays host-supplied so each app keeps its own package probe.
     */
    fun shouldBlockForInstalledPeer(
        bypassedByBuildFlag: Boolean,
        productName: String,
        isPeerInstalled: () -> Boolean,
        warn: (String) -> Unit,
    ): Boolean {
        if (bypassedByBuildFlag) {
            warn("$productName conflict guard bypassed by build flag allowConflictBypass=true")
            return false
        }
        return isPeerInstalled()
    }
}
