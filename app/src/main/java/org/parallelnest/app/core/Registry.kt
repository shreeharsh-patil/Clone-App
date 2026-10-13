package org.parallelnest.app.core

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.nestData by preferencesDataStore("nest")
object NativeCore {
    init { System.loadLibrary("parallelnest_core") }
    @JvmStatic external fun canonicalize(input: String): String
}
object RegistryCodec {
    fun mergeRestored(old: List<CloneInfo>, incoming: List<CloneInfo>): List<CloneInfo> {
        val restored = incoming.associateBy { it.packageName to it.profile }
        val merged = old.map { row -> restored[row.packageName to row.profile]?.copy(id=row.id, locked=false) ?: row }.toMutableList()
        incoming.filter { row -> old.none { it.packageName == row.packageName && it.profile == row.profile } }.forEach { row ->
            merged += row.copy(id=if(merged.any { it.id==row.id }) UUID.randomUUID().toString() else row.id, locked=false)
        }
        return merged
    }
    fun encode(rows: List<CloneInfo>): String = JSONObject().put("schema", 1).put("instances", JSONArray().apply {
        rows.forEach { r -> put(JSONObject().put("id", r.id).put("package", r.packageName).put("name", r.name).put("profile", r.profile).put("created", r.created).put("last_launch", r.lastLaunch).put("locked", r.locked).put("accent", r.accent)) }
    }).toString()
    fun decode(text: String): List<CloneInfo> {
        val items = JSONObject(text).getJSONArray("instances")
        return (0 until items.length()).map { n -> items.getJSONObject(n).let { r -> CloneInfo(r.getString("id"), r.getString("package"), r.getString("name"), r.getLong("profile"), r.getLong("created"), r.getLong("last_launch"), r.getBoolean("locked"), r.getInt("accent")) } }
    }
}
class Registry(private val context: Context) {
    private val key = stringPreferencesKey("registry")
    private val empty = "{\"schema\":1,\"instances\":[]}"
    val instances: Flow<List<CloneInfo>> = context.nestData.data.map { RegistryCodec.decode(NativeCore.canonicalize(it[key] ?: empty)) }.flowOn(Dispatchers.IO)
    val settings: Flow<Preferences> = context.nestData.data
    suspend fun update(transform: (List<CloneInfo>) -> List<CloneInfo>) = withContext(Dispatchers.IO) {
        context.nestData.edit { prefs ->
            val old = RegistryCodec.decode(NativeCore.canonicalize(prefs[key] ?: empty))
            prefs[key] = NativeCore.canonicalize(RegistryCodec.encode(transform(old)))
        }
    }
    suspend fun setting(name: String, value: Boolean) { context.nestData.edit { it[booleanPreferencesKey(name)] = value } }
    suspend fun textSetting(name: String, value: String) { context.nestData.edit { it[stringPreferencesKey(name)] = value } }
    suspend fun backup(): String {
        val p = context.nestData.data.first()
        return JSONObject().put("schema", 1).put("registry", JSONObject(NativeCore.canonicalize(p[key] ?: empty))).put("theme", p[stringPreferencesKey("theme")] ?: "system").put("dynamic", p[booleanPreferencesKey("dynamic")] ?: true).put("grid",p[booleanPreferencesKey("grid")] ?: false).toString(2)
    }
    suspend fun restore(text: String, available: Set<Pair<String, Long>>): Int {
        require(text.toByteArray().size <= 1_048_576) { "Backup exceeds 1 MiB" }
        val root = JSONObject(text)
        require(root.getInt("schema") == 1) { "Unsupported backup version" }
        val incoming = RegistryCodec.decode(NativeCore.canonicalize(root.getJSONObject("registry").toString())).filter { it.packageName to it.profile in available }.map { it.copy(locked = false) }
        val theme = root.optString("theme", "system")
        require(theme in listOf("system", "light", "dark"))
        context.nestData.edit { p ->
            val old = RegistryCodec.decode(NativeCore.canonicalize(p[key] ?: empty))
            p[key] = NativeCore.canonicalize(RegistryCodec.encode(RegistryCodec.mergeRestored(old,incoming)))
            p[stringPreferencesKey("theme")] = theme
            p[booleanPreferencesKey("dynamic")] = root.optBoolean("dynamic", true)
            p[booleanPreferencesKey("grid")] = root.optBoolean("grid", false)
        }
        return incoming.size
    }
}
