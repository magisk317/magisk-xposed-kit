package io.github.magisk317.xposed.contract.util

import kotlin.math.abs

object CodeRecordSimilarityUtils {
    const val DEFAULT_WINDOW_MS = 20_000L
    val DEFAULT_SYSTEM_SMS_PACKAGES: Set<String> = setOf(
        "com.android.mms",
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "com.textra",
    )

    data class Projection(
        val code: String?,
        val body: String?,
        val company: String?,
        val sender: String?,
        val packageName: String?,
        val date: Long,
    )

    fun shouldMergeByCodeWithinWindow(
        firstCode: String?,
        firstBody: String?,
        firstCompany: String?,
        firstSender: String?,
        firstPackageName: String? = null,
        firstDate: Long,
        secondCode: String?,
        secondBody: String?,
        secondCompany: String?,
        secondSender: String?,
        secondPackageName: String? = null,
        secondDate: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        systemPackages: Set<String> = DEFAULT_SYSTEM_SMS_PACKAGES,
    ): Boolean {
        if (abs(firstDate - secondDate) > windowMs) return false
        if (crossSourceMatchScore(
            existingCode = firstCode,
            existingBody = firstBody,
            existingCompany = firstCompany,
            existingSender = firstSender,
            incomingCode = secondCode,
            incomingBody = secondBody,
            incomingCompany = secondCompany,
            incomingSender = secondSender,
        ) > 0) {
            return true
        }
        return shouldMergeSystemSummaryCopy(
            firstCode = firstCode,
            firstBody = firstBody,
            firstPackageName = firstPackageName,
            secondCode = secondCode,
            secondBody = secondBody,
            secondPackageName = secondPackageName,
            systemPackages = systemPackages,
        )
    }

    fun crossSourceMatchScore(
        existingCode: String?,
        existingBody: String?,
        existingCompany: String?,
        existingSender: String?,
        incomingCode: String?,
        incomingBody: String?,
        incomingCompany: String?,
        incomingSender: String?,
    ): Int {
        val leftCode = existingCode.orEmpty().trim()
        val rightCode = incomingCode.orEmpty().trim()
        if (leftCode.isBlank() || leftCode != rightCode) return 0

        var score = 0
        val leftBody = normalizeBody(existingBody)
        val rightBody = normalizeBody(incomingBody)
        if (leftBody.isNotBlank() && rightBody.isNotBlank() && leftBody == rightBody) {
            score += 3
        }

        val leftCompany = normalizeLabel(existingCompany)
        val rightCompany = normalizeLabel(incomingCompany)
        if (leftCompany.isNotBlank() && rightCompany.isNotBlank() && leftCompany == rightCompany) {
            score += 2
        }

        val leftSender = normalizeLabel(existingSender)
        val rightSender = normalizeLabel(incomingSender)
        if (leftSender.isNotBlank() && rightSender.isNotBlank() && leftSender == rightSender) {
            score += 1
        }
        return score
    }

    fun scoreRecordPreference(
        packageName: String?,
        company: String?,
        sender: String?,
        systemPackages: Set<String> = DEFAULT_SYSTEM_SMS_PACKAGES,
    ): Int {
        var score = 0
        val pkg = packageName.orEmpty()
        if (pkg.isNotBlank() && pkg !in systemPackages) score += 4
        if (company.orEmpty().isNotBlank()) score += 2
        if (sender.orEmpty().isNotBlank()) score += 1
        return score
    }

    fun <T> deduplicateRecords(
        records: List<T>,
        projection: (T) -> Projection,
        systemPackages: Set<String> = emptySet(),
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): List<T> {
        if (records.size < 2) return records
        val sorted = records.sortedByDescending { projection(it).date }
        val kept = mutableListOf<T>()
        sorted.forEach { candidate ->
            val duplicateIndex = kept.indexOfFirst { existing ->
                val existingProjection = projection(existing)
                val candidateProjection = projection(candidate)
                shouldMergeByCodeWithinWindow(
                    firstCode = existingProjection.code,
                    firstBody = existingProjection.body,
                    firstCompany = existingProjection.company,
                    firstSender = existingProjection.sender,
                    firstDate = existingProjection.date,
                    secondCode = candidateProjection.code,
                    secondBody = candidateProjection.body,
                    secondCompany = candidateProjection.company,
                    secondSender = candidateProjection.sender,
                    secondDate = candidateProjection.date,
                    windowMs = windowMs,
                )
            }
            if (duplicateIndex < 0) {
                kept += candidate
            } else {
                val existingProjection = projection(kept[duplicateIndex])
                val candidateProjection = projection(candidate)
                val existingScore = scoreRecordPreference(
                    packageName = existingProjection.packageName,
                    company = existingProjection.company,
                    sender = existingProjection.sender,
                    systemPackages = systemPackages,
                )
                val candidateScore = scoreRecordPreference(
                    packageName = candidateProjection.packageName,
                    company = candidateProjection.company,
                    sender = candidateProjection.sender,
                    systemPackages = systemPackages,
                )
                kept[duplicateIndex] = when {
                    candidateScore > existingScore -> candidate
                    candidateScore < existingScore -> kept[duplicateIndex]
                    candidateProjection.date > existingProjection.date -> candidate
                    else -> kept[duplicateIndex]
                }
            }
        }
        return kept.sortedByDescending { projection(it).date }
    }

    fun normalizeBody(body: String?): String {
        return body.orEmpty()
            .replace(Regex("^\\s*\\[\\d+条]\\s*"), "")
            .replace(Regex("^\\s*[【\\[].*?[】\\]]\\s*"), "")
            .replace(Regex("^\\s*[.…·•:：;；,，!！?？、\\-—~～]+"), "")
            .replace("...", "")
            .replace("…", "")
            .replace(Regex("\\s+"), "")
            .trim()
    }

    fun normalizeLabel(value: String?): String {
        return value.orEmpty()
            .trim()
            .trim('【', '】', '[', ']')
            .replace(Regex("\\s+"), "")
    }

    fun isSystemSmsPackage(
        packageName: String?,
        systemPackages: Set<String> = DEFAULT_SYSTEM_SMS_PACKAGES,
    ): Boolean {
        val pkg = packageName.orEmpty().trim()
        return pkg.isNotEmpty() && pkg in systemPackages
    }

    fun isSystemSmsSummaryBody(body: String?): Boolean {
        val raw = body.orEmpty().trim()
        return raw.startsWith("[") && raw.contains("条]") ||
            raw.startsWith("...") ||
            raw.startsWith("…")
    }

    fun shouldMergeSystemSummaryCopy(
        firstCode: String?,
        firstBody: String?,
        firstPackageName: String?,
        secondCode: String?,
        secondBody: String?,
        secondPackageName: String?,
        systemPackages: Set<String> = DEFAULT_SYSTEM_SMS_PACKAGES,
    ): Boolean {
        val leftCode = firstCode.orEmpty().trim()
        val rightCode = secondCode.orEmpty().trim()
        if (leftCode.isBlank() || leftCode != rightCode) return false

        val firstSystem = isSystemSmsPackage(firstPackageName, systemPackages)
        val secondSystem = isSystemSmsPackage(secondPackageName, systemPackages)
        if (firstSystem == secondSystem) return false

        val summaryBody = if (firstSystem) firstBody else secondBody
        return isSystemSmsSummaryBody(summaryBody)
    }
}
