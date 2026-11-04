package io.github.magisk317.xposed.permission

import android.content.Context

/**
 * Version-scoped wizard progress + upgrade-prompt marker shared by the app family.
 * The prompt/progress only survive within one app version; an upgrade restarts setup.
 */
enum class SetupProgress(val completed: Boolean, val rootSelected: Boolean) {
    ROOT_PENDING(false, false),
    GRANT_ROOT(false, true),
    GRANT_MANUAL(false, false),
    COMPLETE_ROOT(true, true),
    COMPLETE_MANUAL(true, false),
}

object SetupProgressPolicy {
    fun shouldShowWizard(privacyAccepted: Boolean, progress: SetupProgress?): Boolean =
        !privacyAccepted || progress?.completed != true
}

object SetupProgressStore {
    fun current(context: Context): SetupProgress? {
        val preferences = context.getSharedPreferences(STARTUP_PERMISSION_PREFS, Context.MODE_PRIVATE)
        if (preferences.getLong(SETUP_VERSION_CODE, Long.MIN_VALUE) != context.currentVersionCode()) {
            return null
        }
        return preferences.getString(SETUP_PROGRESS, null)
            ?.let { value -> runCatching { SetupProgress.valueOf(value) }.getOrNull() }
    }

    fun isComplete(context: Context): Boolean = current(context)?.completed == true

    fun mark(context: Context, progress: SetupProgress) {
        context.getSharedPreferences(STARTUP_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(SETUP_VERSION_CODE, context.currentVersionCode())
            .putString(SETUP_PROGRESS, progress.name)
            .apply()
    }

    fun markComplete(context: Context) {
        val completed = if (current(context)?.rootSelected == true) {
            SetupProgress.COMPLETE_ROOT
        } else {
            SetupProgress.COMPLETE_MANUAL
        }
        mark(context, completed)
    }

    private const val SETUP_PROGRESS = "setup_progress"
    private const val SETUP_VERSION_CODE = "setup_progress_version_code"
}

object StartupPromptStore {
    fun hasPrompted(context: Context): Boolean {
        val promptedVersion = context.getSharedPreferences(STARTUP_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PROMPTED_VERSION_CODE, Long.MIN_VALUE)
        return promptedVersion == context.currentVersionCode()
    }

    fun markPromptedForCurrentVersion(context: Context) {
        context.getSharedPreferences(STARTUP_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PROMPTED_VERSION_CODE, context.currentVersionCode())
            .apply()
    }

    private const val KEY_PROMPTED_VERSION_CODE = "prompted_version_code"
}

const val STARTUP_PERMISSION_PREFS = "startup_permission_prompt"
