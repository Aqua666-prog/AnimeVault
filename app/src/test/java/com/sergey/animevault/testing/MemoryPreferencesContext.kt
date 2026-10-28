package com.sergey.animevault.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** SharedPreferences fixture with durable state across recreated production stores. No Android runtime. */
class MemoryPreferencesContext : ContextWrapper(null) {
    private val files = mutableMapOf<String, SharedPreferences>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = files.getOrPut(name) { memoryPreferences() }
}

private fun memoryPreferences(): SharedPreferences {
    val values = mutableMapOf<String, Any>()
    fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        var clear = false
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "clear" -> { clear = true; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "apply", "commit" -> {
                    if (clear) values.clear()
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    if (method.name == "commit") true else null
                }
                else -> {
                    check(method.name.startsWith("put")) { method.name }
                    pending[args!![0] as String] = args[1]
                    proxy
                }
            }
        } as SharedPreferences.Editor
    }
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getAll" -> values.toMap()
            "contains" -> values.containsKey(args!![0])
            "edit" -> editor()
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            else -> {
                check(method.name.startsWith("get")) { method.name }
                values[args!![0]] ?: args[1]
            }
        }
    } as SharedPreferences
}
