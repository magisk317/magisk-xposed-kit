package io.github.magisk317.xposed.runtime.contract.sim

import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import io.github.magisk317.xposed.contract.sim.SmsRoutingMetadata

object SmsRoutingIntentExtras {
    const val EXTRA_SIM_SLOT = "sim_slot"
    const val EXTRA_SUB_ID = "sub_id"

    private val simSlotKeys = arrayOf(
        EXTRA_SIM_SLOT,
        "slot",
        "slot_id",
        "phone_id",
        "phoneId",
        "simSlot",
        "android.telephony.extra.SLOT_INDEX",
    )

    private val subIdKeys = arrayOf(
        EXTRA_SUB_ID,
        "sim_id",
        "simId",
        "subscription",
        "subscription_id",
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        "android.telephony.extra.SUBSCRIPTION_ID",
    )

    fun readFrom(intent: Intent): SmsRoutingMetadata {
        return fromRawValues(
            simSlot = readIntExtra(intent, *simSlotKeys),
            subId = readIntExtra(intent, *subIdKeys),
            slotIndexResolver = ::resolveSlotIndex,
        )
    }

    fun fromRawValues(
        simSlot: Int?,
        subId: Int?,
        slotIndexResolver: (Int) -> Int = { -1 },
    ): SmsRoutingMetadata {
        return SmsRoutingMetadata(
            simSlot = simSlot?.takeIf { it >= 0 },
            subId = subId?.takeIf { it > 0 },
        ).normalized(slotIndexResolver)
    }

    fun copyFrom(source: Intent, target: Intent): SmsRoutingMetadata {
        val metadata = readFrom(source)
        writeTo(target, metadata)
        return metadata
    }

    fun writeTo(intent: Intent, metadata: SmsRoutingMetadata) {
        metadata.simSlot?.let { intent.putExtra(EXTRA_SIM_SLOT, it) }
        metadata.subId?.let { intent.putExtra(EXTRA_SUB_ID, it) }
    }

    fun readIntExtra(intent: Intent, vararg keys: String): Int? {
        for (key in keys) {
            if (!intent.hasExtra(key)) continue
            val intValue = intent.getIntExtra(key, Int.MIN_VALUE)
            if (intValue != Int.MIN_VALUE) return intValue
            val longValue = intent.getLongExtra(key, Long.MIN_VALUE)
            if (longValue != Long.MIN_VALUE) return longValue.toInt()
            intent.getStringExtra(key)?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun resolveSlotIndex(subId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
    }
}
