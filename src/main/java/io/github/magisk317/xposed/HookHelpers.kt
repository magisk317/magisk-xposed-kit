package io.github.magisk317.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Error thrown when a class cannot be found during hook resolution. */
class HookClassNotFoundError(cause: Throwable) : Error(cause)

/** Error wrapping [InvocationTargetException] from reflective hook calls. */
class HookInvocationTargetError(val targetException: Throwable) : Error(targetException)

object HookHelpers {

    private val primitiveToWrapper = mapOf(
        java.lang.Boolean.TYPE to Boolean::class.javaObjectType,
        java.lang.Byte.TYPE to Byte::class.javaObjectType,
        java.lang.Character.TYPE to Char::class.javaObjectType,
        java.lang.Short.TYPE to Short::class.javaObjectType,
        java.lang.Integer.TYPE to Int::class.javaObjectType,
        java.lang.Long.TYPE to Long::class.javaObjectType,
        java.lang.Float.TYPE to Float::class.javaObjectType,
        java.lang.Double.TYPE to Double::class.javaObjectType,
        java.lang.Void.TYPE to Void::class.javaObjectType,
    )

    // -- Class resolution --

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return if (classLoader != null) {
            Class.forName(className, false, classLoader)
        } else {
            Class.forName(className)
        }
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return runCatching { findClass(className, classLoader) }.getOrNull()
    }

    // -- Method resolution --

    fun findMethodExact(clazz: Class<*>?, methodName: String, vararg parameterTypes: Any?): Method {
        val target = clazz ?: throw NoSuchMethodException("Class is null for method $methodName")
        val resolved = resolveParameterTypes(target.classLoader, parameterTypes)
        var current: Class<*>? = target
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(methodName, *resolved).apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchMethodException("Method not found: ${target.name}#$methodName")
    }

    fun findMethodExactIfExists(clazz: Class<*>?, methodName: String, vararg parameterTypes: Any?): Method? {
        return runCatching { findMethodExact(clazz, methodName, *parameterTypes) }.getOrNull()
    }

    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg args: Any?): Method {
        val methods = collectMethods(clazz, methodName)
        val argTypes = args.map { it?.javaClass }.toTypedArray<Class<*>?>()
        return methods.maxByOrNull { scoreExecutable(it.parameterTypes, argTypes) }
            ?.takeIf { scoreExecutable(it.parameterTypes, argTypes) >= 0 }
            ?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException("No suitable method for ${clazz.name}#$methodName")
    }

    fun findMethodsByExactParameters(
        clazz: Class<*>?,
        returnType: Class<*>?,
        vararg parameterTypes: Class<*>?,
    ): Array<Method> {
        val target = clazz ?: return emptyArray()
        if (parameterTypes.any { it == null }) return emptyArray()
        val resolved = parameterTypes.filterNotNull().toTypedArray()
        val methods = mutableListOf<Method>()
        var current: Class<*>? = target
        while (current != null) {
            current.declaredMethods
                .filter { method ->
                    if (method.parameterTypes.size != resolved.size) return@filter false
                    if (returnType != null && method.returnType != returnType) return@filter false
                    method.parameterTypes.contentEquals(resolved)
                }
                .forEach { method ->
                    method.isAccessible = true
                    methods += method
                }
            current = current.superclass
        }
        return methods.toTypedArray()
    }

    // -- Constructor resolution --

    fun findConstructorExact(clazz: Class<*>?, vararg parameterTypes: Any?): Constructor<*> {
        val target = clazz ?: throw NoSuchMethodException("Class is null for constructor")
        val resolved = resolveParameterTypes(target.classLoader, parameterTypes)
        return target.getDeclaredConstructor(*resolved).apply { isAccessible = true }
    }

    fun findConstructorExact(className: String, classLoader: ClassLoader?, vararg parameterTypes: Any?): Constructor<*> {
        return findConstructorExact(findClass(className, classLoader), *parameterTypes)
    }

    fun findConstructorBestMatch(clazz: Class<*>, vararg args: Any?): Constructor<*> {
        val argTypes = args.map { it?.javaClass }.toTypedArray<Class<*>?>()
        return clazz.declaredConstructors.maxByOrNull { scoreExecutable(it.parameterTypes, argTypes) }
            ?.takeIf { scoreExecutable(it.parameterTypes, argTypes) >= 0 }
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("No suitable constructor for ${clazz.name}")
    }

    // -- Invocation --

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val target = obj ?: throw NullPointerException("callMethod target is null for $methodName")
        val method = findMethodBestMatch(target.javaClass, methodName, *args)
        return method.invokeAccessible(target, args)
    }

    fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? {
        val target = clazz ?: throw NullPointerException("callStaticMethod class is null for $methodName")
        val method = findMethodBestMatch(target, methodName, *args)
        return method.invokeAccessible(null, args)
    }

    fun newInstance(clazz: Class<*>?, vararg args: Any?): Any {
        val target = clazz ?: throw NullPointerException("newInstance class is null")
        val constructor = findConstructorBestMatch(target, *args)
        return constructor.newInstanceAccessible(args)
    }

    // -- Field access --

    fun findField(clazz: Class<*>, fieldName: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("Field not found: ${clazz.name}#$fieldName")
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        val target = obj ?: throw NullPointerException("getObjectField target is null for $fieldName")
        return findField(target.javaClass, fieldName).get(target)
    }

    fun getIntField(obj: Any?, fieldName: String): Int {
        val target = obj ?: throw NullPointerException("getIntField target is null for $fieldName")
        return findField(target.javaClass, fieldName).getInt(target)
    }

    fun getStaticIntField(clazz: Class<*>?, fieldName: String): Int {
        val target = clazz ?: throw NullPointerException("getStaticIntField class is null for $fieldName")
        return findField(target, fieldName).getInt(null)
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        val target = obj ?: throw NullPointerException("setObjectField target is null for $fieldName")
        findField(target.javaClass, fieldName).set(target, value)
    }

    fun setIntField(obj: Any?, fieldName: String, value: Int) {
        val target = obj ?: throw NullPointerException("setIntField target is null for $fieldName")
        findField(target.javaClass, fieldName).setInt(target, value)
    }

    fun setStaticBooleanField(clazz: Class<*>?, fieldName: String, value: Boolean) {
        val target = clazz ?: throw NullPointerException("setStaticBooleanField class is null for $fieldName")
        findField(target, fieldName).setBoolean(null, value)
    }

    fun <T> getField(obj: Any, name: String, fieldClazz: Class<T>): T? {
        val container = if (obj is Class<*>) null else obj
        val thisClass = if (obj is Class<*>) obj else obj.javaClass
        val field = findField(thisClass, name)
        @Suppress("UNCHECKED_CAST")
        return getFieldValue(field, container, fieldClazz) as? T?
    }

    fun <T> setField(obj: Any, name: String, value: T?, fieldClass: Class<T>) {
        val container = if (obj is Class<*>) null else obj
        val thisClass = if (obj is Class<*>) obj else obj.javaClass
        val field = findField(thisClass, name)
        setFieldValue(field, container, value, fieldClass)
    }

    // -- Internal helpers --

    private fun resolveParameterTypes(classLoader: ClassLoader?, parameterTypes: Array<out Any?>): Array<Class<*>> {
        return parameterTypes.map { param ->
            when (param) {
                null -> throw NoSuchMethodException("Parameter type is null")
                is Class<*> -> param
                is String -> findClass(param, classLoader)
                else -> throw NoSuchMethodException("Unsupported parameter type: $param")
            }
        }.toTypedArray()
    }

    private fun collectMethods(clazz: Class<*>, methodName: String): List<Method> {
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

    internal fun scoreExecutable(parameterTypes: Array<Class<*>>, argTypes: Array<Class<*>?>): Int {
        if (parameterTypes.size != argTypes.size) return -1
        var score = 0
        for (index in parameterTypes.indices) {
            val parameterType = wrapPrimitive(parameterTypes[index])
            val argType = argTypes[index]
            if (argType == null) {
                if (parameterTypes[index].isPrimitive) return -1
                continue
            }
            if (!parameterType.isAssignableFrom(argType)) return -1
            if (parameterType == argType) score += 2 else score += 1
        }
        return score
    }

    internal fun wrapPrimitive(clazz: Class<*>): Class<*> {
        return primitiveToWrapper[clazz] ?: clazz
    }

    private fun getFieldValue(field: Field, obj: Any?, fieldClazz: Class<*>): Any? {
        return when (wrapPrimitive(fieldClazz)) {
            Boolean::class.javaObjectType -> field.getBoolean(obj)
            Byte::class.javaObjectType -> field.getByte(obj)
            Char::class.javaObjectType -> field.getChar(obj)
            Double::class.javaObjectType -> field.getDouble(obj)
            Float::class.javaObjectType -> field.getFloat(obj)
            Int::class.javaObjectType -> field.getInt(obj)
            Long::class.javaObjectType -> field.getLong(obj)
            Short::class.javaObjectType -> field.getShort(obj)
            else -> field.get(obj)
        }
    }

    private fun setFieldValue(field: Field, obj: Any?, value: Any?, fieldClass: Class<*>) {
        when (wrapPrimitive(fieldClass)) {
            Boolean::class.javaObjectType -> field.setBoolean(obj, value as Boolean)
            Byte::class.javaObjectType -> field.setByte(obj, value as Byte)
            Char::class.javaObjectType -> field.setChar(obj, value as Char)
            Double::class.javaObjectType -> field.setDouble(obj, value as Double)
            Float::class.javaObjectType -> field.setFloat(obj, value as Float)
            Int::class.javaObjectType -> field.setInt(obj, value as Int)
            Long::class.javaObjectType -> field.setLong(obj, value as Long)
            Short::class.javaObjectType -> field.setShort(obj, value as Short)
            else -> field.set(obj, value)
        }
    }

    internal fun Method.invokeAccessible(thisObject: Any?, args: Array<out Any?>): Any? = try {
        isAccessible = true
        invoke(thisObject, *args)
    } catch (e: InvocationTargetException) {
        throw HookInvocationTargetError(e.targetException ?: e.cause ?: e)
    }

    internal fun Constructor<*>.newInstanceAccessible(args: Array<out Any?>): Any = try {
        isAccessible = true
        newInstance(*args)
    } catch (e: InvocationTargetException) {
        throw HookInvocationTargetError(e.targetException ?: e.cause ?: e)
    }
}
