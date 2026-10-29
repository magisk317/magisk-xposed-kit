package io.github.magisk317.xposed.runtime.state

import android.content.Context
import java.util.Base64
import java.io.File

/**
 * Product-neutral, no-backup runtime state for injected Xposed hooks.
 *
 * The store intentionally has no SMS, forwarding or entitlement concepts. Product providers can
 * expose this state through their own ContentProvider without making the kit depend on them.
 */
object RuntimeStateStore {
    private const val DIRECTORY = "runtime_state"
    private const val FILE = "state"
    private const val KEY_SERVICE_BIND = "service_bind"
    private const val KEY_HOOK_PROCESS_PREFIX = "hook_process."
    private const val KEY_HOOK_INSTALL_PREFIX = "hook_install."
    private const val KEY_CALL_SOURCE = "call_source"
    private const val KEY_NOTIFY_EVENT = "notify_event"
    private const val SEPARATOR = "|"
    private const val MAX_PROCESSES = 16
    private const val MAX_INSTALLS = 32

    private val lock = Any()

    fun snapshot(context: Context): RuntimeStateSnapshot = synchronized(lock) {
        readLocked(fileFor(context))
    }

    fun recordServiceBind(context: Context, frameworkName: String, frameworkVersion: String) {
        update(context) { current ->
            current.copy(lastServiceBindAtMs = System.currentTimeMillis(), lastServiceFrameworkName = frameworkName, lastServiceFrameworkVersion = frameworkVersion)
        }
    }

    fun recordHookProcessOnline(
        context: Context,
        packageName: String,
        processName: String,
        source: String,
        moduleVersion: Int,
    ) {
        update(context) { current ->
            val next = (current.hookProcesses.filterNot { it.packageName == packageName && it.processName == processName } + HookProcessStatus(packageName, processName, System.currentTimeMillis(), source, moduleVersion))
                .sortedByDescending { it.lastSeenAtMs }
                .take(MAX_PROCESSES)
            current.copy(hookProcesses = next)
        }
    }

    fun recordHookInstall(
        context: Context,
        hook: String,
        status: String,
        packageName: String,
        processName: String,
        detail: String,
    ) {
        if (hook.isBlank() || status.isBlank()) return
        update(context) { current ->
            val next = (current.hookInstalls.filterNot { it.hook == hook } + HookInstallStatus(hook, status, packageName, processName, detail, System.currentTimeMillis()))
                .sortedByDescending { it.lastSeenAtMs }
                .take(MAX_INSTALLS)
            current.copy(hookInstalls = next)
        }
    }

    fun recordCallNotificationSource(context: Context, packageName: String, channelId: String, category: String) {
        update(context) { it.copy(lastCallNotificationSource = CallNotificationSourceSnapshot(packageName, channelId, category, System.currentTimeMillis())) }
    }

    fun recordNotifyEvent(context: Context, source: String, packageName: String, channelId: String, messageType: String, skipReason: String) {
        if (source.isBlank() || packageName.isBlank()) return
        update(context) { it.copy(lastNotifyEvent = NotifyEventSnapshot(source, packageName, channelId, messageType, skipReason, System.currentTimeMillis())) }
    }

    private fun update(context: Context, transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot) = synchronized(lock) {
        val target = fileFor(context)
        write(target, transform(readLocked(target)))
    }

    private fun fileFor(context: Context): File = File(File(context.noBackupFilesDir, DIRECTORY), FILE)

    private fun readLocked(file: File): RuntimeStateSnapshot {
        if (!file.isFile) return RuntimeStateSnapshot()
        val values = mutableMapOf<String, String>()
        runCatching { file.forEachLine { line -> line.indexOf('=').takeIf { it > 0 }?.let { values[line.substring(0, it)] = line.substring(it + 1) } } }
        return RuntimeStateSnapshot(
            lastServiceBindAtMs = values[KEY_SERVICE_BIND]?.let(RuntimeStateCodec::decodeService)?.first ?: 0L,
            lastServiceFrameworkName = values[KEY_SERVICE_BIND]?.let(RuntimeStateCodec::decodeService)?.second.orEmpty(),
            lastServiceFrameworkVersion = values[KEY_SERVICE_BIND]?.let(RuntimeStateCodec::decodeService)?.third.orEmpty(),
            hookProcesses = values.entries.filter { it.key.startsWith(KEY_HOOK_PROCESS_PREFIX) }.mapNotNull { RuntimeStateCodec.decodeProcess(it.key, it.value) }.sortedByDescending { it.lastSeenAtMs },
            hookInstalls = values.entries.filter { it.key.startsWith(KEY_HOOK_INSTALL_PREFIX) }.mapNotNull { RuntimeStateCodec.decodeInstall(it.key, it.value) }.sortedByDescending { it.lastSeenAtMs },
            lastCallNotificationSource = values[KEY_CALL_SOURCE]?.let(RuntimeStateCodec::decodeCallSource) ?: CallNotificationSourceSnapshot(),
            lastNotifyEvent = values[KEY_NOTIFY_EVENT]?.let(RuntimeStateCodec::decodeNotifyEvent) ?: NotifyEventSnapshot(),
        )
    }

