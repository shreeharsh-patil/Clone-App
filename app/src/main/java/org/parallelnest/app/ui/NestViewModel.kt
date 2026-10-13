package org.parallelnest.app.ui

import android.app.Application
import androidx.datastore.preferences.core.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.parallelnest.app.NestApplication
import org.parallelnest.app.core.*

data class NestState(val profile: ProfileState? = null, val instances: List<CloneInfo> = emptyList(), val apps: List<AppInfo> = emptyList(), val busy: Boolean = false, val message: String? = null, val onboarded: Boolean = false, val theme: String = "system", val dynamic: Boolean = true, val grid: Boolean = false)
class NestViewModel(app: Application) : AndroidViewModel(app) {
    private val nest = app as NestApplication
    val engine = nest.engine
    val registry = nest.registry
    private val mutable = MutableStateFlow(NestState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            registry.settings.collect { p -> mutable.update { it.copy(onboarded = p[booleanPreferencesKey("onboarded")] ?: false, theme = p[stringPreferencesKey("theme")] ?: "system", dynamic = p[booleanPreferencesKey("dynamic")] ?: true, grid = p[booleanPreferencesKey("grid")] ?: false) } }
        }
        viewModelScope.launch { registry.instances.collect { refresh() } }
        refresh()
    }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) { try { val profile = engine.status(); val rows = engine.getInstances(); mutable.update { it.copy(profile = profile, instances = rows) } } catch (e: Exception) { report(e.message ?: "Unable to read registry") } } }
    fun discover() { viewModelScope.launch { runOperation { val apps = engine.listSupportedApps(); mutable.update { it.copy(apps = apps) }; null } } }
    fun report(message: String) { mutable.update { it.copy(message = message) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    private suspend fun runOperation(block: suspend () -> OperationResult?) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        try { block()?.let { report(it.message) } } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e.message ?: "Operation failed") } finally { mutable.update { it.copy(busy = false) }; refresh() }
    }
    fun create(app: AppInfo, name: String) { viewModelScope.launch { runOperation { engine.createInstance(app, name) } } }
    fun launch(row: CloneInfo) { viewModelScope.launch { runOperation { engine.launchInstance(row) } } }
    fun freeze(row: CloneInfo) { viewModelScope.launch { runOperation { engine.stopInstance(row) } } }
    fun delete(row: CloneInfo) { viewModelScope.launch { runOperation { engine.deleteInstance(row) } } }
    fun synchronize() { viewModelScope.launch { runOperation { engine.adoptProfileApps() } } }
    fun info(row: CloneInfo) { viewModelScope.launch { runOperation { val r=engine.request("info", row.packageName); OperationResult(r.getBooleanExtra("success",false),r.getStringExtra("message").orEmpty()) } } }
    fun rename(row: CloneInfo, name: String) { viewModelScope.launch(Dispatchers.IO) { runOperation { engine.rename(row, name); OperationResult(true,"Name updated") } } }
    fun lock(row: CloneInfo, value: Boolean) { viewModelScope.launch(Dispatchers.IO) { runOperation { engine.lock(row, value); null } } }
    fun accent(row: CloneInfo, value: Int) { viewModelScope.launch(Dispatchers.IO) { runOperation { engine.accent(row, value); null } } }
    fun setting(name: String, value: Boolean) { viewModelScope.launch { registry.setting(name,value) } }
    fun theme(value: String) { viewModelScope.launch { registry.textSetting("theme",value) } }
}
