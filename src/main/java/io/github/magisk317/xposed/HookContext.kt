package io.github.magisk317.xposed

/** Lambda type for DSL hook callbacks. */
typealias HookCallback = HookContext.() -> Unit

/** Lambda type for before/after actions. */
typealias HookAction = MethodHookParam.() -> Unit

/** Lambda type for replace actions. */
typealias ReplaceAction = MethodHookParam.() -> Any?

/**
 * DSL context for configuring hook behavior via [HookCallback].
 *
 * Usage:
 * ```kotlin
 * someMethod.hook {
 *     doBefore { /* called before original */ }
 *     doAfter { /* called after original */ }
 *     // or:
 *     replace { /* replace original entirely */ return@replace someValue }
 *     // conditional replace:
 *     replace({ shouldHook() }) { return@replace someValue }
 * }
 * ```
 */
class HookContext(@Suppress("unused") private val methodHook: MethodHook) {
    internal var beforeAction: HookAction? = null
        private set

    internal var afterAction: HookAction? = null
        private set

    internal var replaceAction: ReplaceAction? = null
        private set

    internal var needHook: (() -> Boolean)? = null
        private set

    fun doBefore(action: HookAction) {
        beforeAction = action
    }

    fun doAfter(action: HookAction) {
        afterAction = action
    }

    fun replace(action: ReplaceAction) {
        replaceAction = action
    }

    fun replace(hookCheck: () -> Boolean, action: ReplaceAction) {
        needHook = hookCheck
        replaceAction = action
    }
}
