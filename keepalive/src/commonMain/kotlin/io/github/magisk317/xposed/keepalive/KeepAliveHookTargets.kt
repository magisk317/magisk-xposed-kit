package io.github.magisk317.xposed.keepalive

import java.lang.reflect.Method

/**
 * The framework descriptors the keep-alive hook targets.
 *
 * Descriptors are the part of the hook that breaks silently: a framework refactor
 * reorders a parameter, the hook keeps compiling, and its argument indexes now point at
 * different values. Keeping the table and its matcher together in shared, testable code
 * means such a change fails in a unit test instead of in system_server.
 *
 * Matching is on the full descriptor (owner + name + parameter types + return type),
 * never on the name alone: the framework overloads these entry points heavily, so a
 * name-only match would bind the hook to an arbitrary overload.
 */
object KeepAliveHookTargets {
    private const val LEGACY_OOM_ADJUSTER = "com.android.server.am.OomAdjuster"
    private const val MODERN_OOM_ADJUSTER = "com.android.server.am.psc.OomAdjuster"
    private const val MODERN_PROCESS_RECORD = "com.android.server.am.psc.ProcessRecordInternal"
    private const val PROCESS_RECORD = "com.android.server.am.ProcessRecord"
    private const val PROCESS_LIST = "com.android.server.am.ProcessList"
    private const val APP_STANDBY_CONTROLLER = "com.android.server.usage.AppStandbyController"

    /** Ordered OomAdjuster owners: the Android 17 psc layout first. */
    val oomApplyOwners = listOf(MODERN_OOM_ADJUSTER, LEGACY_OOM_ADJUSTER)

    val oomApply = listOf(
        IndexedHookTarget(
            capability = KeepAliveCapability.OOM_APPLY,
            shape = MethodShape(
                owner = MODERN_OOM_ADJUSTER,
                name = "applyOomAdjLSP",
                parameterTypes = listOf(MODERN_PROCESS_RECORD, "boolean"),
                returnType = "void",
            ),
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.OOM_APPLY,
            shape = MethodShape(
                owner = LEGACY_OOM_ADJUSTER,
                name = "applyOomAdjLSP",
                parameterTypes = listOf(PROCESS_RECORD, "boolean", "long", "long"),
                returnType = "boolean",
            ),
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.OOM_APPLY,
            shape = MethodShape(
                owner = LEGACY_OOM_ADJUSTER,
                name = "applyOomAdjLSP",
                parameterTypes = listOf(PROCESS_RECORD, "boolean", "long", "long", "int", "boolean"),
                returnType = "boolean",
            ),
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.OOM_APPLY,
            shape = MethodShape(
                owner = LEGACY_OOM_ADJUSTER,
                name = "applyOomAdjLSP",
                parameterTypes = listOf(PROCESS_RECORD, "boolean", "long", "long", "int"),
                returnType = "boolean",
            ),
        ),
    )

    val killLocked = listOf(
        IndexedHookTarget(
            capability = KeepAliveCapability.KILL_GUARD,
            shape = MethodShape(
                owner = PROCESS_RECORD,
                name = "killLocked",
                parameterTypes = listOf("java.lang.String", "java.lang.String", "int", "int", "boolean"),
                returnType = "void",
            ),
            valueIndex = 2,
            secondaryValueIndex = 3,
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.KILL_GUARD,
            shape = MethodShape(
                owner = PROCESS_RECORD,
                name = "killLocked",
                parameterTypes = listOf(
                    "java.lang.String",
                    "java.lang.String",
                    "int",
                    "int",
                    "android.app.ApplicationExitInfo\$AnrInfo",
                    "boolean",
                    "boolean",
                ),
                returnType = "void",
            ),
            valueIndex = 2,
            secondaryValueIndex = 3,
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.KILL_GUARD,
            shape = MethodShape(
                owner = PROCESS_RECORD,
                name = "killLocked",
                parameterTypes = listOf(
                    "java.lang.String",
                    "java.lang.String",
                    "int",
                    "int",
                    "boolean",
                    "boolean",
                ),
                returnType = "void",
            ),
            valueIndex = 2,
            secondaryValueIndex = 3,
        ),
    )

