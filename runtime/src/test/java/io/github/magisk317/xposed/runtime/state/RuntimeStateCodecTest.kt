package io.github.magisk317.xposed.runtime.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeStateCodecTest {
    @Test
    fun `hook process round trips encoded identity and source`() {
        val original = HookProcessStatus("com.android.server.telecom", "system_server", 42L, "process_online", 55)
        val key = "hook_process.${RuntimeStateCodec.encode(original.packageName)}.${RuntimeStateCodec.encode(original.processName)}"
        assertEquals(original, RuntimeStateCodec.decodeProcess(key, RuntimeStateCodec.encodeProcess(original)))
    }

    @Test
    fun `notification event round trips separators and unicode`() {
        val original = NotifyEventSnapshot("nms", "com.android.server.telecom", "TelecomIncomingCalls", "call_notify", "skip|原因", 99L)
        assertEquals(original, RuntimeStateCodec.decodeNotifyEvent(RuntimeStateCodec.encodeNotifyEvent(original)))
    }
}
