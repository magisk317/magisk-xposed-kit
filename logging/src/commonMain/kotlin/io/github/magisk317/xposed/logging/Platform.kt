package io.github.magisk317.xposed.logging

/**
 * The two things this module cannot express in common code.
 *
 * [printPlatformLine] is where a line lands (logcat on Android, stdout/stderr on the JVM);
 * [currentTimeMillis] is the wall clock, which `kotlin.stdlib` common does not expose.
 * Both are internal: consumers install a sink or a printer, they do not call the platform.
 */
internal expect fun currentTimeMillis(): Long

internal expect fun printPlatformLine(priority: Int, tag: String, message: String)
