package io.github.magisk317.xposed.capability.helper

import android.content.Context
import io.github.magisk317.xposed.HookHelpers

/**
 * Reflection-based Context recovery for telephony hook objects.
 *
 * The constructor hook may never fire (e.g. after a hot module update without a
 * phone-process restart); the dispatch hook then recovers a phone Context from the
 * hooked `InboundSmsHandler`/receiver instances. This is the union of both hosts'
 * probe paths: direct Context, a class-hierarchy walk for Context fields,
 * named fields (`mContext`/`context`/`mPhoneContext`), `getContext()` methods,
 * and the `mPhone`/`phone` holder chain.
 */
object SmsHookContextRecovery {

    data class RecoveredContext(val context: Context, val source: String)

    private val CONTEXT_FIELD_NAMES = listOf("mContext", "context", "mPhoneContext")
    private val PHONE_FIELD_NAMES = listOf("mPhone", "phone")

    /** Probes [source] for a Context, labelling the hit as `[sourceLabel].[path]`. */
    fun resolve(source: Any?, sourceLabel: String): RecoveredContext? {
        if (source == null) return null
        if (source is Context) return RecoveredContext(source, sourceLabel)
        hierarchyContextField(source, sourceLabel)?.let { return it }
        for (name in CONTEXT_FIELD_NAMES) {
            readContextField(source, name)?.let { return RecoveredContext(it, "$sourceLabel.$name") }
        }
        readContextMethod(source, "getContext")?.let { return RecoveredContext(it, "$sourceLabel.getContext") }
        val phone = readObjectField(source, PHONE_FIELD_NAMES[0]) ?: readObjectField(source, PHONE_FIELD_NAMES[1])
        readContextMethod(phone, "getContext")?.let { return RecoveredContext(it, "$sourceLabel.phone.getContext") }
        readContextField(phone, "mContext")?.let { return RecoveredContext(it, "$sourceLabel.phone.mContext") }
        return null
    }

    /** Probes several candidates in order and returns the first Context found. */
    fun resolveFirst(vararg candidates: Pair<Any?, String>): RecoveredContext? {
        for ((obj, label) in candidates) {
            resolve(obj, label)?.let { return it }
        }
        return null
    }

    private fun hierarchyContextField(source: Any, sourceLabel: String): RecoveredContext? {
        return runCatching {
            var clazz: Class<*>? = source.javaClass
            while (clazz != null) {
                val field = clazz.declaredFields.firstOrNull { f ->
                    Context::class.java.isAssignableFrom(f.type) && (f.name == "mContext" || f.name == "context")
                }
                if (field != null) {
                    field.isAccessible = true
                    (field.get(source) as? Context)?.let {
                        return@runCatching RecoveredContext(it, "$sourceLabel.${field.name}")
                    }
                }
                clazz = clazz.superclass
            }
            null
        }.getOrNull()
    }

    private fun readContextField(source: Any?, fieldName: String): Context? =
        readObjectField(source, fieldName) as? Context

    private fun readObjectField(source: Any?, fieldName: String): Any? =
        runCatching { HookHelpers.getObjectField(source, fieldName) }.getOrNull()

    private fun readContextMethod(source: Any?, methodName: String): Context? =
        runCatching { HookHelpers.callMethod(source, methodName) as? Context }.getOrNull()
}
