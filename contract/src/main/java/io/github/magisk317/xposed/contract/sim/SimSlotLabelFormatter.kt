package io.github.magisk317.xposed.contract.sim

object SimSlotLabelFormatter {
    fun format(
        simSlot: Int,
        remarkResolver: (Int) -> String = { "" },
    ): String {
        if (simSlot < 0) return ""
        val remark = remarkResolver(simSlot).trim()
        return remark.ifBlank { "SIM${simSlot + 1}" }
    }
}
