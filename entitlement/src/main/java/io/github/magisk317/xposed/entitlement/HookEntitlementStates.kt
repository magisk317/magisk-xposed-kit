package io.github.magisk317.xposed.entitlement

import android.content.Context
import com.magisk317.mobile.entitlement.MobileEntitlementPublishedState

/**
 * Assembles the published entitlement state from a host preference reader.
 *
 * Hosts whose hook-side prefs go through a source chain (remote provider + snapshot)
 * supply the chain-backed readers; the state shape stays identical to what
 * [MobileHookGateCheck] expects.
 */
object HookEntitlementStates {
    fun fromChain(
        context: Context,
        readBoolean: (Context, String, Boolean) -> Boolean,
        readString: (Context, String, String) -> String,
        allowedKey: String,
        allowedDefault: Boolean,
        tokenKey: String,
    ): MobileEntitlementPublishedState = MobileEntitlementPublishedState(
        automationAllowed = readBoolean(context, allowedKey, allowedDefault),
        entitlementToken = readString(context, tokenKey, "").takeIf(String::isNotBlank),
    )
}
