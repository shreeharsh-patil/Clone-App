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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import kotlinx.coroutines.*
import org.parallelnest.app.BuildConfig
import org.parallelnest.app.core.*
import java.text.DateFormat
import java.util.Date

private val iconCache = LruCache<String, Bitmap>(64)
private fun statusText(row: CloneInfo) = when { row.paused -> "Work profile paused"; row.suspended -> "Work app frozen"; row.locked -> "Work · Launch locked"; else -> "Work · Installed" }
@Composable fun AppIcon(pkg: String, accent: Int = 0) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = iconCache[pkg], pkg) {
        value = withContext(Dispatchers.IO) {
            iconCache[pkg] ?: runCatching {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).also { b -> drawable.setBounds(0,0,96,96); drawable.draw(Canvas(b)); iconCache.put(pkg,b) }
            }.getOrNull()
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = if (accent == 0) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) { bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(if (accent == 0) 40.dp else 32.dp)) } ?: Text(pkg.substringAfterLast('.').take(1).uppercase(), fontWeight = FontWeight.Bold) }
    }
}
@Composable private fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}
@Composable private fun InfoCard(title: String, text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant); content() }
    }
}
@Composable private fun Preference(label: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(label); Text(description, style = MaterialTheme.typography.bodySmall) }; Switch(checked, change) }
}
data class UiActions(val provision: () -> Unit, val launch: (CloneInfo) -> Unit, val original: (String) -> Unit, val profileSettings: () -> Unit, val lock: (CloneInfo, Boolean) -> Unit, val pinSetup: () -> Unit, val backup: () -> Unit, val restore: () -> Unit, val logs: () -> Unit, val shortcut: (CloneInfo) -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable fun NestUi(vm: NestViewModel, actions: UiActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    LaunchedEffect(state.onboarded, route) { if (state.onboarded && route == "welcome") nav.navigate("home") { popUpTo("welcome") { inclusive = true } } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("ParallelNest", fontWeight = FontWeight.SemiBold) }, navigationIcon = { if (route !in listOf("home", "welcome", "")) TextButton(onClick = { nav.popBackStack() }) { Text("Back") } }, actions = { if (state.onboarded && route != "settings") TextButton(onClick = { nav.navigate("settings") }) { Text("Settings") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (route == "home" && state.profile?.bridgeAvailable == true) ExtendedFloatingActionButton(onClick = { vm.discover(); nav.navigate("add") }) { Text("+  Add app") } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            NavHost(nav, startDestination = "welcome", modifier = Modifier.weight(1f)) {
                composable("welcome") {
                    Page("A separate space.\nYour own accounts.", "One phone, two independent app environments.") {
                        InfoCard("Built on Android isolation", "ParallelNest uses a work profile with its own app data and accounts. No root, ads, analytics or credential collection.")
                        InfoCard("Know the limits", "One additional copy per app. Existing work-profile controllers cannot be replaced. Some apps, employers and device makers restrict profile use.")
                        Button(onClick = { vm.setting("onboarded",true); nav.navigate("setup") }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                    }
                }
                composable("setup") {
                    var consent by remember { mutableStateOf(false) }
                    Page("Set up your space", state.profile?.description) {
                        InfoCard("Android work profile", "Android will ask for permission to create a managed profile. ParallelNest becomes its controller and can install, launch, suspend and uninstall apps inside it. Personal app data stays separate.")
                        if(BuildConfig.DEBUG) Text("Evaluation build: work-profile debugging is enabled for testing when Android USB debugging is enabled. Production builds retain the default debugging restriction.",style=MaterialTheme.typography.bodySmall)
                        if (state.profile?.canProvision == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent,{consent=it}); Text("I understand and want to create a new work profile", Modifier.weight(1f)) }
                            Button(onClick = actions.provision, enabled = consent && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Create work profile") }
                        }
                        if (state.profile?.bridgeAvailable == true) Button(onClick = { nav.navigate("home") }) { Text("Open dashboard") }
                        OutlinedButton(onClick = actions.profileSettings) { Text("Open Android profile settings") }
                        InfoCard("Experimental virtual containers", VirtualContainerEngine.reason)
                    }
                }
                composable("home") {
                    var search by remember { mutableStateOf("") }
                    val rows = state.instances.filter { it.name.contains(search,true) || it.packageName.contains(search,true) }
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Your spaces", style = MaterialTheme.typography.headlineLarge)
                        Text(state.profile?.description ?: "Checking Android profiles…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.profile?.bridgeAvailable != true) {
                            Button(onClick = { nav.navigate("setup") }) { Text("Set up / resume work profile") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = vm::synchronize, enabled = !state.busy) { Text("Sync work apps") }
                                TextButton(onClick = { vm.setting("grid", !state.grid) }) { Text(if (state.grid) "List view" else "Grid view") }
                            }
                        }
                        OutlinedTextField(search,{search=it}, label={Text("Search instances")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
                        if (rows.isEmpty()) {
                            InfoCard("Make room for another account", if (state.instances.isEmpty()) "Set up your work profile, then add an installed app. Its second copy will have separate persistent data." else "No instances match this search.")
                        } else if (state.grid) {
                            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(rows, key = { it.id }) { row -> InstanceCard(row, state.busy, {actions.launch(row)}, {nav.navigate("instance/${row.id}")}, true) }
                            }
                        } else {
                            LazyColumn(contentPadding=PaddingValues(bottom=100.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) { items(rows,key={it.id}) { row -> InstanceCard(row,state.busy,{actions.launch(row)},{nav.navigate("instance/${row.id}")},false) } }
                        }
                    }
                }
                composable("add") {
                    var search by remember { mutableStateOf("") }
                    var selected by remember { mutableStateOf<AppInfo?>(null) }
                    var name by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("Add an app", style=MaterialTheme.typography.headlineLarge)
                        Text("Android work profile · one additional copy per package. Installation needs your consent. App accounts are not copied.")
                        OutlinedTextField(search,{search=it},label={Text("Search installed launcher apps")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(state.apps.filter { it.label.contains(search,true) || it.packageName.contains(search,true) },key={it.packageName}) { app ->
                                Card(onClick = {selected=app;name="${app.label} · Work".take(80)}, enabled=app.supported && !state.busy) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                        AppIcon(app.packageName); Column(Modifier.weight(1f)) {Text(app.label,fontWeight=FontWeight.Medium);Text(app.packageName,style=MaterialTheme.typography.bodySmall);Text(app.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                    }
                                }
                            }
                        }
                    }
                    selected?.let { app -> AlertDialog(onDismissRequest={selected=null},title={Text("Create work copy")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {Text("${app.label} will be installed in your work profile. Existing work-app data is preserved if already installed.");OutlinedTextField(name,{name=it.take(80)},label={Text("Instance name")})}},confirmButton={TextButton(onClick={vm.create(app,name);selected=null;nav.popBackStack()},enabled=name.isNotBlank() && !state.busy){Text("Install / add")}},dismissButton={TextButton(onClick={selected=null}){Text("Cancel")}}) }
                }
                composable("instance/{id}") { entry ->
                    val row=state.instances.find { it.id==entry.arguments?.getString("id") }
                    var delete by remember { mutableStateOf(false) }
                    var rename by remember { mutableStateOf(false) }
                    var name by remember(row?.name) { mutableStateOf(row?.name.orEmpty()) }
                    if(row==null) Page("Instance unavailable", "Its work profile or app may have been removed. Refresh the dashboard.") {Button(onClick={vm.refresh();nav.popBackStack()}){Text("Return")}}
                    else Page(row.name,row.packageName) {
                        AppIcon(row.packageName,row.accent)
                        Text("${statusText(row)} · serial ${row.profile}")
                        Button(onClick={actions.launch(row)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Launch work copy")}
                        OutlinedButton(onClick={actions.original(row.packageName)},modifier=Modifier.fillMaxWidth()){Text("Launch original")}
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={rename=true}){Text("Rename")};TextButton(onClick={vm.accent(row,(row.accent+1)%2)}){Text("Change icon style")}}
                        OutlinedButton(onClick={actions.lock(row,!row.locked)},modifier=Modifier.fillMaxWidth()){Text(if(row.locked) "Remove launch lock" else "Lock before launch")}
                        OutlinedButton(onClick={vm.freeze(row)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Freeze work app")}
                        OutlinedButton(onClick={vm.info(row)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Work app info & storage")}
                        OutlinedButton(onClick={actions.shortcut(row)},modifier=Modifier.fillMaxWidth()){Text("Add launch shortcut")}
                        Text("Created ${DateFormat.getDateTimeInstance().format(Date(row.created))}\nLast successful launch: ${if(row.lastLaunch==0L) "Never" else DateFormat.getDateTimeInstance().format(Date(row.lastLaunch))}",style=MaterialTheme.typography.bodySmall)
                        InfoCard("Storage & notifications", "App-private storage and cache cannot be read by the manager. Open work app settings for Android's storage controls. Notification permissions are controlled by each app in its profile. Profile suspension can pause delivery.")
                        TextButton(onClick={delete=true},enabled=!state.busy){Text("Delete work instance",color=MaterialTheme.colorScheme.error)}
                        if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Delete ${row.name}?")},text={Text("Uninstalling this work-profile copy permanently removes its accounts and app data. The original personal app and its data are untouched. No arbitrary app-data backup is available.")},confirmButton={TextButton(onClick={delete=false;vm.delete(row);nav.popBackStack()}){Text("Delete work copy")}},dismissButton={TextButton(onClick={delete=false}){Text("Cancel")}})
                        if(rename) AlertDialog(onDismissRequest={rename=false},title={Text("Rename instance")},text={OutlinedTextField(name,{name=it.take(80)},label={Text("Name")})},confirmButton={TextButton(onClick={vm.rename(row,name);rename=false},enabled=name.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick={rename=false}){Text("Cancel")}})
                    }
                }
                composable("settings") {
                    Page("Settings","Keep your space personal.") {
                        Text("Appearance",style=MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system","light","dark").forEach { theme -> FilterChip(selected=state.theme==theme,onClick={vm.theme(theme)},label={Text(theme.replaceFirstChar { it.uppercase() })}) }}
                        Preference("Dynamic color","Use your Android wallpaper colors",state.dynamic){vm.setting("dynamic",it)}
                        InfoCard("Security","Launch locks authenticate every launch made through ParallelNest. Other launchers and notification entry points can still open work apps.") {OutlinedButton(onClick=actions.pinSetup){Text("Set / change fallback PIN")};TextButton(onClick={nav.navigate("privacy")}){Text("Privacy & security")}}
                        InfoCard("Metadata backup","Exports instance names, preferences and timestamps. No app credentials or protected app data. Restore only matches apps still installed in this device's work profile; locks must be configured again."){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=actions.backup){Text("Export")};OutlinedButton(onClick=actions.restore){Text("Restore")}}}
                        OutlinedButton(onClick={nav.navigate("setup")},modifier=Modifier.fillMaxWidth()){Text("Clone engine setup")}
                        OutlinedButton(onClick={nav.navigate("diagnostics")},modifier=Modifier.fillMaxWidth()){Text("Compatibility & diagnostics")}
                        OutlinedButton(onClick=actions.logs,modifier=Modifier.fillMaxWidth()){Text("Export diagnostics")}
                        OutlinedButton(onClick={nav.navigate("licenses")},modifier=Modifier.fillMaxWidth()){Text("About & open-source licenses")}
                    }
                }
                composable("privacy") { Page("Privacy & security","Offline by design.") {
                    InfoCard("Your accounts stay inside their apps","ParallelNest does not collect credentials, intercept traffic, upload installed-app lists or read other apps' private data. No internet permission, analytics, ads or persistent monitoring service is included.")
                    InfoCard("Profile controller permissions","Only the work profile created with your consent is managed. Installation, suspension and deletion affect that profile. Remove it through Android settings only if you intend to erase all work-profile data.")
                    InfoCard("Manager lock scope","Biometrics or device credentials protect manager launches. Optional PINs use salted PBKDF2 verification encrypted with Android Keystore and retry delays. Every launch relocks. This cannot prevent direct launching through other apps.")
                    InfoCard("Backups and storage","Manager metadata is exportable through Android's file picker. APK installation reads only selected installed package files. Protected clone data is never backed up or extracted.")
                } }
                composable("diagnostics") { Page("Compatibility","Capabilities are checked on this device.") {
                    InfoCard("Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\nTarget SDK: 36\nManaged profiles supported: ${state.profile?.supported}\nProvisioning allowed: ${state.profile?.canProvision}\nBridge available: ${state.profile?.bridgeAvailable}\n${state.profile?.description}")
                    InfoCard("Native metadata core","Rust validates schema, identifiers, package names, size limits and uniqueness before persistent writes and imports. Native library must load; builds fail if either supported ABI is missing.")
                    InfoCard("Validation scope","Android 13–16 APIs are targeted. See the repository test report for executed device tests. Third-party app sign-in, notifications, battery use and OEM behavior require device-specific verification.")
                    InfoCard("Virtual containers",VirtualContainerEngine.reason)
                    TextButton(onClick=vm::refresh){Text("Refresh device checks")}
                } }
                composable("licenses") { Page("ParallelNest","${BuildConfig.VERSION_NAME} · Apache License 2.0") {
                    InfoCard("Original, open-source implementation","Work-profile patterns were researched in Island (Apache-2.0) and Shelter (GPL-3.0). Their source code and artwork are not included. BlackBox, NewBlackbox and VirtualApp are not bundled.")
                    InfoCard("Libraries","AndroidX / Jetpack Compose / Material 3: Apache-2.0\nKotlin and kotlinx.coroutines: Apache-2.0\nRust jni: MIT OR Apache-2.0\nSerde / serde_json: MIT OR Apache-2.0\nComplete dependency notices and license texts are distributed with this repository.")
                    OutlinedButton(onClick={nav.navigate("notices")}){Text("Read complete dependency notices")}
                    Text("No ads. No root. No account with us.\nWork profiles support one additional copy per app. Experimental virtualization is unavailable.")
                } }
                composable("notices") {
                    val context=LocalContext.current
                    val sections by produceState<List<String>>(emptyList()) { value=withContext(Dispatchers.IO){context.assets.open("third-party-notices.txt").bufferedReader().use{it.readText()}.split("\n\n").flatMap{it.chunked(4096)}} }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item{Text("Dependency notices",style=MaterialTheme.typography.headlineMedium)}
                        items(sections){section->Text(section,style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun InstanceCard(row: CloneInfo, busy: Boolean, launch: () -> Unit, manage: () -> Unit, grid: Boolean) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = manage, onLongClick = manage)) {
        if(grid) Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){AppIcon(row.packageName,row.accent);Text(row.name,style=MaterialTheme.typography.titleMedium);Text(statusText(row),style=MaterialTheme.typography.bodySmall);Button(onClick=launch,enabled=!busy){Text("Launch")}}
        else Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){AppIcon(row.packageName,row.accent);Column(Modifier.weight(1f)){Text(row.name,style=MaterialTheme.typography.titleMedium);Text(statusText(row),style=MaterialTheme.typography.bodySmall)};TextButton(onClick=launch,enabled=!busy){Text("Launch")}}
    }
}
