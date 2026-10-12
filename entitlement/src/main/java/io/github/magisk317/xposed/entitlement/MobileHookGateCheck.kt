package io.github.magisk317.xposed.entitlement

import com.magisk317.mobile.entitlement.MobileEntitlementGate
import com.magisk317.mobile.entitlement.MobileEntitlementPublishedState
import com.magisk317.mobile.entitlement.MobileEntitlementVerificationPolicy

/**
 * Hook-boundary gate check shared by XposedSmsCode and xinyi-relay.
 *
 * Play distributions ship the gate off: the app publishes an always-allowed
 * snapshot and no signed lease is ever issued, so the hook boundary must not
 * demand one. [MobileEntitlementGate] forces enforcement on every
 * cross-process decision, so the only correct short-circuit is before it —
 * the same one the app process takes through ui-kit's MobileGate.
 */
object MobileHookGateCheck {
    fun isAllowedWhenEnabled(
        gateEnabled: Boolean,
        state: MobileEntitlementPublishedState?,
        signingPublicJwk: String,
        issuer: String,
        appId: String,
    ): Boolean {
        if (!gateEnabled) return true
        val policy = MobileEntitlementVerificationPolicy(
            signingPublicJwk = signingPublicJwk,
            issuer = issuer,
            appId = appId,
            enforced = true,
        )
        return MobileEntitlementGate.isAllowed(state, policy)
    }
}
