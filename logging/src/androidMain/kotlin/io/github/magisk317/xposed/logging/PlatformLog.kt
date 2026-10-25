package io.github.magisk317.xposed.logging

import android.util.Log

internal actual fun printPlatformLine(priority: Int, tag: String, message: String) {
    Log.println(priority, tag, message)
}
