package org.parallelnest.app.engine.virtualization

import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small synchronous, atomic preferences implementation for the cooperative prototype. */
internal class GuestPreferences(file: File) : SharedPreferences {
    private val disk = AtomicFile(file)
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val values = mutableMapOf<String, Any>()
    init {
        if (file.exists()) {
            val root = disk.openRead().bufferedReader().use { JSONObject(it.readText()) }
            root.keys().forEach { key ->
                val row = root.getJSONObject(key)
                values[key] = when (row.getString("type")) {
                    "set" -> row.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    "long" -> row.getLong("value")
                    "int" -> row.getInt("value")
                    "float" -> row.getDouble("value").toFloat()
                    "boolean" -> row.getBoolean("value")
                    "string" -> row.getString("value")
                    else -> error("Invalid preference type")
                }
            }
        }
    }
    @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    @Synchronized override fun getString(key: String?, defValue: String?): String? = values[key]?.let { it as String } ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key: String?, defValue: Int) = values[key]?.let { it as Int } ?: defValue
    @Synchronized override fun getLong(key: String?, defValue: Long) = values[key]?.let { it as Long } ?: defValue
    @Synchronized override fun getFloat(key: String?, defValue: Float) = values[key]?.let { it as Float } ?: defValue
    @Synchronized override fun getBoolean(key: String?, defValue: Boolean) = values[key]?.let { it as Boolean } ?: defValue
    @Synchronized override fun contains(key: String?) = values.containsKey(key)
    @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { check(commit()) { "Guest preferences write failed" } }
        override fun commit(): Boolean = synchronized(this@GuestPreferences) {
            val next = if (clear) mutableMapOf() else values.toMutableMap()
            pending.forEach { (k, v) -> if (v == null) next.remove(k) else next[k] = v }
            val json = JSONObject()
            next.forEach { (k, v) ->
                val type = when (v) { is Set<*> -> "set"; is Long -> "long"; is Int -> "int"; is Float -> "float"; is Boolean -> "boolean"; else -> "string" }
                json.put(k, JSONObject().put("type", type).put("value", if (v is Set<*>) JSONArray(v.toList()) else v))
            }
            val stream = disk.startWrite()
            try { stream.write(json.toString().toByteArray()); disk.finishWrite(stream) }
            catch (_: Exception) { disk.failWrite(stream); return@synchronized false }
            val changed = (values.keys + next.keys).filter { values[it] != next[it] }
            values.clear(); values.putAll(next)
            changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@GuestPreferences, key) } }
            true
        }
    }
}
