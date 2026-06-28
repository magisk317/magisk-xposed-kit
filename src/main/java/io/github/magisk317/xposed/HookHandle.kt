package io.github.magisk317.xposed

import io.github.libxposed.api.XposedInterface

class HookHandle internal constructor(
    private val handle: XposedInterface.HookHandle?,
) {
    val id: String? get() = handle?.id

    fun unhook() {
        handle?.unhook()
    }
}
