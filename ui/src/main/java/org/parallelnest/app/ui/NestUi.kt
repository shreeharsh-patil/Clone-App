package org.parallelnest.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import kotlinx.coroutines.*
import org.parallelnest.app.core.*
import org.parallelnest.app.core.diagnostics.*
import org.parallelnest.app.engine.virtualization.VirtualContainerEngine
import java.text.DateFormat
import java.util.Date

val NestBlue=Color(0xFF39A8F4)
val NestPurple=Color(0xFFA773F8)
fun nestDarkColors()=darkColorScheme(primary=NestBlue,onPrimary=Color.White,secondary=NestPurple,background=Color(0xFF121212),onBackground=Color.White,
    surface=Color(0xFF1E1E1E),onSurface=Color.White,surfaceContainer=Color(0xFF202020),surfaceContainerHigh=Color(0xFF292929),surfaceContainerHighest=Color(0xFF292929),onSurfaceVariant=Color(0xFFA1A1AA),outline=Color(0xFF303030),outlineVariant=Color(0xFF303030))
private val icons=LruCache<String,Bitmap>(64)
@Composable fun AppIcon(pkg: String,accent: Int=0,profile: Long?=null,size: Int=44) {
    val context=LocalContext.current
    val key="$pkg@$profile"
    val bitmap by produceState<Bitmap?>(icons[key],key) {
        value=withContext(Dispatchers.IO) {
            icons[key] ?: runCatching {
                val original=runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrElse {
                    val serial=profile ?: throw it
                    val users=context.getSystemService(android.os.UserManager::class.java)
                    val user=users.userProfiles.firstOrNull { users.getSerialNumberForUser(it)==serial } ?: throw it
                    context.getSystemService(android.content.pm.LauncherApps::class.java).getActivityList(pkg,user).first().getIcon(context.resources.displayMetrics.densityDpi)
                }
                Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).also { b -> original.setBounds(0,0,96,96);original.draw(Canvas(b));icons.put(key,b) }
            }.getOrNull()
        }
    }
    val frame=when(accent){1->NestBlue.copy(alpha=.18f);2->NestPurple.copy(alpha=.18f);3->NestBlue.copy(alpha=.35f);4->MaterialTheme.colorScheme.surfaceContainerHighest;else->Color.Transparent}
    Surface(Modifier.size(size.dp),shape=RoundedCornerShape(12.dp),color=frame) {
        Box(contentAlignment=Alignment.Center) { bitmap?.let { Image(it.asImageBitmap(),null,Modifier.size((if(accent==0)size else size-8).dp)) } ?: Text(pkg.substringAfterLast('.').take(1).uppercase(),fontWeight=FontWeight.Bold) }
    }
}
data class UiActions(val version: String,val debug: Boolean,val launch: (CloneInfo)->Unit,val original: (String)->Unit,
    val bluetoothSettings: ()->Unit,val bluetoothPermissions: ()->Unit,val bluetoothShare: ()->Unit,val backup: ()->Unit,val restore: ()->Unit,val logs: ()->Unit,val repository: ()->Unit,val storage: ()->Unit,val shortcut: (CloneInfo)->Unit)
