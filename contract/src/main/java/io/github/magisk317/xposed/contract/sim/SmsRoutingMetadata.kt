package io.github.magisk317.xposed.contract.sim

data class SmsRoutingMetadata(
    val simSlot: Int? = null,
    val subId: Int? = null,
) {
    fun hasValue(): Boolean = simSlot != null || subId != null

    fun normalized(slotIndexResolver: (Int) -> Int = { -1 }): SmsRoutingMetadata {
        val normalizedSubId = subId?.takeIf { it > 0 }
        val slotFromSubId = normalizedSubId
            ?.let(slotIndexResolver)
            ?.takeIf { it >= 0 }
        val normalizedSlot = slotFromSubId
            ?: normalizeRawSlot(simSlot)
        return SmsRoutingMetadata(
            simSlot = normalizedSlot,
            subId = normalizedSubId,
        )
    }

    companion object {
        const val UNKNOWN_SIM_SLOT = -1
        const val UNKNOWN_SUB_ID = 0

        fun normalizeRawSlot(rawSlot: Int?): Int? {
            return when (rawSlot) {
                null -> null
                in 0..1 -> rawSlot
                2 -> 1
                else -> null
            }
        }
    }
}
