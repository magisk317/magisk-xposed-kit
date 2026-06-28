@file:Suppress("NOTHING_TO_INLINE")

package io.github.magisk317.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Method

// -- Method/Constructor hook DSL --

fun Method.hook(callback: HookCallback): HookHandle =
    XposedRuntime.hook(this, MethodHook(callback))

fun Constructor<*>.hook(callback: HookCallback): HookHandle =
    XposedRuntime.hook(this, MethodHook(callback))

// -- Class-level hook DSL --

fun Class<*>.hookMethod(methodName: String, vararg parameterTypes: Class<*>, callback: HookCallback): HookHandle =
    HookHelpers.findMethodExact(this, methodName, *parameterTypes).hook(callback)

fun Class<*>.hookConstructor(vararg parameterTypes: Class<*>, callback: HookCallback): HookHandle =
    HookHelpers.findConstructorExact(this, *parameterTypes).hook(callback)

fun Class<*>.hookAllConstructor(callback: HookCallback): Set<HookHandle> =
    declaredConstructors.map { it.hook(callback) }.toSet()

fun Class<*>.hookAllMethods(methodName: String, callback: HookCallback): Set<HookHandle> =
    collectMethodsForDsl(this, methodName).map { it.hook(callback) }.toSet()

// -- Top-level convenience --

fun hookMethod(
    className: String,
    classLoader: ClassLoader,
    methodName: String,
    vararg parameterTypes: Class<*>,
    callback: HookCallback,
): HookHandle = classLoader.findClass(className).hookMethod(methodName, *parameterTypes, callback = callback)

fun hookConstructor(
    className: String,
    classLoader: ClassLoader,
    vararg parameterTypes: Class<*>,
    callback: HookCallback,
): HookHandle = classLoader.findClass(className).hookConstructor(*parameterTypes, callback = callback)

// -- Reflection helpers (extension functions) --

fun ClassLoader.findClass(className: String): Class<*> =
    HookHelpers.findClass(className, this)

fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
    HookHelpers.findClass(className, classLoader)

fun findMethodExact(clazz: Class<*>?, methodName: String, vararg parameterTypes: Any?): Method =
    HookHelpers.findMethodExact(clazz, methodName, *parameterTypes)

fun findConstructorExact(clazz: Class<*>?, vararg parameterTypes: Any?): Constructor<*> =
    HookHelpers.findConstructorExact(clazz, *parameterTypes)

fun findConstructorExact(className: String, classLoader: ClassLoader?, vararg parameterTypes: Any?): Constructor<*> =
    HookHelpers.findConstructorExact(className, classLoader, *parameterTypes)

fun Any.callMethod(methodName: String, vararg args: Any?): Any? =
    HookHelpers.callMethod(this, methodName, *args)

fun Class<*>.callStaticMethod(methodName: String, vararg args: Any?): Any? =
    HookHelpers.callStaticMethod(this, methodName, *args)

fun Class<*>.newInstance(vararg args: Any): Any =
    HookHelpers.newInstance(this, *args)

fun findHookClass(className: String, classLoader: ClassLoader?): Class<*> = try {
    HookHelpers.findClass(className, classLoader)
} catch (e: ClassNotFoundException) {
    throw HookClassNotFoundError(e)
} catch (e: NoClassDefFoundError) {
    throw HookClassNotFoundError(e)
}

fun findHookMethodExact(clazz: Class<*>?, methodName: String, vararg parameterTypes: Any?): Method =
    HookHelpers.findMethodExact(clazz, methodName, *parameterTypes)

fun findHookConstructorExact(className: String, classLoader: ClassLoader?, vararg parameterTypes: Any?): Constructor<*> =
    HookHelpers.findConstructorExact(className, classLoader, *parameterTypes)

fun getHookObjectField(obj: Any?, fieldName: String): Any? =
    HookHelpers.getObjectField(obj, fieldName)

fun getHookIntField(obj: Any?, fieldName: String): Int =
    HookHelpers.getIntField(obj, fieldName)

fun setHookObjectField(obj: Any?, fieldName: String, value: Any?) =
    HookHelpers.setObjectField(obj, fieldName, value)

fun setHookIntField(obj: Any?, fieldName: String, value: Int) =
    HookHelpers.setIntField(obj, fieldName, value)

fun setHookStaticBooleanField(clazz: Class<*>?, fieldName: String, value: Boolean) =
    HookHelpers.setStaticBooleanField(clazz, fieldName, value)

fun invokeOriginalMethod(method: java.lang.reflect.Member, thisObject: Any?, args: Array<Any?>): Any? =
    XposedRuntime.invokeOriginal(method, thisObject, args)

// -- Field operator extensions --

inline fun <reified T> Any.getOrNull(name: String): T? = HookHelpers.getField(this, name, T::class.java)

inline operator fun <reified T> Any.get(name: String): T = HookHelpers.getField(this, name, T::class.java)!!

inline operator fun <reified T> Any.set(name: String, value: T?) = HookHelpers.setField(this, name, value, T::class.java)

// -- Internal --

private fun collectMethodsForDsl(clazz: Class<*>, methodName: String): List<Method> {
    val methods = mutableListOf<Method>()
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredMethods
            .filter { it.name == methodName }
            .forEach {
                it.isAccessible = true
                methods += it
            }
        current = current.superclass
    }
    return methods
}

// -- Application access --

fun currentApplication(): android.app.Application? = runCatching {
    val activityThreadClass = Class.forName("android.app.ActivityThread")
    val activityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)
    activityThreadClass.getDeclaredMethod("currentApplication").invoke(activityThread) as? android.app.Application
}.getOrNull()
