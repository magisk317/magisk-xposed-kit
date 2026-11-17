package io.github.magisk317.xposed.capability.system
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputFallbackPolicy
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.widget.Toast
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputAccessibilityResultContract
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputBroadcastContract
import io.github.magisk317.xposed.runtime.contract.autoinput.AutoInputResultBroadcastContract
import io.github.magisk317.xposed.runtime.contract.logging.LogRoute
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.xposed.capability.runtime.CoreHookPolicyHolder
import io.github.magisk317.xposed.HookHelpers
import io.github.magisk317.xposed.BaseHook
import io.github.magisk317.xposed.HookEnv
import io.github.magisk317.xposed.LoadParam
import io.github.magisk317.xposed.MethodHook
import io.github.magisk317.xposed.MethodHookParam
import io.github.magisk317.xposed.capability.prefs.CorePrefs
import io.github.magisk317.xposed.capability.utils.resolveStaticIntFieldOrDefault
import io.github.magisk317.xposed.runNonFatalCatching
import io.github.magisk317.xposed.runNonFatalOrNull
import java.lang.reflect.Method
import java.util.Locale
import io.github.magisk317.xposed.logging.MagiskOtel

/**
 * Caller-identity and package-query probes for the auto-input broadcast
 * path: reflective intent/caller extraction, launcher/package lookups and
 * the TTL-cached sending-uid resolver with its once-only diagnostics.
 */
internal fun extractBroadcastIntent(record: Any): Intent? {
        if (record is Intent) return record
        return runNonFatalOrNull { HookHelpers.getObjectField(record, "intent") as? Intent }
            ?: runNonFatalOrNull { HookHelpers.callMethod(record, "getIntent") as? Intent }
    }

internal fun extractCallerInfo(record: Any): Pair<Int, String?> {
        val uidFields = listOf("callerUid", "callingUid", "uid")
        for (field in uidFields) {
            runNonFatalOrNull { HookHelpers.getIntField(record, field) }?.let { return it to null }
        }
        var callerPkg = runNonFatalOrNull { HookHelpers.getObjectField(record, "callerPackage") as? String }
        val callerApp = runNonFatalOrNull { HookHelpers.getObjectField(record, "callerApp") }
        if (callerApp != null) {
            runNonFatalOrNull { HookHelpers.getIntField(callerApp, "uid") }?.let { uid ->
                return uid to callerPkg
            }
            runNonFatalOrNull { HookHelpers.getObjectField(callerApp, "info") }?.let { info ->
                if (callerPkg == null) {
                    callerPkg = runNonFatalOrNull { HookHelpers.getObjectField(info, "packageName") as? String }
                        ?: runNonFatalOrNull { HookHelpers.callMethod(info, "getPackageName") as? String }
                }
                runNonFatalOrNull { HookHelpers.getIntField(info, "uid") }?.let { uid ->
                    return uid to callerPkg
                }
            }
        }
        return -1 to callerPkg
    }
internal fun resolveAccessibilityOrderedResult(
        receiver: BroadcastReceiver,
    ): AutoInputAccessibilityResultContract.OrderedResult? {
        val extras = runNonFatalOrNull { receiver.getResultExtras(false) } ?: return null
        return AutoInputAccessibilityResultContract.readOrderedResult(extras)
    }

internal fun shouldSkipFallbackAfterAccessibility(
        context: Context,
        result: AutoInputAccessibilityResultContract.OrderedResult,
    ): Boolean {
        return AutoInputFallbackPolicy.shouldSkipFallbackAfterAccessibility(
            success = result.success,
            reason = result.reason,
            windowPackage = result.windowPackage,
            modulePackage = io.github.magisk317.xposed.capability.runtime.CoreRuntime.access.applicationId,
            homePackages = resolveHomePackages(context),
        )
    }

