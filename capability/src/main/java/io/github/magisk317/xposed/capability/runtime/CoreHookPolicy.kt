package io.github.magisk317.xposed.capability.runtime

import android.content.Context

interface CoreHookPolicy {
    fun shouldSuppressSystemHooks(context: Context?, source: String): Boolean
}

object CoreHookPolicyHolder {
    @Volatile
    private var policy: CoreHookPolicy? = null

    fun install(policy: CoreHookPolicy?) {
        this.policy = policy
    }

    fun shouldSuppressSystemHooks(context: Context?, source: String): Boolean {
        return policy?.shouldSuppressSystemHooks(context, source) ?: false
    }
}
