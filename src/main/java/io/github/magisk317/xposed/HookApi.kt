package io.github.magisk317.xposed

import java.lang.reflect.Member

interface HookApi {
    fun hookMethod(member: Member, callback: MethodHook): HookHandle?
    fun hookAllConstructors(clazz: Class<*>, callback: MethodHook): Set<HookHandle>
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: MethodHook): Set<HookHandle>
    fun log(priority: Int, tag: String?, msg: String, tr: Throwable? = null)
    fun getApiVersion(): Int?
    fun getFrameworkName(): String?
    fun getFrameworkVersionCode(): Long?
}
