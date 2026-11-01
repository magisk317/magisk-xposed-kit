package io.github.magisk317.xposed.service

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.runtime.state.FrameworkHookTargetStatus
import io.github.magisk317.xposed.runtime.state.RuntimeStateStore

/**
 * The processes the framework reports as running the module code.
 *
 * The framework is the only side that knows which processes carry the module, so this is what keeps
 * the activation status independent from the hook having to reach the app through a content
 * provider the system may refuse to start.
 */
object FrameworkHookTargets {

    /**
     * Reads the framework's view of the hooked processes.
     *
     * Returns null when the framework could not answer. An older framework has no
     * `getRunningTargets` and answers with [UnsupportedOperationException], so the caller keeps what
     * it recorded last time instead of wiping it.
     */
    fun read(context: Context, service: XposedService): List<FrameworkHookTargetStatus>? {
        val targets = runCatching { service.runningTargets }.getOrElse { error ->
            XLog.w(message = "failed to read the framework hook targets: ${error.message}")
            return null
        }
        val packageManager = context.packageManager
        return targets.map { target ->
            FrameworkHookTargetStatus(
                packageName = packageManager.getPackagesForUid(target.uid)?.firstOrNull().orEmpty(),
                processName = target.processName,
                pid = target.pid,
                uid = target.uid,
                state = target.state.name,
            )
        }
    }

    /**
     * Records the framework's view of the hooked processes into the kit's runtime state store.
     *
     * @return true when the framework answered and the targets were recorded.
     */
    fun record(
        context: Context,
        service: XposedService,
        source: String = "framework_targets",
    ): Boolean {
        val targets = read(context, service) ?: return false
        RuntimeStateStore.recordFrameworkHookTargets(
            context = context,
            targets = targets,
            source = source,
        )
        return true
    }
}
