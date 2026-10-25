package io.github.magisk317.xposed.logging

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** Anonymous, per-install identity used only for telemetry aggregation. */
object AnonymousInstallationId {
    const val DEFAULT_PREFERENCES_NAME = "magisk_otel_identity"
    const val PREFERENCE_KEY = "anonymous_installation_id"

    @JvmStatic
    fun canonicalUuid(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
    }
}

/** The activity windows a device has been seen in, for anonymous retention aggregation. */
interface InstallationActivityTracker {
    fun claimWindows(nowMillis: Long): List<ActiveWindow>
}

data class InstallationTelemetryState(
    val installationId: String,
    val tracker: InstallationActivityTracker,
)

internal class ActiveWindowClaims(
    private val readBucket: (String) -> String?,
    private val writeBuckets: (List<ActiveWindow>) -> Unit,
) {
    @Synchronized
    fun claim(nowMillis: Long): List<ActiveWindow> {
        val windows = activeWindows(nowMillis)
        val claimed = windows.filter { readBucket(it.preferenceKey) != it.bucket }
        if (claimed.isNotEmpty()) {
            writeBuckets(claimed)
        }
        return claimed
    }
}

/** One ISO activity window (daily / weekly / monthly) claimed by an installation. */
data class ActiveWindow(
    val window: String,
    val bucket: String,
) {
    val preferenceKey: String = "active_bucket_$window"
}

internal fun activeWindows(nowMillis: Long): List<ActiveWindow> {
    val date = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val weekFields = WeekFields.ISO
    val weekYear = date.get(weekFields.weekBasedYear())
    val week = date.get(weekFields.weekOfWeekBasedYear())
    return listOf(
        ActiveWindow("daily", date.toString()),
        ActiveWindow("weekly", String.format(Locale.ROOT, "%04d-W%02d", weekYear, week)),
        ActiveWindow("monthly", String.format(Locale.ROOT, "%04d-%02d", date.year, date.monthValue)),
    )
}