@Composable private fun Page(title: String,content: @Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) { Text(title,style=MaterialTheme.typography.headlineSmall);content() }
}
@Composable private fun Note(title: String,body: String,content: @Composable ColumnScope.()->Unit={}) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,style=MaterialTheme.typography.titleMedium);Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);content() }
    }
}
@Composable private fun GroupTitle(text: String) { Text(text,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun MoreIcon() { androidx.compose.foundation.Canvas(Modifier.size(24.dp)) { listOf(.25f,.5f,.75f).forEach { y -> drawCircle(Color(0xFFA1A1AA),2.dp.toPx(),androidx.compose.ui.geometry.Offset(size.width/2,size.height*y)) } } }
@Composable private fun BackIcon() { Text("‹",fontSize=32.sp,color=MaterialTheme.colorScheme.onSurface) }
@Composable private fun SearchIcon() { androidx.compose.foundation.Canvas(Modifier.size(24.dp)) { val center=androidx.compose.ui.geometry.Offset(size.width*.42f,size.height*.42f);drawCircle(Color(0xFFA1A1AA),size.width*.26f,center,style=Stroke(2.dp.toPx()));drawLine(Color(0xFFA1A1AA),androidx.compose.ui.geometry.Offset(size.width*.62f,size.height*.62f),androidx.compose.ui.geometry.Offset(size.width*.9f,size.height*.9f),2.dp.toPx()) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NestUi(vm: NestViewModel,actions: UiActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val generalRuntime=state.runtime?.type=="general_experimental"
    val nav=rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route=back?.destination?.route ?: "home"
    var overflow by remember { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<CloneInfo?>(null) }
    var delete by remember { mutableStateOf<CloneInfo?>(null) }
    var unavailable by remember { mutableStateOf<AppInfo?>(null) }
    var name by remember { mutableStateOf("") }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it);vm.clearMessage() } }
    fun add() { vm.discover();nav.navigate("deviceapps") }
    fun install() { vm.createSelected();nav.navigate("progress") }
    fun info(row: CloneInfo) { nav.navigate("instance/${row.id}") }
    fun rename(row: CloneInfo) { name=row.name;rename=row }
    Scaffold(containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(when {route=="deviceapps"->"Device Apps";route=="home"->"Clone Apps";route=="settings"->"Settings";route=="bluetooth"->"Bluetooth";route=="diagnostics"->"Diagnostics";else->"Clone Apps"},fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)},
            navigationIcon={if(route!="home")IconButton(onClick={nav.popBackStack()},modifier=Modifier.semantics{contentDescription="Back"}){BackIcon()}},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface),
            actions={
                if(route=="deviceapps")IconButton(onClick={searchVisible=!searchVisible},modifier=Modifier.semantics{contentDescription="Search apps"}){SearchIcon()}
                Box {
                    IconButton(onClick={overflow=true},modifier=Modifier.semantics{contentDescription="More options"}){MoreIcon()}
                    DropdownMenu(expanded=overflow,onDismissRequest={overflow=false}) {
                        DropdownMenuItem(text={Text("Settings")},onClick={overflow=false;nav.navigate("settings")})
                        DropdownMenuItem(text={Text("About")},onClick={overflow=false;nav.navigate("about")})
                        DropdownMenuItem(text={Text("Refresh Apps")},onClick={overflow=false;vm.synchronize();if(route=="deviceapps")vm.discover()})
                        DropdownMenuItem(text={Text("Bluetooth")},onClick={overflow=false;nav.navigate("bluetooth")})
                        if(route=="deviceapps")DropdownMenuItem(text={Text("Clear selection")},onClick={overflow=false;vm.clearSelection()})
                    }
                }
            })},snackbarHost={SnackbarHost(snackbar)}) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav,startDestination="home") {
                composable("home") {
                    LazyVerticalGrid(columns=GridCells.Fixed(3),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item(span={GridItemSpan(3)}) { Text(if(generalRuntime)"Experimental runtime · app compatibility varies" else "Test prototype · only Clone Apps Probe can be cloned",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(state.instances,key={it.id}) { row -> CloneTile(row,state.busy,{actions.launch(row)},{rename(row)},{vm.accent(row,(row.accent+1)%5)},{info(row)},{vm.info(row)},{delete=row}) }
                        item(key="add-app") { AddTile(enabled=!state.busy,onClick=::add) }
                    }
                }
                composable("deviceapps") {
                    val apps=remember(state.apps,state.query){AppCatalog.filter(state.apps,state.query)}
                    val supported=apps.filter { it.supported }
                    val unsupported=apps.filterNot { it.supported }
                    Column(Modifier.fillMaxSize()) {
                        if(searchVisible || state.query.isNotEmpty()) OutlinedTextField(state.query,vm::query,label={Text("Search apps")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),trailingIcon={if(state.query.isNotEmpty())TextButton(onClick={vm.query("")}){Text("Clear")}})
                        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.weight(1f).testTag("installed-apps"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
                            item {
                                val noneAvailable=state.discoveryComplete && state.apps.none { it.supported }
                                Note(if(!state.discoveryComplete)"Checking installed apps" else if(noneAvailable)"No supported apps installed" else if(generalRuntime)"Experimental app cloning" else "Limited app support",
                                    if(generalRuntime)"Select an app to attempt cloning. A clone is recorded after its guest Activity resumes. App features and device compatibility still need verification."
                                    else "This version can clone only the Clone Apps Probe test app. Other installed apps cannot be cloned with this runtime. Tap an unavailable app for its compatibility details.") {
                                    TextButton(onClick={nav.navigate("runtime")}){Text("View runtime support")}
                                }
                            }
                            if(supported.isNotEmpty()) {
                                item { Text(if(generalRuntime)"Available to try" else "Available to clone",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=20.dp,bottom=12.dp)) }
                                itemsIndexed(supported,key={_,a->"supported:${a.packageName}"}) { index,app -> DeviceAppRow(app,index,supported.size,state,vm){unavailable=it} }
                            }
                            if(unsupported.isNotEmpty()) {
                                item { Text("Unavailable in this version",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=20.dp,bottom=12.dp)) }
                                itemsIndexed(unsupported,key={_,a->"unsupported:${a.packageName}"}) { index,app -> DeviceAppRow(app,index,unsupported.size,state,vm){unavailable=it} }
                            }
                            if(apps.isEmpty() && state.discoveryComplete && !state.busy)item { Text("No installed apps match this search.",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=16.dp)) }
                        }
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(generalRuntime)"Experimental · successful launch required" else "Activity prototype · first-party probe only",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            GradientInstall(state.selection.size,enabled=state.selection.isNotEmpty() && !state.busy,onClick=::install)
                        }
                    }
                }
                composable("runtime") { Page("Guest runtime") {
                    Note(if(generalRuntime)"Experimental app runtime" else "Activity hosting prototype", state.runtime?.description ?: "Checking runtime…")
                    state.runtime?.capabilities?.forEach { (feature, status) -> Text("$feature: $status") }
                    Text("Guests share the host Android UID. Directory separation does not provide an Android security sandbox.")
                } }
                composable("progress") {
                    Page("Creating Clone") {
                        state.creation.current?.let { app -> AppIcon(app.packageName,size=64);Text(app.label,style=MaterialTheme.typography.titleLarge) }
                        if(state.creation.running)LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.creation.stage)
                        Text("${state.creation.finished} of ${state.creation.total} applications finished",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        state.creation.outcomes.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                        if(state.creation.running && !state.creation.cancelRemaining)TextButton(onClick=vm::cancelRemaining){Text("Cancel remaining apps")}
                        if(state.creation.cancelRemaining && state.creation.running)Text("The current operation completes through Android. Remaining apps will not start.",style=MaterialTheme.typography.bodySmall)
                        if(!state.creation.running)Button(onClick={nav.navigate("home"){popUpTo("home"){inclusive=true}}},modifier=Modifier.fillMaxWidth()){Text("Open Home")}
                    }
                }
                composable("instance/{id}") { entry ->
                    val row=state.instances.find { it.id==entry.arguments?.getString("id") }
                    if(row==null)Page("Clone unavailable"){Text("The guest registry entry is unavailable. Refresh Apps.")}
                    else Page(row.name) {
                        AppIcon(row.packageName,row.accent,if(row.engine=="work_profile")row.profile else null,72)
                        Text("Clone ${row.number} · ${if(generalRuntime)"experimental app runtime" else "in-app Activity prototype"}",style=MaterialTheme.typography.titleMedium)
                        Text(row.packageName,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Created ${DateFormat.getDateTimeInstance().format(Date(row.created))}\nLast successful launch: ${if(row.lastLaunch==0L) "Never" else DateFormat.getDateTimeInstance().format(Date(row.lastLaunch))}",style=MaterialTheme.typography.bodySmall)
                        Text("Virtual user ${row.virtualUser} · ${row.runtime} · ${row.compatibility}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick={actions.launch(row)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Launch")}
                        DetailAction("Rename"){rename(row)}
                        DetailAction("Change Icon"){vm.accent(row,(row.accent+1)%5)}
                        DetailAction("App Info"){vm.info(row)}
                        DetailAction("Storage"){vm.info(row)}
                        Text(if(generalRuntime)"Guest data is stored under this instance's virtual user. APK code is shared between clones of the same version. Guests share Clone Apps' Android UID."
                            else "Guest data is stored under this instance UUID. Guests share Clone Apps' Android UID.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        DetailAction("Update guest APK"){vm.update(row)}
                        DetailAction("Compatibility Check"){vm.diagnostics(row);nav.navigate("diagnostics")}
                        DetailAction("Delete Clone",true){delete=row}
                        TextButton(onClick={vm.freeze(row)},enabled=!state.busy){Text("Stop guest")}
                        TextButton(onClick={actions.original(row.packageName)}){Text("Launch original app")}
                        TextButton(onClick={actions.shortcut(row)}){Text("Add launch shortcut")}
                    }
                }
                composable("settings") { Page("Settings") {
                    GroupTitle("General")
                    SettingsAction("Dark Theme",state.theme=="dark"){vm.theme("dark")}
                    SettingsAction("System Theme",state.theme=="system"){vm.theme("system")}
                    SettingsAction("Refresh Apps"){vm.synchronize()}
                    SettingsAction("Clone Engine Status"){nav.navigate("runtime")}
                    if(state.legacy.isNotEmpty())SettingsAction("Legacy work-profile recovery"){nav.navigate("legacy")}
                    SettingsAction("Bluetooth devices & file sharing"){nav.navigate("bluetooth")}
                    GroupTitle("Performance")
                    SettingsAction("Diagnostics"){nav.navigate("diagnostics")}
                    SettingsAction("Compatibility Check"){nav.navigate("diagnostics")}
                    SettingsAction("Storage Management",action=actions.storage)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick=actions.backup){Text("Export metadata")};TextButton(onClick=actions.restore){Text("Restore metadata")}}
                    GroupTitle("About")
                    Text("App Version · ${actions.version}",style=MaterialTheme.typography.bodyMedium)
                    SettingsAction("Open-Source Licenses"){nav.navigate("notices")}
                    SettingsAction("GitHub Repository",action=actions.repository)
                    SettingsAction("Privacy Policy"){nav.navigate("privacy")}
                    SettingsAction("About Clone Apps"){nav.navigate("about")}
                } }
                composable("legacy") { Page("Legacy recovery") {
                    Text("Historical work-profile records are preserved. Existing data stays in its Android profile; this prototype cannot import it. Resume an existing profile in Android settings before launching a legacy app.")
                    state.legacy.forEach { row -> OutlinedButton(onClick={vm.launchLegacy(row)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Open legacy ${row.name}")} }
                } }
                composable("diagnostics") { Page("Diagnostics") {
                    Text(if(generalRuntime)state.runtime?.description.orEmpty() else "First-party Activity hosting supports files, preferences and SQLite. Hardware and background components have no guest bridges. Export diagnostics for runtime capabilities.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    state.instances.forEach { row -> OutlinedButton(onClick={vm.diagnostics(row)},enabled=!state.diagnosticBusy,modifier=Modifier.fillMaxWidth()){Text(row.name,maxLines=1,overflow=TextOverflow.Ellipsis)} }
                    if(state.instances.isEmpty())Text(if(generalRuntime)"No guest instances. Use Add App to select an installed application." else "No guest instances. Install the matching-signed isolation probe and use Add App.")
                    if(state.diagnosticBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.report?.let { report ->
                        Text("${report.packageName}\nProfile ${report.profile} · Android API ${report.api} · app target ${report.target}",style=MaterialTheme.typography.bodySmall)
                        report.capabilities.forEach { c -> Note(c.name,"${c.status.label}\n${c.detail}") }
                        GroupTitle("Requested permissions")
                        report.permissions.forEach { p -> Text("${p.name}\n${if(p.granted) "Granted in this profile" else "Not granted"} · ${p.kind}",style=MaterialTheme.typography.bodySmall) }
                    }
                    Note("Physical test limits","Hardware presence and read-only API checks do not prove capture, accessory connections, call routing, push delivery or third-party sign-in. Physical tests are not recorded for this build. Emulator observations remain separate from hardware acceptance.")
                    TextButton(onClick=actions.logs){Text("Export device diagnostics")}
                } }
                composable("bluetooth") { BluetoothScreen(actions.bluetoothSettings,actions.bluetoothPermissions,actions.bluetoothShare) }
                composable("privacy") { Page("Privacy Policy") {
                    Note(if(generalRuntime)"Experimental app runtime" else "Offline manager",if(generalRuntime)"The experimental runtime has internet permission for guest apps. Guest apps keep their own network and privacy behavior. Automatic runtime log uploading is disabled."
                        else "No internet permission, account with us, analytics, ads or credential collection. Only the trusted first-party probe can run in this prototype.")
                    Note("Shared host UID","Guests run as the host Android UID. Guest hardware bridges are unsupported. The manager's Bluetooth features request normal Android permission consent for the selected operation.")
                    Note("Files and metadata","File sharing grants temporary access only to the file you select. Room stores clone metadata; DataStore stores preferences. Backups contain names, styles and timestamps, not protected app data or provisioning secrets.")
                } }
                composable("about") { Page("About Clone Apps") {
                    Text("${actions.version} · Apache License 2.0")
                    Text(if(generalRuntime)"Kotlin, Jetpack Compose and Rust manager with an experimental source-built BlackBox runtime. App compatibility and physical-device coverage remain incomplete."
                        else "Original Kotlin, Jetpack Compose and Rust implementation. A limited first-party Activity hosting prototype with independent instance directories. No root, ads or subscriptions.")
                    Text(if(generalRuntime)state.runtime?.description.orEmpty() else VirtualContainerEngine.reason,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(generalRuntime)"Includes adapted BlackBox, Dobby, FreeReflection and xDL source. Their licenses and source revisions are included with the experiment."
                        else "Island, Shelter, BlackBox, NewBlackbox and VirtualApp were researched. Their code and artwork are not bundled.")
                    OutlinedButton(onClick={nav.navigate("notices")}){Text("Open-Source Licenses")}
                    TextButton(onClick=actions.repository){Text("GitHub Repository")}
                } }
                composable("notices") {
                    val context=LocalContext.current
                    val parts by produceState<List<String>>(emptyList(),generalRuntime) { value=withContext(Dispatchers.IO){
                        val base=context.assets.open("third-party-notices.txt").bufferedReader().use{it.readText()}
                        val runtime=if(generalRuntime)context.assets.open("third-party-runtime-notices.txt").bufferedReader().use{it.readText()} else ""
                        (base+"\n\n"+runtime).split("\n\n").flatMap{it.chunked(4096)}
                    } }
                    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { items(parts){Text(it,style=MaterialTheme.typography.bodySmall)} }
                }
            }
        }
    }
    rename?.let { row -> AlertDialog(onDismissRequest={rename=null},title={Text("Rename Clone")},text={OutlinedTextField(name,{name=it.take(80)},singleLine=true,label={Text("Display name")})},confirmButton={TextButton(onClick={vm.rename(row,name);rename=null},enabled=name.isNotBlank() && !state.busy){Text("Save")}},dismissButton={TextButton(onClick={rename=null}){Text("Cancel")}}) }
    delete?.let { row -> AlertDialog(onDismissRequest={delete=null},title={Text("Delete ${row.name}?")},text={Text(if(generalRuntime)"This permanently deletes this clone's private data. Shared APK code is removed after its last clone is deleted. Other instances and the original app are preserved." else "This permanently deletes only this guest instance's files, preferences, databases and APK. Other instances and the original app are preserved.")},confirmButton={TextButton(onClick={vm.delete(row);delete=null},enabled=!state.busy){Text("Delete Clone")}},dismissButton={TextButton(onClick={delete=null}){Text("Cancel")}}) }
    unavailable?.let { app -> AlertDialog(onDismissRequest={unavailable=null},title={Text("Cannot clone ${app.label}")},text={Text(app.reason)},confirmButton={TextButton(onClick={unavailable=null}){Text("OK")}}) }
}

@Composable private fun GradientInstall(count: Int,enabled: Boolean,onClick: ()->Unit) {
    Surface(Modifier.fillMaxWidth().heightIn(min=52.dp).alpha(if(enabled)1f else .38f).background(Brush.horizontalGradient(listOf(NestBlue,NestPurple)),RoundedCornerShape(14.dp)).clickable(enabled=enabled,onClick=onClick),shape=RoundedCornerShape(14.dp),color=Color.Transparent) {
        Box(Modifier.padding(14.dp),contentAlignment=Alignment.Center){Text("Clone ($count)",color=Color.White,fontWeight=FontWeight.SemiBold)}
    }
}
@Composable private fun DeviceAppRow(app: AppInfo,index: Int,total: Int,state: NestState,vm: NestViewModel,onUnavailable: (AppInfo)->Unit) {
    val enabled=app.supported && !state.busy
    val shape=RoundedCornerShape(topStart=if(index==0)14.dp else 0.dp,topEnd=if(index==0)14.dp else 0.dp,bottomStart=if(index==total-1)14.dp else 0.dp,bottomEnd=if(index==total-1)14.dp else 0.dp)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,shape)) {
        Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy){if(app.supported)vm.select(app.packageName) else onUnavailable(app)}.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f)){Text(app.label,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(!app.supported)app.reason else app.packageName,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}
            if(app.supported)Checkbox(app.packageName in state.selection,onCheckedChange={vm.select(app.packageName)},enabled=enabled)
            else Text("Details",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        }
        if(index<total-1)HorizontalDivider(Modifier.padding(start=68.dp),color=MaterialTheme.colorScheme.outlineVariant)
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun CloneTile(row: CloneInfo,busy: Boolean,launch: ()->Unit,rename: ()->Unit,accent: ()->Unit,info: ()->Unit,storage: ()->Unit,delete: ()->Unit) {
    var options by remember { mutableStateOf(false) }
    Box(contentAlignment=Alignment.TopCenter) {
        Box(Modifier.widthIn(max=140.dp).fillMaxWidth().height(128.dp).background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(14.dp)).combinedClickable(onClick={if(!busy)launch()},onLongClick={options=true})) {
            Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalAlignment=Alignment.CenterHorizontally){AppIcon(row.packageName,row.accent,if(row.engine=="work_profile")row.profile else null);Text(row.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)}
            Surface(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),shape=RoundedCornerShape(5.dp),color=NestBlue){Box(contentAlignment=Alignment.Center){Text(row.number.toString(),color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold)}}
            DropdownMenu(options,{options=false}) {
                listOf("Launch" to launch,"Rename" to rename,"Change Icon" to accent,"App Info" to info,"Storage Information" to storage,"Delete Clone" to delete).forEach { (label,action) -> DropdownMenuItem(text={Text(label,color=if(label=="Delete Clone")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)},onClick={options=false;action()},enabled=!busy) }
            }
        }
    }
}
@Composable private fun AddTile(enabled: Boolean,onClick: ()->Unit) {
    Box(contentAlignment=Alignment.TopCenter){Column(Modifier.widthIn(max=140.dp).fillMaxWidth().height(128.dp).background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(14.dp)).clickable(enabled=enabled,onClick=onClick).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("+",fontSize=40.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("Add App",style=MaterialTheme.typography.bodySmall)}}
}
@Composable private fun DetailAction(label: String,destructive: Boolean=false,action: ()->Unit) {
    Card(onClick=action,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){Text(label,Modifier.padding(16.dp),color=if(destructive)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
}
@Composable private fun SettingsAction(label: String,selected: Boolean?=null,action: ()->Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(12.dp)).clickable(onClick=action).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));selected?.let { RadioButton(it,onClick=null) }}
}
