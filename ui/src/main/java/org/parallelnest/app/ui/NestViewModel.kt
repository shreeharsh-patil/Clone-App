package org.parallelnest.app.ui

import android.app.Application
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.parallelnest.app.core.*
import org.parallelnest.app.core.diagnostics.*
import org.parallelnest.app.engine.api.*

object AppCatalog {
    private val popular=setOf("com.whatsapp","com.instagram.android","com.snapchat.android","com.discord","org.telegram.messenger","com.openai.chatgpt")
    fun filter(apps: List<AppInfo>,query: String)=apps.filter { it.label.contains(query.trim(),true) || it.packageName.contains(query.trim(),true) }.sortedBy { it.label.lowercase() }
    fun hot(apps: List<AppInfo>)=apps.filter { it.packageName in popular }
    fun toggle(selected: List<String>,pkg: String)=if(pkg in selected) selected-pkg else selected+pkg
}
data class CreationState(val current: AppInfo?=null,val stage: String="Checking compatibility",val total: Int=0,val finished: Int=0,val running: Boolean=false,val outcomes: List<String> = emptyList(),val cancelRemaining: Boolean=false)
data class NestState(val profile: ProfileState?=null,val instances: List<CloneInfo> = emptyList(),val apps: List<AppInfo> = emptyList(),val busy: Boolean=false,val message: String?=null,
    val theme: String="dark",val selection: List<String> = emptyList(),val query: String="",val creation: CreationState=CreationState(),val report: CompatibilityReport?=null,val diagnosticBusy: Boolean=false)
