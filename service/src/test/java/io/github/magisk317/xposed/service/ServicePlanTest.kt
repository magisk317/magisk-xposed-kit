package io.github.magisk317.xposed.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServicePlanTest {

    @Test
    fun `missing targets keep caller order and skip granted names`() {
        val granted = listOf("com.android.systemui", "com.miui.securitycore")
        val wanted = listOf(
            "com.miui.personalassistant",
            "com.android.systemui",
            "com.xiaomi.xmsf",
        )
        assertEquals(
            listOf("com.miui.personalassistant", "com.xiaomi.xmsf"),
            ServicePlan.missingTargets(granted, wanted),
        )
    }

    @Test
    fun `blank and repeated scope names are never requested`() {
        assertEquals(
            listOf("com.a"),
            ServicePlan.missingTargets(listOf(" "), listOf(" com.a ", "", "com.a", "   ")),
        )
    }

    @Test
    fun `entry estimate grows with the payload and is stable`() {
        val bool = ServicePlan.entryBytes("k", true)
        val text = ServicePlan.entryBytes("k", "abc")
        val big = ServicePlan.entryBytes("k", "x".repeat(4096))
        assertTrue(text > bool)
        assertTrue(big > text * 100)
        assertEquals(big, ServicePlan.entryBytes("k", "x".repeat(4096)))
    }

    @Test
    fun `string sets are mirrorable, mixed sets and raw bytes are not`() {
        assertTrue(ServicePlan.isMirrorable(emptySet<String>()))
        assertTrue(ServicePlan.isMirrorable(setOf("a", "b")))
        assertFalse(ServicePlan.isMirrorable(setOf(1, 2)))
        assertFalse(ServicePlan.isMirrorable(byteArrayOf(1, 2)))
        assertFalse(ServicePlan.isMirrorable(null))
    }

    @Test
    fun `partition excludes by policy and reports every key it did not move`() {
        val entries = mapOf(
            "core_on" to true,
            "token" to "y".repeat(2048),
            "secret" to "local only",
            "blob" to byteArrayOf(1, 2),
        )
        val plan = ServicePlan.partition(
            entries = entries,
            keyGroup = { key -> if (key == "secret") null else "core" },
            maxEntryBytes = 4096,
            maxGroupBytes = Long.MAX_VALUE,
        )
        assertEquals(listOf("core_on"), plan.groups.getValue("core").keys.toList())
        assertEquals(listOf("secret"), plan.ungrouped)
        assertEquals(listOf("blob"), plan.unsupported)
        assertEquals(listOf("token"), plan.oversized)
        assertTrue(plan.oversizedGroups.isEmpty())
    }

    @Test
    fun `a group over budget is withheld whole instead of half written`() {
        val entries = (1..8).associate { index -> "key$index" to "z".repeat(500) }
        val plan = ServicePlan.partition(
            entries = entries,
            keyGroup = { "shard" },
            maxEntryBytes = 4096,
            maxGroupBytes = 6000,
        )
        assertTrue(plan.groups.isEmpty())
        assertEquals(listOf("shard"), plan.oversizedGroups)
    }

    @Test
    fun `keys are visited in sorted order so a report is reproducible`() {
        val entries = mapOf("b" to 1, "a" to 2, "c" to 3)
        val plan = ServicePlan.partition(
            entries = entries,
            keyGroup = { "g" },
            maxEntryBytes = 4096,
            maxGroupBytes = Long.MAX_VALUE,
        )
        assertEquals(listOf("a", "b", "c"), plan.groups.getValue("g").keys.toList())
    }

    @Test
    fun `several groups are budgeted independently`() {
        val entries = mapOf(
            "small" to 1,
            "large_a" to "L".repeat(1000),
            "large_b" to "M".repeat(1000),
        )
        val plan = ServicePlan.partition(
            entries = entries,
            keyGroup = { if (it == "small") "core" else "bulk" },
            maxEntryBytes = 4096,
            maxGroupBytes = 3000,
        )
        assertEquals(listOf("small"), plan.groups.getValue("core").keys.toList())
        assertTrue("bulk" !in plan.groups)
        assertEquals(listOf("bulk"), plan.oversizedGroups)
    }
}
