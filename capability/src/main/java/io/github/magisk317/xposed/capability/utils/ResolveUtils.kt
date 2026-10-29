package io.github.magisk317.xposed.capability.utils

import io.github.magisk317.xposed.runNonFatalOrNull

fun resolveStaticIntFieldOrDefault(owner: Class<*>, fieldName: String, defaultValue: Int): Int {
    return runNonFatalOrNull { owner.getField(fieldName).getInt(null) } ?: defaultValue
}
