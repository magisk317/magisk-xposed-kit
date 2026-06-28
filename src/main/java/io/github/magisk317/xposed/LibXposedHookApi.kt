package io.github.magisk317.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * [HookApi] implementation backed by libxposed. Supports hot-reload hook replacement.
 *
 * @param module The active xposed module instance.
 * @param hookIdPrefix Prefix for hook IDs (e.g. "smscode", "mipush"). Each consumer
 *                     should use its own prefix to avoid collisions during hot reload.
 */
class LibXposedHookApi(
    private val module: XposedModule,
    private val hookIdPrefix: String = "xposed-kit",
) : HookApi {

    private val pendingOldHooksById = linkedMapOf<String, ArrayDeque<XposedInterface.HookHandle>>()
    private val pendingOldHooksByExecutable = linkedMapOf<String, ArrayDeque<XposedInterface.HookHandle>>()
    private val pendingOldHooks = linkedSetOf<XposedInterface.HookHandle>()
    private var replacingOldHooks = false

    // -- Hot reload lifecycle --

    fun beginHotReload(oldHandles: List<XposedInterface.HookHandle>) {
        replacingOldHooks = true
        pendingOldHooksById.clear()
        pendingOldHooksByExecutable.clear()
        pendingOldHooks.clear()
        oldHandles.forEach { handle ->
            val id = handle.id
            pendingOldHooks += handle
            if (id.isNullOrBlank()) {
                return@forEach
            } else {
                pendingOldHooksById.getOrPut(id) { ArrayDeque() }.addLast(handle)
                parseExecutableSignature(id)?.let { signature ->
                    pendingOldHooksByExecutable.getOrPut(signature) { ArrayDeque() }.addLast(handle)
                }
            }
        }
    }

    fun finishHotReload(): Int {
        val staleHandles = pendingOldHooks.toList()
        staleHandles.forEach { handle ->
            runCatching { handle.unhook() }
                .onFailure { module.log(Log.WARN, LOG_TAG, "Failed to unhook stale hot reload handle", it) }
        }
        val removed = staleHandles.size
        pendingOldHooksById.clear()
        pendingOldHooksByExecutable.clear()
        pendingOldHooks.clear()
        replacingOldHooks = false
        return removed
    }

    fun abortHotReload() {
        pendingOldHooksById.clear()
        pendingOldHooksByExecutable.clear()
        pendingOldHooks.clear()
        replacingOldHooks = false
    }

    // -- HookApi --

    override fun hookMethod(member: Member, callback: MethodHook): HookHandle? {
        if (member is Method && (Modifier.isAbstract(member.modifiers) || Modifier.isNative(member.modifiers))) {
            return null
        }
        val executable = member as? Executable ?: return null
        val handle = hookExecutable(executable, callback, createHooker(member, callback))
        return HookHandle(handle)
    }

    override fun hookAllConstructors(clazz: Class<*>, callback: MethodHook): Set<HookHandle> {
        return clazz.declaredConstructors.mapNotNull { hookMethod(it, callback) }.toSet()
    }

    override fun hookAllMethods(clazz: Class<*>, methodName: String, callback: MethodHook): Set<HookHandle> {
        return clazz.declaredMethods
            .filter { it.name == methodName }
            .mapNotNull { hookMethod(it, callback) }
            .toSet()
    }

    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
        if (tr != null) {
            module.log(priority, tag, msg, tr)
        } else {
            module.log(priority, tag, msg)
        }
    }

    override fun getApiVersion(): Int? = module.apiVersion
    override fun getFrameworkName(): String? = module.frameworkName
    override fun getFrameworkVersionCode(): Long? = module.frameworkVersionCode

    // -- Internal hook execution with hot-reload replacement --

    internal fun hookExecutable(
        executable: Executable,
        callback: MethodHook,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle {
        val hookId = buildHookId(executable, callback)
        val legacyHookId = buildLegacyHookId(executable, callback)
        val executableSignature = buildExecutableSignature(executable)
        return sequenceOf(hookId, legacyHookId)
            .distinct()
            .firstNotNullOfOrNull { id -> takePendingHook(pendingOldHooksById[id]) }
            ?.replaceHook(hooker)
            ?: takeUniquePendingHookByExecutable(executableSignature)
                ?.also {
                    module.log(Log.INFO, LOG_TAG, "Replacing hot reload hook by executable signature: $executableSignature")
                }
                ?.replaceHook(hooker)
            ?: module.hook(executable).setId(hookId).intercept(hooker)
    }

    // -- Hook ID construction --

    private fun buildHookId(executable: Executable, callback: MethodHook): String {
        return "$hookIdPrefix:${callback.stableId ?: callback.javaClass.name}@${buildExecutableSignature(executable)}"
    }

    private fun buildLegacyHookId(executable: Executable, callback: MethodHook): String {
        return "$hookIdPrefix:${callback.javaClass.name}@${buildExecutableSignature(executable)}"
    }

    private fun buildExecutableSignature(executable: Executable): String {
        val params = executable.parameterTypes.joinToString(",") { it.name }
        return "${executable.declaringClass.name}#${executable.name}($params)"
    }

    // -- Hot-reload hook matching --

    private fun takePendingHook(queue: ArrayDeque<XposedInterface.HookHandle>?): XposedInterface.HookHandle? {
        if (!replacingOldHooks || queue == null) return null
        while (queue.isNotEmpty()) {
            val handle = queue.removeFirst()
            if (pendingOldHooks.remove(handle)) return handle
        }
        return null
    }

    private fun takeUniquePendingHookByExecutable(executableSignature: String): XposedInterface.HookHandle? {
        if (!replacingOldHooks) return null
        val queue = pendingOldHooksByExecutable[executableSignature] ?: return null
        var candidate: XposedInterface.HookHandle? = null
        var liveCount = 0
        queue.forEach { handle ->
            if (handle in pendingOldHooks) {
                liveCount += 1
                candidate = handle
                if (liveCount > 1) return null
            }
        }
        val handle = candidate ?: return null
        if (!queue.remove(handle)) return null
        return if (pendingOldHooks.remove(handle)) handle else null
    }

    private fun parseExecutableSignature(hookId: String): String? {
        if (!hookId.startsWith("$hookIdPrefix:")) return null
        val signature = hookId.substringAfter('@', missingDelimiterValue = "")
        return signature.ifBlank { null }
    }

    // -- Hooker creation --

    private fun createHooker(member: Member, callback: MethodHook): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val args = chain.args.toTypedArray()
                val param = MethodHookParam(chain.executable, chain.thisObject, args, chain, { null })
                val beforeFailure = runCatching {
                    callback.beforeHookedMethod(param)
                }.exceptionOrNull()
                if (beforeFailure != null) {
                    module.log(Log.WARN, LOG_TAG, "Hook before failed: ${member.name}", beforeFailure)
                }
                if (param.returnEarly) {
                    val throwable = param.throwable
                    if (throwable != null) throw throwable
                    return param.result
                }

                var result: Any? = null
                var throwable: Throwable? = null
                val proceedFailure = runCatching {
                    result = chain.proceed(param.args)
                }.exceptionOrNull()
                if (proceedFailure != null) {
                    throwable = proceedFailure
                }
                param.returnEarly = false
                param.result = result
                param.throwable = throwable
                val afterFailure = runCatching {
                    callback.afterHookedMethod(param)
                }.exceptionOrNull()
                if (afterFailure != null) {
                    module.log(Log.WARN, LOG_TAG, "Hook after failed: ${member.name}", afterFailure)
                }
                val finalThrowable = param.throwable
                if (finalThrowable != null) throw finalThrowable
                return param.result
            }
        }
    }

    companion object {
        private const val LOG_TAG = "XposedKit"
    }
}
