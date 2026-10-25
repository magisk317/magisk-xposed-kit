package io.github.magisk317.xposed.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Grants the permissions the app can obtain through the root channel, so the
 * startup prompt no longer has to bounce the user through system dialogs and
 * settings pages.
 *
 * Every grant here is idempotent and additive: shared Secure settings are read
 * back and merged, never overwritten, so services the user already enabled stay
 * untouched.
 *
 * This is deliberately a thin, policy-free helper for callers that fork `su`
 * themselves. It builds commands and reports results; it decides nothing about
 * which permissions matter to an app, and it carries no privileged-runtime
 * protocol — apps whose grants run through an already-elevated resident process
 * should keep using that channel instead of forking `su` per call.
 */
object PermissionBridge {

    /** Ceiling for one root command. A hung `su` must not block the caller forever. */
    const val DEFAULT_TIMEOUT_MILLIS = 10_000L

    /** Shell commands granting [permission] through root, or null when it cannot be bridged. */
    fun commandsFor(context: Context, permission: String): List<String>? =
        commandsForPackage(context.packageName, permission)

    private fun commandsForPackage(packageName: String, permission: String): List<String>? {
        return when (permission) {
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            -> listOf("pm grant $packageName $permission")

            Manifest.permission.PACKAGE_USAGE_STATS ->
                listOf("appops set $packageName GET_USAGE_STATS allow")

            Manifest.permission.SCHEDULE_EXACT_ALARM ->
                listOf("appops set $packageName SCHEDULE_EXACT_ALARM allow")

            Manifest.permission.REQUEST_INSTALL_PACKAGES ->
                listOf("appops set $packageName REQUEST_INSTALL_PACKAGES allow")

            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
                listOf("dumpsys deviceidle whitelist +$packageName")

            else -> null
        }
    }

    /**
     * Enables or disables [component] inside the accessibility services list.
     *
     * The Secure setting is shared with every other accessibility service, so the current value is
     * merged — or filtered, when disabling — instead of replaced. Writing it outright would silently
     * disable every other service the user relies on.
     *
     * Entries are compared one by one rather than by substring, so a component whose name merely
     * contains another one is not mistaken for a match. Returns an empty list when the list already
     * holds the requested state, which lets callers skip the root round trip entirely.
     */
    fun accessibilityCommands(
        currentServices: String?,
        component: String,
        enable: Boolean = true,
    ): List<String> {
        val existing = currentServices
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.split(":")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val next = if (enable) {
            if (existing.contains(component)) return emptyList()
            existing + component
        } else {
            if (!existing.contains(component)) return emptyList()
            existing.filter { it != component }
        }
        return buildList {
            add("settings put secure enabled_accessibility_services ${next.joinToString(":")}")
            if (enable) add("settings put secure accessibility_enabled 1")
        }
    }

    /**
     * Every command worth attempting at startup. Each one is idempotent, so this
     * is safe to run on every launch; without root every command fails fast.
     *
     * Permissions the app does not declare are skipped outright: `pm grant` and
     * `appops set` of an undeclared permission can only exit non-zero, so a
     * distribution that drops a permission (e.g. a Play build without the SMS
     * channel) stops paying for its failing command on every launch.
     */
    fun bridgeCommands(
        context: Context,
        notificationListenerComponent: String,
        notificationListenerEnabled: Boolean,
        accessibilityServices: String?,
        accessibilityComponent: String?,
    ): List<String> {
        val requestedPermissions = context.declaredPermissions()
        return buildList {
            addAll(permissionCommands(context.packageName, requestedPermissions))
            if (!notificationListenerEnabled) {
                add("cmd notification allow_listener $notificationListenerComponent")
            }
            if (accessibilityComponent != null) {
                addAll(accessibilityCommands(accessibilityServices, accessibilityComponent))
            }
        }
    }

    /**
     * Grant commands for the subset of [BRIDGEABLE_PERMISSIONS] the app actually
     * declares, in declaration-list order. Pure over its inputs so callers and
     * tests can pin the filtering without a live [Context].
     */
    internal fun permissionCommands(
        packageName: String,
        requestedPermissions: Set<String>,
    ): List<String> = BRIDGEABLE_PERMISSIONS
        .filter { it in requestedPermissions }
        .flatMap { permission -> commandsForPackage(packageName, permission).orEmpty() }

    private fun Context.declaredPermissions(): Set<String> = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        packageInfo.requestedPermissions?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    /**
     * Runs [commands] through the root shell; a failure on one does not skip the rest.
     * Blocking — invoke from a background dispatcher. Diagnostics are handed to [log]
     * instead of any specific logging backend.
     *
     * Each command gets its own `su` invocation bounded by [timeoutMillis]; on expiry the child is
     * killed and the command counts as failed. Output is drained on a side thread, so a command
     * writing more than a pipe buffer cannot deadlock against the wait.
     *
     * @return true only when every command exited 0.
     */
    fun runRoot(
        commands: List<String>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        log: ((String) -> Unit)? = null,
    ): Boolean {
        var allSucceeded = true
        for (command in commands) {
            val ok = runCatching {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = OutputDrain(process)
                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    log?.invoke("timed out after ${timeoutMillis}ms for '$command'")
                    return@runCatching false
                }
                val exit = process.exitValue()
                if (exit != 0) {
                    log?.invoke("failed ($exit) for '$command': ${output.await()}")
                }
                exit == 0
            }.onFailure { error ->
                log?.invoke("unavailable for '$command': ${error.message ?: "unknown"}")
            }.getOrDefault(false)
            if (!ok) allSucceeded = false
        }
        return allSucceeded
    }

    private val BRIDGEABLE_PERMISSIONS = arrayOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.PACKAGE_USAGE_STATS,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    )
}

/**
 * Reads a process's merged output on a daemon thread so the reader never blocks the caller, and
 * hands back whatever arrived once the process is gone.
 */
private class OutputDrain(process: Process) {
    private val collected = StringBuilder()
    private val thread = Thread {
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(collected) { collected.append(buffer, 0, read) }
                }
            }
        }.onFailure {
            // The drain thread is the only thing that could report this, and whatever it
            // managed to collect before dying is still handed back by await().
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun await(joinMillis: Long = DRAIN_JOIN_MILLIS): String {
        runCatching { thread.join(joinMillis) }.onFailure {
            // A timed-out or interrupted join only means the drain is still running; the
            // collected text is read under the same lock below either way.
        }
        return synchronized(collected) { collected.toString().trim() }
    }

    private companion object {
        private const val DRAIN_JOIN_MILLIS = 500L
    }
}
