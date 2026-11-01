package io.github.magisk317.xposed.runtime.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeStateCodecTest {
    @Test
    fun `hook process round trips encoded identity and source`() {
        val original = HookProcessStatus("com.android.server.telecom", "system_server", 42L, "process_online", 55)
        val key = RuntimeStateCodec.hookProcessKey(original.packageName, original.processName)
        assertEquals(original, RuntimeStateCodec.decodeHookProcess(key, RuntimeStateCodec.encodeHookProcess(original)))
    }

    @Test
    fun `notification event round trips separators and unicode`() {
        val original = NotifyEventSnapshot("nms", "com.android.server.telecom", "TelecomIncomingCalls", "call_notify", "skip|原因", 99L)
        assertEquals(original, RuntimeStateCodec.decodeNotifyEvent(RuntimeStateCodec.encodeNotifyEvent(original)))
    }

    @Test
    fun `framework hook targets round trip identity and state`() {
        val original = listOf(
            FrameworkHookTargetStatus(
                packageName = "com.android.systemui",
                processName = "com.android.systemui",
                pid = 4242,
                uid = 1000,
                state = "UP_TO_DATE",
                lastSeenAtMs = 77L,
            ),
            FrameworkHookTargetStatus(
                packageName = "android",
                processName = "system_server",
                pid = 900,
                uid = 1000,
                state = "STALE",
                lastSeenAtMs = 78L,
            ),
        )
        val encoded = RuntimeStateCodec.encodeFrameworkHookTargets(original)

        assertEquals(original, RuntimeStateCodec.decodeFrameworkHookTargets(encoded))
    }

    @Test
    fun `framework hook targets survive a broken entry`() {
        val encoded = RuntimeStateCodec.encodeFrameworkHookTargets(
            listOf(FrameworkHookTargetStatus(processName = "system_server", pid = 1, uid = 2)),
        )

        assertEquals(emptyList<FrameworkHookTargetStatus>(), RuntimeStateCodec.decodeFrameworkHookTargets("broken"))
        assertEquals(1, RuntimeStateCodec.decodeFrameworkHookTargets("$encoded;broken").size)
    }
}
