package io.github.magisk317.xposed.capability.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.magisk317.xposed.capability.helper.AutoInputBroadcastHelper
import io.github.magisk317.xposed.capability.system.SystemInputInjectorHook
import io.github.magisk317.xposed.logging.XLog

/**
 * Local auto-input broadcast and toast actions for host hook layers.
 *
 * Host `InputHelper` objects delegate here so the wiring (action resolver,
 * main-looper toast, logging) stays in one place; [tokenProvider] stays
 * optional because only hosts that mirror the module IPC token need it.
 */
object LocalInputActions {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun sendText(
        context: Context,
        text: String?,
        autoEnter: Boolean = false,
        inputIntervalMs: Long = 0L,
        attemptId: Long? = null,
        tokenProvider: () -> String = { "" },
    ) {
        AutoInputBroadcastHelper.sendText(
            context = context,
            text = text,
            autoEnter = autoEnter,
            inputIntervalMs = inputIntervalMs,
            attemptId = attemptId,
            actionResolver = {
                SystemInputInjectorHook.resolveActionAutoInput()
            },
            tokenProvider = tokenProvider,
        )
    }

    @JvmStatic
    fun sendToast(
        context: Context,
        text: String?,
        duration: Int = Toast.LENGTH_LONG,
    ) {
        if (text.isNullOrEmpty()) return
        mainHandler.post {
            Toast.makeText(context, text, duration).show()
        }
        XLog.i(
            message = "Show toast locally with textLength: %d, duration: %d",
            text.length,
            duration,
        )
    }
}