    /** API 33 package-cleanup boundary, before MIUI sends SIGSTOP or removes process records. */
    val packageKill = listOf(
        IndexedHookTarget(
            capability = KeepAliveCapability.PACKAGE_KILL_GUARD,
            shape = MethodShape(
                owner = PROCESS_LIST,
                name = "killPackageProcessesLSP",
                parameterTypes = listOf(
                    "java.lang.String",
                    "int",
                    "int",
                    "int",
                    "boolean",
                    "boolean",
                    "boolean",
                    "boolean",
                    "boolean",
                    "boolean",
                    "int",
                    "int",
                    "java.lang.String",
                ),
                returnType = "boolean",
            ),
            packageIndex = 0,
            valueIndex = 10,
            secondaryValueIndex = 11,
        ),
    )

    val standbyBucket = listOf(
        IndexedHookTarget(
            capability = KeepAliveCapability.STANDBY_BUCKET,
            shape = MethodShape(
                owner = APP_STANDBY_CONTROLLER,
                name = "setAppStandbyBucket",
                parameterTypes = listOf("java.lang.String", "int", "int", "int", "int"),
                returnType = "void",
            ),
            packageIndex = 0,
            valueIndex = 1,
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.STANDBY_BUCKET,
            shape = MethodShape(
                owner = APP_STANDBY_CONTROLLER,
                name = "setAppStandbyBucket",
                parameterTypes = listOf("java.lang.String", "int", "int", "int"),
                returnType = "void",
            ),
            packageIndex = 0,
            valueIndex = 2,
        ),
        IndexedHookTarget(
            capability = KeepAliveCapability.STANDBY_BUCKET,
            shape = MethodShape(
                owner = APP_STANDBY_CONTROLLER,
                name = "setAppStandbyBucket",
                parameterTypes = listOf("java.lang.String", "int", "int", "int", "long", "boolean"),
                returnType = "void",
            ),
            packageIndex = 0,
            valueIndex = 2,
        ),
    )

    val appIdle = listOf(
        IndexedHookTarget(
            capability = KeepAliveCapability.STANDBY_IDLE,
            shape = MethodShape(
                owner = APP_STANDBY_CONTROLLER,
                name = "setAppIdleAsync",
                parameterTypes = listOf("java.lang.String", "boolean", "int"),
                returnType = "void",
            ),
            packageIndex = 0,
            valueIndex = 1,
        ),
    )

    val forceIdle = listOf(
        IndexedHookTarget(
            capability = KeepAliveCapability.STANDBY_FORCE_IDLE,
            shape = MethodShape(
                owner = APP_STANDBY_CONTROLLER,
                name = "forceIdleState",
                parameterTypes = listOf("java.lang.String", "int", "boolean"),
                returnType = "void",
            ),
            packageIndex = 0,
            valueIndex = 2,
        ),
    )

    /**
     * Resolves [targets] against [owner].
     *
     * [MethodResolution.Ambiguous] is a failure for callers: two descriptors matching the
     * same method makes the index-to-role mapping undecidable, and guessing would be worse
     * than leaving the capability uninstalled.
     */
    fun resolve(owner: Class<*>, targets: List<IndexedHookTarget>): MethodResolution<Method> {
        return resolveTargets(
            targets = targets,
            candidates = owner.declaredMethods.toList(),
            describe = { method ->
                MethodShape(
                    owner = method.declaringClass.name,
                    name = method.name,
                    parameterTypes = method.parameterTypes.map { it.name },
                    returnType = method.returnType.name,
                )
            },
        )
    }

    /** Human readable descriptor, used in hook installation logs. */
    fun describe(target: IndexedHookTarget): String = with(target.shape) {
        "$owner#$name(${parameterTypes.joinToString()})=$returnType"
    }
}