internal fun resolveHomePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runNonFatalCatching {
            val infos = queryIntentActivitiesCompat(context.packageManager, intent)
            infos.mapNotNull { it.activityInfo?.packageName?.takeIf(String::isNotBlank) }.toSet()
        }.getOrDefault(emptySet())
    }

internal fun resolvePackagesForUid(context: Context, uid: Int): Set<String> {
        if (uid < 0) return emptySet()
        return runNonFatalOrNull {
            context.packageManager.getPackagesForUid(uid)
                ?.filter(String::isNotBlank)
                ?.toSet()
        }.orEmpty()
    }

    @SuppressLint("QueryPermissionsNeeded")
internal fun queryIntentActivitiesCompat(
        packageManager: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(matchDefaultOnlyFlag().toLong()),
            )
        }
        val result = runNonFatalOrNull {
            val method = packageManager.javaClass.getMethod(
                "queryIntentActivities",
                Intent::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(packageManager, intent, matchDefaultOnlyFlag())
        }
        return (result as? List<*>)?.filterIsInstance<ResolveInfo>().orEmpty()
    }

internal fun matchDefaultOnlyFlag(): Int {
        return resolveStaticIntFieldOrDefault(
            PackageManager::class.java,
            "MATCH_DEFAULT_ONLY",
            MATCH_DEFAULT_ONLY_FALLBACK,
        )
    }


        private const val MATCH_DEFAULT_ONLY_FALLBACK = 0x00010000

internal object SystemInputUidProbe {
        private const val CACHED_UID_TTL_MS = 10_000L
    @Volatile
    private var sendingUidFieldsLogged = false
    private var lastAutoInputCallerUid: Int = -1
    private var lastAutoInputCallerUidAt: Long = 0L
    internal fun recordCallerUid(uid: Int) {
        lastAutoInputCallerUid = uid
        lastAutoInputCallerUidAt = SystemClock.elapsedRealtime()
    }

    internal fun resolveSendingUid(receiver: BroadcastReceiver): Int {
        val cached = lastAutoInputCallerUid
        if (cached >= 0) {
            val ageMs = SystemClock.elapsedRealtime() - lastAutoInputCallerUidAt
            if (ageMs in 0..CACHED_UID_TTL_MS) {
                return cached
            }
        }
        // Try system API if available
        val direct = runNonFatalCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HookHelpers.callMethod(receiver, "getSendingUid") as Int
            } else {
                null
            }
        }.getOrElse { t ->
            XLog.w("Failed to get sendingUid: ${t.message}")
            null
        }
        if (direct != null) return direct

        val pendingResult = runNonFatalOrNull { HookHelpers.callMethod(receiver, "getPendingResult") }
            ?: runNonFatalOrNull { HookHelpers.getObjectField(receiver, "mPendingResult") }
        if (pendingResult != null) {
            val candidates = listOf("mSentFromUid", "mSendingUid", "mSenderUid", "mCallingUid")
            for (field in candidates) {
                runNonFatalOrNull { HookHelpers.getIntField(pendingResult, field) }?.let { return it }
            }
            logPendingResultFieldsOnce(pendingResult)
        } else {
            logPendingResultMissingOnce()
        }
        return -1
    }

    private var pendingResultMissingLogged = false

    internal fun logPendingResultMissingOnce() {
        if (pendingResultMissingLogged) return
        synchronized(this) {
            if (pendingResultMissingLogged) return
            pendingResultMissingLogged = true
        }
        XLog.w("Diag pendingResult is null; unable to resolve sendingUid")
    }

    internal fun logPendingResultFieldsOnce(pendingResult: Any) {
        if (sendingUidFieldsLogged) return
        synchronized(this) {
            if (sendingUidFieldsLogged) return
            sendingUidFieldsLogged = true
        }
        val fields = generateSequence(pendingResult.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { "${it.name}:${it.type.name}" }
            .distinct()
            .sorted()
            .joinToString(limit = 80, truncated = "...")
        XLog.w(message = "Diag pendingResult fields: %s", fields)
    }

}
