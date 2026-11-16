package com.iosshade.systemui.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Anti-crash wrapper around every reflective/Xposed operation used by this module.
 *
 * Design rules:
 *  - Every helper swallows Throwable and returns a safe fallback (null / false).
 *  - Every failure is logged with enough context to fix the hook signature after a JADX pass.
 *  - No helper ever propagates an exception into One UI's call stack.
 */
object SafeXposed {

    /** Returns null instead of throwing when a class does not exist in this One UI build. */
    fun findClassOrNull(name: String, loader: ClassLoader): Class<*>? = try {
        XposedHelpers.findClass(name, loader)
    } catch (t: Throwable) {
        null
    }

    /**
     * Tries a list of candidate class names (used because Samsung may move/rename classes
     * between One UI minor versions). Returns the first hit, or null with a clear warning.
     */
    fun findClassAny(names: List<String>, loader: ClassLoader): Class<*>? {
        for (name in names) {
            val clazz = findClassOrNull(name, loader)
            if (clazz != null) {
                Log.i("Resolved class: $name")
                return clazz
            }
        }
        Log.w("No candidate class resolved. Candidates: ${names.joinToString()}")
        return null
    }

    /**
     * Hooks ALL declared overloads of [methodName] on [clazz].
     * XposedBridge.hookAllMethods only matches methods declared directly in the class,
     * which is exactly what we want (avoids accidentally hooking inherited framework methods).
     *
     * @return true if at least one method was found and hooked.
     */
    fun hookAll(clazz: Class<*>, methodName: String, hook: XC_MethodHook): Boolean {
        return try {
            val count = clazz.declaredMethods.count { it.name == methodName }
            if (count == 0) {
                Log.w("Method not found: ${clazz.name}#$methodName (class exists, signature differs)")
                return false
            }
            XposedBridge.hookAllMethods(clazz, methodName, hook)
            Log.i("Hooked ${clazz.name}#$methodName (overloads=$count)")
            true
        } catch (t: Throwable) {
            Log.e("Failed to hook ${clazz.name}#$methodName", t)
            false
        }
    }

    /** Safe field read; returns null when the field does not exist or is inaccessible. */
    fun getObjectFieldSafe(obj: Any?, name: String): Any? = try {
        if (obj == null) null else XposedHelpers.getObjectField(obj, name)
    } catch (t: Throwable) {
        null
    }

    /**
     * Searches several candidate field names across the whole class hierarchy
     * (Samsung often moves fields into base classes).
     */
    fun findFieldValue(obj: Any?, names: List<String>): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (name in names) {
                try {
                    val field = clazz.getDeclaredField(name)
                    field.isAccessible = true
                    return field.get(obj)
                } catch (ignored: Throwable) {
                    // try next name / superclass
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** Safe method invocation via XposedHelpers (logs nothing on failure; caller decides). */
    fun callMethodSafe(obj: Any?, name: String, vararg args: Any?): Any? = try {
        if (obj == null) null else XposedHelpers.callMethod(obj, name, *args)
    } catch (t: Throwable) {
        null
    }

    /**
     * Reflectively invokes [name] on [obj] matching only by method name + parameter count.
     * Used for best-effort collapse/expansion control where the exact signature may vary
     * between One UI builds. Returns true if SOME overload was successfully invoked.
     */
    fun invokeByName(obj: Any?, name: String, args: Array<out Any?>): Boolean {
        if (obj == null) return false
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (method in clazz.declaredMethods) {
                if (method.name != name || method.parameterCount != args.size) continue
                try {
                    method.isAccessible = true
                    method.invoke(obj, *args)
                    return true
                } catch (ignored: Throwable) {
                    // Wrong parameter types -> try the next overload.
                }
            }
            clazz = clazz.superclass
        }
        return false
    }

    /** Dumps every declared method of [clazz] into the LSPosed log (JADX calibration aid). */
    fun dumpMethods(clazz: Class<*>) {
        try {
            Log.i("--- METHODS of ${clazz.name} ---")
            clazz.declaredMethods.forEach { m ->
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                Log.i("  ${m.name}($params)")
            }
            Log.i("--- END ${clazz.name} ---")
        } catch (t: Throwable) {
            Log.e("dumpMethods failed for ${clazz.name}", t)
        }
    }
}
