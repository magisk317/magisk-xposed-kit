package io.github.magisk317.xposed.contract.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeRecordSimilarityUtilsTest {
    @Test
    fun crossSourceMatchScore_prioritizesCodeThenBodyCompanySender() {
        val score = CodeRecordSimilarityUtils.crossSourceMatchScore(
            existingCode = "888686",
            existingBody = "【验证码】888686，尊敬的用户，您正在登录中国移动 APP",
            existingCompany = "10086910",
            existingSender = "10086910",
            incomingCode = "888686",
            incomingBody = "【验证码】888686，尊敬的用户，您正在登录中国移动 APP",
            incomingCompany = "中国移动",
            incomingSender = "中国移动",
        )

        assertEquals(3, score)
    }

    @Test
    fun shouldMergeByCodeWithinWindow_matchesAcrossCarrierIdentityDrift() {
        assertTrue(
            CodeRecordSimilarityUtils.shouldMergeByCodeWithinWindow(
                firstCode = "888686",
                firstBody = "【验证码】888686，尊敬的用户，您正在登录中国移动 APP",
                firstCompany = "10086910",
                firstSender = "10086910",
                firstPackageName = "com.greenpoint.android.mc10086.activity",
                firstDate = 27_000L,
                secondCode = "888686",
                secondBody = "【验证码】888686，尊敬的用户，您正在登录中国移动 APP",
                secondCompany = "中国移动",
                secondSender = "中国移动",
                secondPackageName = "com.android.mms",
                secondDate = 34_000L,
            ),
        )
    }

    @Test
    fun shouldMergeByCodeWithinWindow_respectsWindow() {
        assertFalse(
            CodeRecordSimilarityUtils.shouldMergeByCodeWithinWindow(
                firstCode = "888686",
                firstBody = "same",
                firstCompany = "A",
                firstSender = "A",
                firstPackageName = "pkg.a",
                firstDate = 1_000L,
                secondCode = "888686",
                secondBody = "same",
                secondCompany = "A",
                secondSender = "A",
                secondPackageName = "pkg.b",
                secondDate = 25_001L,
            ),
        )
    }

    @Test
    fun shouldMergeByCodeWithinWindow_matchesSystemSummaryCopy() {
        assertTrue(
            CodeRecordSimilarityUtils.shouldMergeByCodeWithinWindow(
                firstCode = "088864",
                firstBody = "[瑞幸咖啡] 验证码：088864，2分钟后即失效，赶紧和luckin打个招呼吧！",
                firstCompany = "瑞幸咖啡",
                firstSender = "106854970926",
                firstPackageName = "com.lucky.luckyclient",
                firstDate = 1_000L,
                secondCode = "088864",
                secondBody = "[2条]...验证码：088864，2分钟后即失效，赶紧和luckin打个招呼吧！",
                secondCompany = "瑞幸咖啡",
                secondSender = "瑞幸咖啡",
                secondPackageName = "com.android.mms",
                secondDate = 7_000L,
            ),
        )
    }

    @Test
    fun normalizeBody_stripsSystemSummaryPrefixAndEllipsis() {
        assertEquals(
            "验证码：088864，2分钟后即失效，赶紧和luckin打个招呼吧！",
            CodeRecordSimilarityUtils.normalizeBody("[2条]...验证码：088864，2分钟后即失效，赶紧和luckin打个招呼吧！"),
        )
    }

    @Test
    fun deduplicateRecords_prefersRicherPackageSource() {
        data class FakeRecord(
            val code: String,
            val body: String,
            val company: String,
            val sender: String,
            val packageName: String,
            val date: Long,
        )

        val records = listOf(
            FakeRecord(
                code = "888686",
                body = "【验证码】888686，尊敬的用户，您正在登录中国移动 APP",
                company = "中国移动",
                sender = "中国移动",
                packageName = "com.android.mms",
                date = 34_000L,
            ),
            FakeRecord(
                code = "888686",
                body = "【验证码】888686，尊敬的用户，您正在登录中国移动 APP",
                company = "10086910",
                sender = "10086910",
                packageName = "com.greenpoint.android.mc10086.activity",
                date = 27_000L,
            ),
        )

        val deduped = CodeRecordSimilarityUtils.deduplicateRecords(
            records = records,
            projection = {
                CodeRecordSimilarityUtils.Projection(
                    code = it.code,
                    body = it.body,
                    company = it.company,
                    sender = it.sender,
                    packageName = it.packageName,
                    date = it.date,
                )
            },
            systemPackages = setOf("com.android.mms"),
        )

        assertEquals(1, deduped.size)
        assertEquals("com.greenpoint.android.mc10086.activity", deduped.single().packageName)
    }
}
