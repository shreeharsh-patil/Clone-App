package org.parallelnest.app.core

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.parallelnest.app.core.database.*
import java.util.UUID

private val Context.nestData by preferencesDataStore("nest")
object NativeCore {
    init { System.loadLibrary("parallelnest_core") }
    @JvmStatic external fun canonicalize(input: String): String
}
object RegistryCodec {
    fun mergeRestored(old: List<CloneInfo>, incoming: List<CloneInfo>): List<CloneInfo> {
        val restored=incoming.associateBy { it.packageName to it.profile }
        val merged=old.map { row -> restored[row.packageName to row.profile]?.copy(id=row.id) ?: row }.toMutableList()
        incoming.filter { row -> old.none { it.packageName==row.packageName && it.profile==row.profile } }.forEach { row ->
            merged += row.copy(id=if(merged.any { it.id==row.id }) UUID.randomUUID().toString() else row.id)
        }
        return merged
    }
    fun encode(rows: List<CloneInfo>)=JSONObject().put("schema",2).put("instances",JSONArray().apply {
        rows.forEach { r -> put(JSONObject().put("id",r.id).put("package",r.packageName).put("name",r.name).put("profile",r.profile)
            .put("created",r.created).put("last_launch",r.lastLaunch).put("accent",r.accent).put("number",r.number).put("engine",r.engine)) }
    }).toString()
    /** Upgrade old metadata without retaining the removed launch-security feature. */
    fun upgrade(text: String): String {
        require(text.toByteArray().size<=1_048_576) { "Metadata exceeds 1 MiB" }
        val root=JSONObject(text)
        if(root.getInt("schema")==1) {
            val entries=root.getJSONArray("instances")
            for(i in 0 until entries.length()) entries.getJSONObject(i).apply { remove("locked");put("number",1);put("engine","work_profile") }
            root.put("schema",2)
        }
        return NativeCore.canonicalize(root.toString())
    }
    fun decode(text: String): List<CloneInfo> {
        val entries=JSONObject(text).getJSONArray("instances")
        return (0 until entries.length()).map { entries.getJSONObject(it).let { r -> CloneInfo(r.getString("id"),r.getString("package"),r.getString("name"),r.getLong("profile"),r.getLong("created"),r.getLong("last_launch"),r.getInt("accent"),r.getInt("number"),r.getString("engine")) } }
    }
    fun checked(rows: List<CloneInfo>)=decode(NativeCore.canonicalize(encode(rows)))
}
class Registry(context: Context) {
    private val context=context.applicationContext
    private val db=NestDatabase.get(this.context)
    private val dao=db.clones()
    private val initialization=Mutex()
    @Volatile private var initialized=false
    private suspend fun ready()=initialization.withLock {
        if(initialized)return@withLock
        withContext(Dispatchers.IO) {
            val prefs=context.nestData.data.first()
            if(prefs[booleanPreferencesKey("room_migrated")]!=true) {
                val previous=prefs[stringPreferencesKey("registry")]
                db.withTransaction {
                    if(dao.snapshot().isEmpty() && previous!=null) dao.insert(RegistryCodec.decode(RegistryCodec.upgrade(previous)).map(CloneRecord::from))
                }
                context.nestData.edit { it[booleanPreferencesKey("room_migrated")]=true;it.remove(stringPreferencesKey("registry"));it.remove(booleanPreferencesKey("grid"));it.remove(booleanPreferencesKey("dynamic"));it.remove(booleanPreferencesKey("onboarded")) }
                // Cleanup only; there is no authentication/lock UI or data model.
                context.getSharedPreferences("secure",Context.MODE_PRIVATE).edit().remove("pin").remove("attempts").remove("blocked_until").commit()
            }
        }
        initialized=true
    }
    val instances: Flow<List<CloneInfo>> = flow { ready();emitAll(dao.observe().map { rows -> RegistryCodec.checked(rows.map { it.model() }) }) }.flowOn(Dispatchers.IO)
    val settings: Flow<Preferences> = context.nestData.data
    suspend fun snapshot(): List<CloneInfo> { ready();return RegistryCodec.checked(dao.snapshot().map { it.model() }) }
    suspend fun update(transform: (List<CloneInfo>)->List<CloneInfo>)=withContext(Dispatchers.IO) {
        ready();db.withTransaction { val rows=RegistryCodec.checked(transform(dao.snapshot().map { it.model() }));dao.clear();dao.insert(rows.map(CloneRecord::from)) }
    }
    suspend fun rememberCreation(nonce: String,pkg: String,name: String) { ready();check(dao.pendingAll().size<128) { "Too many unfinished requests. Refresh Apps before retrying." };dao.remember(PendingCreation(nonce,pkg,name,System.currentTimeMillis())) }
    suspend fun finishCreation(nonce: String,success: Boolean,profile: Long) {
        ready();db.withTransaction {
            val pending=dao.pending(nonce) ?: return@withTransaction
            if(success) {
                require(profile>=0) { "Missing managed-profile identity" }
                val rows=dao.snapshot().map { it.model() }
                if(rows.none { it.packageName==pending.packageName && it.profile==profile }) {
                    val created=CloneInfo(UUID.randomUUID().toString(),pending.packageName,pending.name,profile,pending.created)
                    dao.insert(RegistryCodec.checked(rows+created).filter { it.id==created.id }.map(CloneRecord::from))
                }
            }
            dao.forget(nonce)
        }
    }
    suspend fun forgetCreation(nonce: String) { ready();dao.forget(nonce) }
    suspend fun pendingCreations(): List<PendingCreation> { ready();return dao.pendingAll() }
    suspend fun reconcileCreations(packages: Set<String>,profile: Long) {
        pendingCreations().forEach { pending -> if(pending.packageName in packages) finishCreation(pending.nonce,true,profile) else if(System.currentTimeMillis()-pending.created>180_000) forgetCreation(pending.nonce) }
    }
    suspend fun setting(name: String,value: Boolean) { context.nestData.edit { it[booleanPreferencesKey(name)]=value } }
    suspend fun textSetting(name: String,value: String) { context.nestData.edit { it[stringPreferencesKey(name)]=value } }
    suspend fun backup(): String {
        val prefs=settings.first()
        return JSONObject().put("schema",2).put("registry",JSONObject(RegistryCodec.encode(snapshot()))).put("theme",prefs[stringPreferencesKey("theme")] ?: "dark").toString(2)
    }
    suspend fun restore(text: String,available: Set<Pair<String,Long>>): Int {
        require(text.toByteArray().size<=1_048_576) { "Backup exceeds 1 MiB" }
        val root=JSONObject(text);require(root.getInt("schema") in 1..2) { "Unsupported backup version" }
        val incoming=RegistryCodec.decode(RegistryCodec.upgrade(root.getJSONObject("registry").toString())).filter { it.packageName to it.profile in available }
        val theme=root.optString("theme","dark");require(theme in listOf("system","light","dark")) { "Unknown theme" }
        update { RegistryCodec.mergeRestored(it,incoming) };textSetting("theme",if(theme=="light") "system" else theme)
        return incoming.size
    }
}