    private fun write(file: File, state: RuntimeStateSnapshot) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                appendLine("$KEY_SERVICE_BIND=${RuntimeStateCodec.encodeService(state.lastServiceBindAtMs, state.lastServiceFrameworkName, state.lastServiceFrameworkVersion)}")
                state.hookProcesses.take(MAX_PROCESSES).forEach { appendLine("$KEY_HOOK_PROCESS_PREFIX${RuntimeStateCodec.encode(it.packageName)}.${RuntimeStateCodec.encode(it.processName)}=${RuntimeStateCodec.encodeProcess(it)}") }
                state.hookInstalls.take(MAX_INSTALLS).forEach { appendLine("$KEY_HOOK_INSTALL_PREFIX${RuntimeStateCodec.encode(it.hook)}=${RuntimeStateCodec.encodeInstall(it)}") }
                appendLine("$KEY_CALL_SOURCE=${RuntimeStateCodec.encodeCallSource(state.lastCallNotificationSource)}")
                appendLine("$KEY_NOTIFY_EVENT=${RuntimeStateCodec.encodeNotifyEvent(state.lastNotifyEvent)}")
            })
        }
    }
}

internal object RuntimeStateCodec {
    private const val SEPARATOR = "|"
    private fun encodeToken(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    fun encode(value: String): String = encodeToken(value)
    private fun decode(value: String): String? = runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
    fun encodeService(at: Long, name: String, version: String) = listOf(at.toString(), encodeToken(name), encodeToken(version)).joinToString(SEPARATOR)
    fun decodeService(raw: String): Triple<Long, String, String> { val p = raw.split(SEPARATOR); return Triple(p.getOrNull(0)?.toLongOrNull() ?: 0L, p.getOrNull(1)?.let(::decode).orEmpty(), p.getOrNull(2)?.let(::decode).orEmpty()) }
    fun encodeProcess(v: HookProcessStatus) = listOf(v.lastSeenAtMs, v.moduleVersion, encodeToken(v.source)).joinToString(SEPARATOR)
    fun decodeProcess(key: String, raw: String): HookProcessStatus? { val parts = key.removePrefix("hook_process.").split('.'); val p = raw.split(SEPARATOR); if (parts.size != 2 || p.size < 2) return null; return HookProcessStatus(decode(parts[0]).orEmpty(), decode(parts[1]).orEmpty(), p[0].toLongOrNull() ?: 0L, p.getOrNull(2)?.let(::decode).orEmpty(), p[1].toIntOrNull() ?: 0) }
    fun encodeInstall(v: HookInstallStatus) = listOf(v.lastSeenAtMs, encodeToken(v.status), encodeToken(v.packageName), encodeToken(v.processName), encodeToken(v.detail)).joinToString(SEPARATOR)
    fun decodeInstall(key: String, raw: String): HookInstallStatus? { val p = raw.split(SEPARATOR); if (p.size < 5) return null; return HookInstallStatus(decode(key.removePrefix("hook_install.")).orEmpty(), decode(p[1]).orEmpty(), decode(p[2]).orEmpty(), decode(p[3]).orEmpty(), decode(p[4]).orEmpty(), p[0].toLongOrNull() ?: 0L) }
    fun encodeCallSource(v: CallNotificationSourceSnapshot) = listOf(v.lastSeenAtMs, encodeToken(v.packageName), encodeToken(v.channelId), encodeToken(v.category)).joinToString(SEPARATOR)
    fun decodeCallSource(raw: String): CallNotificationSourceSnapshot { val p = raw.split(SEPARATOR); return CallNotificationSourceSnapshot(decode(p.getOrNull(1).orEmpty()).orEmpty(), decode(p.getOrNull(2).orEmpty()).orEmpty(), decode(p.getOrNull(3).orEmpty()).orEmpty(), p.getOrNull(0)?.toLongOrNull() ?: 0L) }
    fun encodeNotifyEvent(v: NotifyEventSnapshot) = listOf(v.lastSeenAtMs, encodeToken(v.source), encodeToken(v.packageName), encodeToken(v.channelId), encodeToken(v.messageType), encodeToken(v.skipReason)).joinToString(SEPARATOR)
    fun decodeNotifyEvent(raw: String): NotifyEventSnapshot { val p = raw.split(SEPARATOR); return NotifyEventSnapshot(decode(p.getOrNull(1).orEmpty()).orEmpty(), decode(p.getOrNull(2).orEmpty()).orEmpty(), decode(p.getOrNull(3).orEmpty()).orEmpty(), decode(p.getOrNull(4).orEmpty()).orEmpty(), decode(p.getOrNull(5).orEmpty()).orEmpty(), p.getOrNull(0)?.toLongOrNull() ?: 0L) }
}
