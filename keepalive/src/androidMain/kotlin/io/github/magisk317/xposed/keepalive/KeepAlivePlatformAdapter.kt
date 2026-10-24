package io.github.magisk317.xposed.keepalive

import io.github.magisk317.xposed.getHookIntField
import io.github.magisk317.xposed.getHookObjectField
import io.github.magisk317.xposed.setHookIntField

/** Field values read off a live process record that the policy needs to decide. */
data class ActiveRecordMappings(
    val processName: String?,
    val nameCurrent: Boolean,
    val pidCurrent: Boolean,
)

/**
 * Reflective bridge between the hook and the framework's process record.
 *
 * All access is guarded: a layout change must degrade to "no override" rather than
 * taking down system_server.
 */
class KeepAlivePlatformAdapter(
    private val targetProcess: String,
) {

    fun adjustOomAdj(record: Any, flags: KeepAliveFlags): Boolean = runCatching {
        val processName = processName(record)
        // Android 17 moved OomAdjuster and ProcessRecordInternal into the psc package and
        // flattened mCurAdj onto the record. Older releases keep mCurAdj in mState.
        val state = runCatching { getHookObjectField(record, "mState") }.getOrNull()
        val stateOwner = state ?: record
        val currentAdj = getHookIntField(stateOwner, "mCurAdj")
        val desiredAdj = KeepAlivePolicy.desiredOomAdj(
            enabled = flags.ready,
            target = flags.oomAdj,
            targetProcess = targetProcess,
            processName = processName,
            currentAdj = currentAdj,
        ) ?: return@runCatching false
        setHookIntField(stateOwner, "mCurAdj", desiredAdj)
        true
    }.getOrDefault(false)

    fun activeRecordMappings(record: Any): ActiveRecordMappings {
        return runCatching {
            val processName = processName(record)
            val uid = getHookIntField(record, "uid")
            val pid = getHookIntField(record, "mPid")
            val service = getHookObjectField(record, "mService")
            if (processName == null || pid <= 0 || service == null) {
                return@runCatching ActiveRecordMappings(processName, false, false)
            }
            val nameCurrent = currentRecordByName(service, processName, uid) === record
            val pidCurrent = currentRecordByPid(service, pid) === record
            ActiveRecordMappings(processName, nameCurrent, pidCurrent)
        }.getOrElse { ActiveRecordMappings(processName(record), false, false) }
    }

    fun processName(record: Any): String? = runCatching {
        getHookObjectField(record, "processName") as? String
    }.getOrNull()

    private fun currentRecordByName(service: Any, processName: String, uid: Int): Any? {
        val method = service.javaClass.declaredMethods.singleOrNull {
            it.name == "getProcessRecordLocked" &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType))
        } ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(service, processName, uid)
        }.getOrNull()
    }

    private fun currentRecordByPid(service: Any, pid: Int): Any? {
        val pidMap = getHookObjectField(service, "mPidsSelfLocked") ?: return null
        val getMethod = pidMap.javaClass.declaredMethods.singleOrNull {
            it.name == "get" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return null
        return synchronized(pidMap) {
            runCatching {
                getMethod.isAccessible = true
                getMethod.invoke(pidMap, pid)
            }.getOrNull()
        }
    }
}