class NestViewModel(app: Application,private val saved: SavedStateHandle): AndroidViewModel(app) {
    private val nest=app as NestServices
    val engine=nest.engine
    val profileEngine=nest.profileEngine
    val registry=nest.registry
    private val operation=Mutex()
    private val mutable=MutableStateFlow(NestState(selection=saved["selection"] ?: emptyList(),query=saved["query"] ?: ""))
    val state=mutable.asStateFlow()
    init {
        viewModelScope.launch { registry.settings.catch { report("Unable to read preferences: ${it.message}") }.collect { p -> mutable.update { it.copy(theme=p[stringPreferencesKey("theme")] ?: "dark") } } }
        viewModelScope.launch { registry.instances.catch { report("Unable to read metadata: ${it.message}") }.collect { refresh() } }
        viewModelScope.launch(Dispatchers.IO) { try { if(registry.pendingCreations().isNotEmpty()) report("An installation was interrupted. Refresh Apps to recover any completed work copies.") } catch(e:CancellationException){throw e}catch(e:Exception){report(e.message ?: "Metadata recovery failed")} }
        refresh()
    }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) {
        try { val profile=engine.status();val rows=engine.getInstances();withContext(Dispatchers.Main) { val left=state.value.selection.filter { pkg -> rows.none { it.packageName==pkg } };saved["selection"]=left;mutable.update { it.copy(profile=profile,instances=rows,selection=left) } } }
        catch(e:CancellationException){throw e}catch(e:Exception){report(e.message ?: "Could not read instances")}
    } }
    fun report(message: String) { mutable.update { it.copy(message=message) } }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    private suspend fun runOperation(block: suspend ()->OperationResult?) {
        if(!operation.tryLock())return
        mutable.update { it.copy(busy=true) }
        try { block()?.let { report(it.message) } }
        catch(e:CancellationException){throw e}catch(e:Exception){report(e.message ?: "Operation failed")}
        finally { mutable.update { it.copy(busy=false) };operation.unlock();refresh() }
    }
    fun discover() { viewModelScope.launch { runOperation { val apps=engine.listSupportedApps();mutable.update { it.copy(apps=apps) };null } } }
    fun query(value: String) { saved["query"]=value;mutable.update { it.copy(query=value) } }
    fun select(pkg: String) {
        if(state.value.busy || state.value.apps.none { it.packageName==pkg && it.supported } || state.value.instances.any { it.packageName==pkg })return
        val selected=AppCatalog.toggle(state.value.selection,pkg);saved["selection"]=selected;mutable.update { it.copy(selection=selected) }
    }
    fun clearSelection() { saved["selection"]=emptyList<String>();mutable.update { it.copy(selection=emptyList()) } }
    fun createSelected() {
        val picks=state.value.selection.mapNotNull { pkg -> state.value.apps.find { it.packageName==pkg && it.supported } }
        if(picks.isEmpty()) { report("Select at least one eligible installed app");return }
        if(!engine.status().bridgeAvailable) { report(engine.status().description);return }
        viewModelScope.launch { runOperation {
            mutable.update { it.copy(creation=CreationState(total=picks.size,running=true)) }
            try {
                for(app in picks) {
                    if(state.value.creation.cancelRemaining)break
                    mutable.update { it.copy(creation=it.creation.copy(current=app,stage=CreationStage.CHECKING.description)) }
                    val result=try { engine.createInstance(app,app.label.take(80)) { stage -> mutable.update { it.copy(creation=it.creation.copy(stage=stage.description)) } } }
                        catch(e:CancellationException){throw e}catch(e:Exception){OperationResult(false,e.message ?: "Installation failed")}
                    if(result.success) { val left=state.value.selection-app.packageName;saved["selection"]=left;mutable.update { it.copy(selection=left) } }
                    mutable.update { it.copy(creation=it.creation.copy(finished=it.creation.finished+1,outcomes=it.creation.outcomes+"${app.label}: ${result.message}")) }
                }
            } finally { mutable.update { it.copy(creation=it.creation.copy(running=false,stage="Finished")) } }
            null
        } }
    }
    fun cancelRemaining() { mutable.update { it.copy(creation=it.creation.copy(cancelRemaining=true)) } }
    fun launch(row: CloneInfo) { viewModelScope.launch { runOperation { engine.launchInstance(row) } } }
    fun freeze(row: CloneInfo) { viewModelScope.launch { runOperation { engine.stopInstance(row) } } }
    fun delete(row: CloneInfo) { viewModelScope.launch { runOperation { engine.deleteInstance(row) } } }
    fun synchronize() { viewModelScope.launch { runOperation { if(engine.status().bridgeAvailable)profileEngine.adoptProfileApps() else { refresh();OperationResult(false,engine.status().description) } } } }
    fun info(row: CloneInfo) { viewModelScope.launch { runOperation { val r=profileEngine.request("info",row.packageName);OperationResult(r.getBooleanExtra("success",false),r.getStringExtra("message").orEmpty()) } } }
    fun rename(row: CloneInfo,name: String) { viewModelScope.launch { runOperation { profileEngine.rename(row,name);OperationResult(true,"Name updated") } } }
    fun accent(row: CloneInfo,value: Int) { viewModelScope.launch { runOperation { profileEngine.accent(row,value);OperationResult(true,"Instance icon style updated") } } }
    fun theme(value: String) { viewModelScope.launch { registry.textSetting("theme",value) } }
    fun diagnostics(row: CloneInfo) {
        viewModelScope.launch {
            if(state.value.diagnosticBusy)return@launch
            mutable.update { it.copy(diagnosticBusy=true,report=null) }
            try {
                val reply=profileEngine.request("compatibility",row.packageName)
                check(reply.getBooleanExtra("success",false)) { reply.getStringExtra("message").orEmpty() }
                val report=CompatibilityReport.parse(reply.getStringExtra("diagnostics_json") ?: error("No diagnostic response"))
                check(report.managed && report.profile==row.profile && report.packageName==row.packageName) { "Diagnostic profile/app identity mismatch" }
                mutable.update { it.copy(report=report) }
            } catch(e:CancellationException){throw e}catch(e:Exception){report(e.message ?: "Could not inspect this work app")}
            finally { mutable.update { it.copy(diagnosticBusy=false) } }
        }
    }
}
