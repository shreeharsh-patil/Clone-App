"""Separate source-only runtime experiment. Does not modify the shipping application.

Run scripts/runtime-proof/prepare_sources.py first. Only pinned reviewed source
trees are accepted. This experiment does not establish production compatibility.
"""
from pathlib import Path
import shutil, subprocess, os, base64, re, json, zipfile, hashlib

ROOT=Path(__file__).resolve().parents[1]
import runpy
runpy.run_path(str(ROOT/'scripts/runtime-proof/prepare_sources.py'))['prepare']()
P=ROOT/'.research/general-runtime-proof'
UP=ROOT/'.research/blackbox-android'
P.mkdir(parents=True,exist_ok=True)

def write(path,text):
    path=P/path; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(text,encoding='utf-8')
def copytree(source,dest):
    shutil.copytree(source,P/dest,dirs_exist_ok=True,ignore=shutil.ignore_patterns('*.a','*.so','*.jar','*.dex','.git'))
def replace_method(path,signature,body):
    text=path.read_text(encoding='utf-8'); begin=text.index('{',text.index(signature)); end=begin+1; level=1
    while level:
        if text[end]=='{': level+=1
        elif text[end]=='}': level-=1
        end+=1
    path.write_text(text[:begin]+'{\n'+body+'\n    }'+text[end:],encoding='utf-8')

copytree(UP/'Bcore/src/main','runtime/src/main')
copytree(UP/'black-reflection/src','reflection/src')
copytree(UP/'compiler/src','compiler/src')
copytree(ROOT/'.research/FreeReflection-source/library/src/main','free-reflection/src/main')
write(Path('free-reflection/src/main/AndroidManifest.xml'),'<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>')
for name,repo in [('BlackBox','blackbox-android'),('Dobby','Dobby-source'),('xDL','xDL-source'),('FreeReflection','FreeReflection-source')]:
    license=(ROOT/'.research'/repo/'LICENSE').read_text(encoding='utf-8')
    write(Path('licenses')/(name+'.txt'),license)
write(Path('source-revisions.json'),json.dumps({name:subprocess.check_output(['git','-C',str(ROOT/'.research'/repo),'rev-parse','HEAD'],text=True).strip() for name,repo in [('BlackBox','blackbox-android'),('Dobby','Dobby-source'),('xDL','xDL-source'),('FreeReflection','FreeReflection-source')]},indent=2))

# Remove unrelated automatic spoof/hiding/error suppression and telemetry paths.
core=P/'runtime/src/main/java/com/tegufy/blackbox/BlackBoxCore.java'
text=core.read_text(encoding='utf-8'); start=text.index('    static {'); end=text.index('    private ProcessType',start)
text=text[:start]+text[end:]; text=text.replace('startLogcat();','/* Local proof: no automatic log collection. */')
core.write_text(text,encoding='utf-8')
replace_method(core,'public static void installSystemHooks()', '        if(sContext!=null) Reflection.unseal(sContext);')
thread=P/'runtime/src/main/java/com/tegufy/blackbox/app/BActivityThread.java'
replace_method(thread,'private static void applyProfileSpoofConfig(', '        // No device identity changes in the compatibility experiment.')
write(Path('runtime/src/main/java/com/tegufy/blackbox/utils/LogSender.java'),'''package com.tegufy.blackbox.utils;
public class LogSender { public static String send(String chatId,java.io.File file,String caption){return "Remote log sending disabled";} }
''')
native=P/'runtime/src/main/cpp/BoxCore.cpp'
t=native.read_text(encoding='utf-8').replace('VMClassLoaderHook::init(env);','/* No class hiding hook. */').replace('VMClassLoaderHook::hideXposed();','/* No class hiding. */')
native.write_text(t,encoding='utf-8')
# No foreign prebuilt archives or unproven executable assets. JUnit is optional.
config=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/JarConfig.java'
config.write_text(config.read_text(encoding='utf-8').replace('new JarDefinition[]{EMPTY_JAR, JUNIT_JAR}','new JarDefinition[]{EMPTY_JAR}'),encoding='utf-8')

# Generate the small bootstrap dex from its accompanying Java source, rather than
# accepting the upstream's opaque base64 payload. Also generate the dummy dex asset.
sdk=Path(os.environ['ANDROID_HOME']); jdk=Path(os.environ['JAVA_HOME'])
javac_bin = str(jdk / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac'))
d8_bin = str(sdk / 'build-tools' / '36.0.0' / ('d8.bat' if os.name == 'nt' else 'd8'))
classes=P/'bootstrap-classes'; dex=P/'bootstrap-dex'; classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True)
source=P/'free-reflection/src/main/java/me/weishu/reflection/BootstrapClass.java'
subprocess.run([javac_bin,'-source','8','-target','8','-classpath',str(sdk/'platforms/android-36/android.jar'),'-d',str(classes),str(source)],check=True)
subprocess.run([d8_bin,'--min-api','33','--lib',str(sdk/'platforms/android-36/android.jar'),'--output',str(dex),str(classes/'me/weishu/reflection/BootstrapClass.class')],check=True)
reflection=P/'free-reflection/src/main/java/me/weishu/reflection/Reflection.java'
t=reflection.read_text(encoding='utf-8'); t=re.sub(r'private static final String DEX = "[^"]+";', 'private static final String DEX = "'+base64.b64encode((dex/'classes.dex').read_bytes()).decode()+'";',t)
t=t.replace('fos.write(bytes);','code.setReadOnly();\n                fos.write(bytes);')
t=t.replace('public static int unseal(Context context) {', '''private static volatile boolean localUnsealed;
    public static synchronized int unseal(Context context) {
        if(localUnsealed)return 0;''')
t=t.replace('if (exemptAll()) {','if (exemptAll()) {\n            localUnsealed=true;')
t=t.replace('if (unsealByDexFile(context)) {','if (unsealByDexFile(context)) {\n            localUnsealed=true;')
reflection.write_text(t,encoding='utf-8')
write(Path('marker/Empty.java'),'package org.parallelnest.runtimeproof; public final class Empty {}')
emptyclasses=P/'marker/classes'; emptydex=P/'marker/dex'; emptyclasses.mkdir(parents=True,exist_ok=True); emptydex.mkdir(exist_ok=True)
subprocess.run([javac_bin,'-source','8','-target','8','-d',str(emptyclasses),str(P/'marker/Empty.java')],check=True)
subprocess.run([d8_bin,'--min-api','33','--output',str(emptydex),str(emptyclasses/'org/parallelnest/runtimeproof/Empty.class')],check=True)
assets=P/'runtime/src/main/assets'; assets.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(assets/'empty.jar','w') as z: z.write(emptydex/'classes.dex','classes.dex')

# Minimize the experiment's manifest permissions and keep private proxies private.
import xml.etree.ElementTree as ET
android='{http://schemas.android.com/apk/res/android}'
manifest=P/'runtime/src/main/AndroidManifest.xml'
ET.register_namespace('android',android[1:-1]); ET.register_namespace('tools','http://schemas.android.com/tools')
tree=ET.parse(manifest); root=tree.getroot(); root.attrib.pop('package',None)
standard_permissions = {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.ACCESS_WIFI_STATE', 'android.permission.CHANGE_WIFI_STATE',
    'android.permission.FOREGROUND_SERVICE', 'android.permission.WAKE_LOCK',
    'android.permission.REORDER_TASKS', 'android.permission.CAMERA',
    'android.permission.RECORD_AUDIO', 'android.permission.MODIFY_AUDIO_SETTINGS',
    'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_MEDIA_IMAGES', 'android.permission.POST_NOTIFICATIONS',
    'android.permission.VIBRATE', 'android.permission.BLUETOOTH',
    'android.permission.BLUETOOTH_ADMIN', 'android.permission.BLUETOOTH_ADVERTISE',
    'android.permission.BLUETOOTH_CONNECT', 'android.permission.BLUETOOTH_SCAN',
    'android.permission.READ_CONTACTS', 'android.permission.WRITE_CONTACTS',
    'android.permission.READ_PHONE_STATE', 'android.permission.CALL_PHONE',
    'android.permission.QUERY_ALL_PACKAGES', 'android.permission.REQUEST_INSTALL_PACKAGES',
    'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
    'android.permission.FOREGROUND_SERVICE_MICROPHONE',
    'android.permission.FOREGROUND_SERVICE_CAMERA',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    'android.permission.FOREGROUND_SERVICE_PHONE_CALL',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
    'android.permission.NEARBY_WIFI_DEVICES',
    'android.permission.BODY_SENSORS',
    'android.permission.BODY_SENSORS_BACKGROUND'
}
for node in list(root):
    if node.tag=='uses-permission' and node.get(android+'name') not in standard_permissions: root.remove(node)
    if node.tag=='uses-feature': node.set(android+'required','false')
for app in root.findall('application'):
    app.attrib.pop(android+'usesCleartextTraffic',None)
    for node in app:
        if node.tag in {'activity','service','provider','receiver'} and node.get(android+'permission')!='android.permission.BIND_JOB_SERVICE': node.set(android+'exported','false')
tree.write(manifest,encoding='utf-8',xml_declaration=True)

write(Path('settings.gradle'),'''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name='RuntimeProof'
include ':runtime', ':reflection', ':compiler', ':free-reflection', ':host'
''')
write(Path('build.gradle'),'''plugins { id 'com.android.application' version '8.13.2' apply false; id 'com.android.library' version '8.13.2' apply false; id 'com.android.dynamic-feature' version '8.13.2' apply false }
''')
write(Path('gradle.properties'),'android.useAndroidX=true\nandroid.nonTransitiveRClass=false\norg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\n')
write(Path('reflection/build.gradle'),"plugins { id 'java-library' }; java { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }\n")
write(Path('compiler/build.gradle'),'''plugins { id 'java-library' }
java { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
dependencies { implementation project(':reflection'); implementation 'com.squareup:javapoet:1.13.0'; compileOnly 'com.google.auto.service:auto-service:1.1.1'; annotationProcessor 'com.google.auto.service:auto-service:1.1.1' }
''')
write(Path('free-reflection/build.gradle'),'''plugins { id 'com.android.library' }
android { namespace 'me.weishu.reflection'; compileSdk 36; defaultConfig { minSdk 33 }; compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 } }
''')
write(Path('runtime/build.gradle'),'''plugins { id 'com.android.library' }
android {
 namespace 'com.tegufy.blackbox'; compileSdk 36; buildToolsVersion '36.0.0'; ndkVersion '28.2.13676358'
 defaultConfig { minSdk 33; ndk { abiFilters 'arm64-v8a','x86_64' }; externalNativeBuild { cmake { arguments '-DANDROID_STL=c++_static', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'; targets 'blackbox' } } }
 buildFeatures { aidl true; buildConfig true }
 compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
 externalNativeBuild { cmake { path 'src/main/cpp/CMakeLists.txt'; version '3.22.1' } }
}
tasks.withType(JavaCompile).configureEach {
 options.encoding = 'UTF-8'
 doFirst {
  fileTree("$buildDir/generated/aidl_source_output_dir").matching { include '**/*.java' }.each { f ->
   def text=f.getText('UTF-8'); f.setText(text.replaceFirst('(?s)^/\\\\*.*?\\\\*/','/* Generated from the source AIDL. */'),'UTF-8')
  }
 }
}
dependencies {
 implementation project(':reflection'); annotationProcessor project(':compiler'); implementation project(':free-reflection')
 implementation 'com.google.android.material:material:1.12.0'; implementation 'androidx.appcompat:appcompat:1.7.1'; implementation 'com.moandjiezana.toml:toml4j:0.7.2'
}
''')
cpp=P/'runtime/src/main/cpp'
shutil.copyfile(ROOT/'.research/Dobby-source/include/dobby.h',cpp/'Dobby/dobby.h')
xdl=ROOT/'.research/xDL-source/xdl/src/main/cpp'
sources=' '.join('"'+p.as_posix()+'"' for p in xdl.glob('*.c'))
native_sources=['BoxCore.cpp','hidden_api.cpp','IO.cpp','Utils/elf_util.cpp','Hook/DexFileHook.cpp','Utils/HexDump.cpp','Hook/FileSystemHook.cpp','Hook/UnixFileSystemHook.cpp','Hook/BinderHook.cpp','Hook/BaseHook.cpp','JniHook/JniHook.cpp']
write(Path('runtime/src/main/cpp/CMakeLists.txt'),f'''cmake_minimum_required(VERSION 3.22)
project(RuntimeNative LANGUAGES C CXX ASM)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
add_subdirectory("{(ROOT/'.research/Dobby-source').as_posix()}" dobby-build)
add_library(xdl_local STATIC {sources})
target_include_directories(xdl_local PUBLIC "{xdl.as_posix()}/include" "{xdl.as_posix()}")
target_link_libraries(xdl_local log dl)
add_library(blackbox SHARED {' '.join(native_sources)})
target_include_directories(blackbox PRIVATE . "{xdl.as_posix()}/include")
target_compile_options(blackbox PRIVATE -Wno-error=format-security -fms-extensions)
target_link_options(blackbox PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(blackbox dobby_static xdl_local log android z)
''')
write(Path('host/build.gradle'),'''plugins { id 'com.android.application' }
android { namespace 'org.parallelnest.runtimeproof'; compileSdk 36; defaultConfig { applicationId 'org.parallelnest.runtimeproof'; minSdk 33; targetSdk 36; versionCode 1; versionName '0.1-experiment'; ndk { abiFilters 'arm64-v8a','x86_64' } }; compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 } }
dependencies { implementation project(':runtime'); implementation project(':free-reflection') }
''')
write(Path('host/src/main/AndroidManifest.xml'),'''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.REORDER_TASKS"/>
<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>
<application android:name=".ProofApp" android:label="Runtime Proof" android:allowBackup="false" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<activity android:name=".ProofActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
</application></manifest>
''')
write(Path('host/src/main/java/org/parallelnest/runtimeproof/ProofApp.java'),'''package org.parallelnest.runtimeproof;
import android.app.Application; import android.content.Context;
import com.tegufy.blackbox.BlackBoxCore; import com.tegufy.blackbox.app.configuration.ClientConfiguration;
public class ProofApp extends Application {
 public static Throwable error;
 @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);try {
  me.weishu.reflection.Reflection.unseal(base);
  BlackBoxCore.get().doAttachBaseContext(this,new ClientConfiguration(){ public String getHostPackageName(){return "org.parallelnest.runtimeproof";} public boolean isEnableDaemonService(){return false;} public boolean isEnableLauncherActivity(){return false;} public boolean isHideRoot(){return false;} public String getLogSenderChatId(){return null;} });
 } catch(Throwable t){error=t;android.util.Log.e("RuntimeProof","attach failed",t);} }
 @Override public void onCreate(){super.onCreate();if(error==null)try{BlackBoxCore.get().doCreate();}catch(Throwable t){error=t;android.util.Log.e("RuntimeProof","create failed",t);}}
}''')
write(Path('host/src/main/java/org/parallelnest/runtimeproof/ProofActivity.java'),'''package org.parallelnest.runtimeproof;
import android.app.Activity;import android.os.Bundle;import android.widget.*;import com.tegufy.blackbox.BlackBoxCore;
public class ProofActivity extends Activity {
 TextView status; EditText pkg; int user;
 public void onCreate(Bundle b){super.onCreate(b);user=getIntent().getIntExtra("user",10);LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(20,40,20,20);status=new TextView(this);status.setText(ProofApp.error==null?"Runtime initialized; no guest verified":ProofApp.error.toString());pkg=new EditText(this);pkg.setText(getIntent().getStringExtra("package") == null?"org.parallelnest.probe":getIntent().getStringExtra("package"));l.addView(status);l.addView(pkg);Button add=new Button(this);add.setText("Import actual APK");add.setOnClickListener(v->install());l.addView(add);Button launch=new Button(this);launch.setText("Launch guest");launch.setOnClickListener(v->launch());l.addView(launch);setContentView(l);String op=getIntent().getStringExtra("op");if(op!=null)new android.os.Handler().postDelayed(()->{if(op.equals("install"))install();else if(op.equals("launch"))launch();},1500);}
 void result(String s){android.util.Log.i("RuntimeProof",s);runOnUiThread(()->status.setText(s));}
 void install(){if(ProofApp.error!=null){result(ProofApp.error.toString());return;}String name=pkg.getText().toString();new Thread(()->{try {BlackBoxCore.get().createUser(user);com.tegufy.blackbox.entity.pm.InstallResult r=BlackBoxCore.get().installPackageAsUser(name,user);result("Import success="+r.success+" package="+r.packageName+" error="+r.msg);}catch(Throwable t){android.util.Log.e("RuntimeProof","Import failed",t);result(t.toString());}}).start();}
 void launch(){try {result("Guest launch requested: "+BlackBoxCore.get().launchApk(pkg.getText().toString(),user));}catch(Throwable t){android.util.Log.e("RuntimeProof","Launch failed",t);result(t.toString());}}
}
''')
system=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/BlackBoxSystem.java'
t=system.read_text(encoding='utf-8')
start=t.index('        List<String> preInstallPackages =')
end=t.index('        JarManager.getInstance().initializeAsync();',start)
system.write_text(t[:start]+'        // Only explicitly selected APK sets are imported.\n'+t[end:],encoding='utf-8')

def replace_hook(path, class_name, body):
    t=path.read_text(encoding='utf-8'); start=t.index('class '+class_name+' ')
    method=t.index('protected Object hook(',start)
    temporary=P/'hook-edit.tmp'; temporary.write_text(t[method:],encoding='utf-8')
    replace_method(temporary,'protected Object hook(',body)
    path.write_text(t[:method]+temporary.read_text(encoding='utf-8'),encoding='utf-8'); temporary.unlink()
replace_method(core,'private void setEssentialProperties(', '''        if(clientConfiguration==null)throw new IllegalArgumentException("Client configuration required");
        Reflection.unseal(context);''')
replace_method(P/'runtime/src/main/java/com/tegufy/blackbox/closecode/Entry.java','public static void attach()',
               '        // No external code injection in the compatibility proof.')
# Installation exceptions must not fall through to a default success result.
service=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/pm/BPackageManagerService.java'
# Report failed removal, avoid following symlinks, and snapshot package iteration.
replace_method(P/'runtime/src/main/java/com/tegufy/blackbox/utils/FileUtils.java',
    'public static int deleteDir(File dir)', '''        java.nio.file.Path root=dir.toPath();
        if(!java.nio.file.Files.exists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))return 0;
        final int[] count={0};
        try {
            java.nio.file.Files.walkFileTree(root,new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws java.io.IOException {
                    java.nio.file.Files.delete(file);count[0]++;return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path directory,java.io.IOException error)throws java.io.IOException {
                    if(error!=null)throw error;
                    java.nio.file.Files.delete(directory);count[0]++;return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return count[0];
        } catch(java.io.IOException error) { throw new java.io.UncheckedIOException("Cannot remove private runtime files",error); }''')
t=service.read_text(encoding='utf-8')
t=t.replace('''                if (i < 0) {
                    
                }''','''                if (i != 0) throw new IllegalStateException("Virtual package removal failed: " + i);''')
t=t.replace('''                    ps.save();
                }
                onPackageUninstalled''','''                    if(!ps.save())throw new IllegalStateException("Could not persist virtual package removal");
                }
                onPackageUninstalled''')
service.write_text(t,encoding='utf-8')
replace_method(service,'public void deleteUser(int userId)', '''        synchronized(mInstallLock) {
            synchronized(mPackages) {
                for(BPackageSettings ps:new java.util.ArrayList<>(mPackages.values())) {
                    uninstallPackageAsUser(ps.pkg.packageName,userId);
                }
            }
        }''')
t=service.read_text(encoding='utf-8'); start=t.index('private InstallResult installPackageAsUserLocked('); end=t.index('private PackageParser.Package parserApk(',start)
part=t[start:end].replace('} catch (Throwable t) {\n            t.printStackTrace();','} catch (Throwable t) {\n            result.installError("Import failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());\n            t.printStackTrace();')
service.write_text(t[:start]+part+t[end:],encoding='utf-8')
# Import code once; creating a second data instance must not erase shared APKs.
create=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/pm/installer/CreatePackageExecutor.java'
t=create.read_text(encoding='utf-8')
t=t.replace('FileUtils.deleteDir(BEnvironment.getAppDir(ps.pkg.packageName));','// Existing package code is preserved when adding a virtual user.')
create.write_text(t,encoding='utf-8')
shutil.copyfile(ROOT/'scripts/runtime-proof/CopyExecutor.java',
    P/'runtime/src/main/java/com/tegufy/blackbox/core/system/pm/installer/CopyExecutor.java')
t=service.read_text(encoding='utf-8')
needle='            BPackageSettings bPackageSettings = mSettings.getPackageLPw(aPackage.packageName, aPackage, option);'
assert t.count(needle)==1
t=t.replace(needle,'''            File existingCode=BEnvironment.getBaseApkDir(aPackage.packageName);
            if(existingCode.isFile() && !com.tegufy.blackbox.core.system.pm.installer.CopyExecutor.sameApk(apkFile,existingCode)) {
                return result.installError("A different APK version already has clones; in-place shared-code updates are not implemented");
            }
'''+needle)
t=t.replace('                bPackageSettings.save();','                if(!bPackageSettings.save())throw new java.io.IOException("Could not persist imported package metadata");')
service.write_text(t,encoding='utf-8')
settings=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/pm/Settings.java'
t=settings.read_text(encoding='utf-8')
t=t.replace('            FileUtils.deleteDir(app);','            // Preserve code and metadata on a transient recovery failure.')
t=t.replace('            BPackageManagerService.get().onPackageUninstalled(packageName, true, BUserHandle.USER_ALL);',
            '            // A failed read is not an uninstall request.')
settings.write_text(t,encoding='utf-8')
client=P/'runtime/src/main/java/com/tegufy/blackbox/fake/frameworks/BPackageManager.java'
replace_method(client,'public boolean isInstalled(String packageName, int userId)', '''        try {
            IBPackageManagerService service=getService();
            if(service==null)throw new IllegalStateException("Virtual package service unavailable");
            return service.isInstalled(packageName,userId);
        } catch(RemoteException e) { throw new IllegalStateException("Cannot query virtual package state",e); }''')
for method in ['stopPackage','uninstallPackageAsUser']:
    replace_method(client,'public void '+method+'(String packageName, int userId)', '''        try {
            getService().'''+method+'''(packageName,userId);
        } catch(RemoteException e) { throw new IllegalStateException("Virtual package operation failed",e); }''')
# Complete APK sets: parse every split and retain its paths across process death.
replace_method(service,'private InstallResult installPackageAsUserLocked(',
    (ROOT/'scripts/runtime-proof/install-package-method.java.txt').read_text(encoding='utf-8'))
pkg=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/pm/BPackage.java'
t=pkg.read_text(encoding='utf-8')
t=t.replace('    public String baseCodePath;', '''    public String baseCodePath;
    public String[] splitNames;
    public String[] splitCodePaths;
    public int[] splitRevisionCodes;''',1)
t=t.replace('        this.baseCodePath = aPackage.baseCodePath;', '''        this.baseCodePath = aPackage.baseCodePath;
        this.splitNames = aPackage.splitNames;
        this.splitCodePaths = aPackage.splitCodePaths;
        this.splitRevisionCodes = aPackage.splitRevisionCodes;''',1)
t=t.replace('        int N = in.readInt();', '''        int header = in.readInt();
        boolean hasSplits = header == -0x434c4e;
        int N = hasSplits ? in.readInt() : header;''',1)
t=t.replace('        this.installOption = in.readParcelable(InstallOption.class.getClassLoader());', '''        this.installOption = in.readParcelable(InstallOption.class.getClassLoader());
        if (hasSplits) {
            this.splitNames = in.createStringArray();
            this.splitCodePaths = in.createStringArray();
            this.splitRevisionCodes = in.createIntArray();
        }''',1)
start=t.index('\n    public void writeToParcel(Parcel dest, int flags) {')
brace=t.index('{',start)+1
t=t[:brace]+'\n        dest.writeInt(-0x434c4e); // Versioned format; old positive-count records remain readable.'+t[brace:]
t=t.replace('        dest.writeParcelable(this.installOption, flags);', '''        dest.writeParcelable(this.installOption, flags);
        dest.writeStringArray(this.splitNames);
        dest.writeStringArray(this.splitCodePaths);
        dest.writeIntArray(this.splitRevisionCodes);''',1)
pkg.write_text(t,encoding='utf-8')
compat=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/pm/PackageManagerCompat.java'
t=compat.read_text(encoding='utf-8').replace('        pi.packageName = p.packageName;', '''        pi.packageName = p.packageName;
        pi.splitNames = p.splitNames;
        pi.splitRevisionCodes = p.splitRevisionCodes;''',1)
compat.write_text(t,encoding='utf-8')
native_utils=P/'runtime/src/main/java/com/tegufy/blackbox/utils/NativeUtils.java'
t=native_utils.read_text(encoding='utf-8')
t=re.sub(r'            if \(libFile.exists\(\) && libFile.length\(\) == entry.getSize\(\)\) \{.*?\n            \}',
    '            // A matching length does not establish a matching native library.',t,flags=re.S)
native_utils.write_text(t,encoding='utf-8')
activity=P/'host/src/main/java/org/parallelnest/runtimeproof/ProofActivity.java'
t=activity.read_text(encoding='utf-8')
t=t.replace('BlackBoxCore.get().installPackageAsUser(name,user)',
    'BlackBoxCore.get().installPackageAsUser(new java.io.File(getPackageManager().getApplicationInfo(name,0).sourceDir),user)')
activity.write_text(t,encoding='utf-8')
stack=P/'runtime/src/main/java/com/tegufy/blackbox/core/system/am/ActivityStack.java'
t=stack.read_text(encoding='utf-8')
t=t.replace('mAms.moveTaskToFront(taskRecord.id, 0);',
    'try { mAms.moveTaskToFront(taskRecord.id, 0); } catch (Throwable t) { t.printStackTrace(); }')
t=t.replace('if (ComponentUtils.intentFilterEquals(topActivityRecord.intent, intent)) {',
    'if (topActivityRecord != null && ComponentUtils.intentFilterEquals(topActivityRecord.intent, intent)) {')
t=t.replace('return startActivityInSourceTask(intent,',
    'if (topActivityRecord == null) return startActivityInNewTaskLocked(userId, intent, activityInfo, resultTo, launchModeFlags);\n        return startActivityInSourceTask(intent,')
stack.write_text(t,encoding='utf-8')

# Bluetooth, permission, and Google Play Services runtime patches for guest applications (WhatsApp, calls, audio accessories)
env_file=P/'runtime/src/main/java/com/tegufy/blackbox/core/env/AppSystemEnv.java'
t=env_file.read_text(encoding='utf-8')
t=t.replace('sSystemPackages.add("com.google.android.webview");',
            'sSystemPackages.add("com.google.android.gms");\n        sSystemPackages.add("com.android.vending");\n        sSystemPackages.add("com.google.android.gsf");\n        sSystemPackages.add("com.google.android.play.games");\n        sSystemPackages.add("com.google.android.webview");')
env_file.write_text(t,encoding='utf-8')

bt_helper='''
    private static boolean isBluetoothPermission(String permission) {
        if (permission == null) return false;
        return permission.startsWith("android.permission.BLUETOOTH")
                || permission.startsWith("android.permission.ACCESS_")
                || permission.startsWith("android.permission.READ_MEDIA_")
                || permission.startsWith("android.permission.FOREGROUND_SERVICE")
                || permission.startsWith("android.permission.BODY_SENSORS")
                || permission.equals(android.Manifest.permission.CAMERA)
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.POST_NOTIFICATIONS")
                || permission.equals("android.permission.NEARBY_WIFI_DEVICES")
                || permission.equals("android.permission.ACTIVITY_RECOGNITION")
                || permission.equals(android.Manifest.permission.VIBRATE)
                || permission.equals(android.Manifest.permission.WAKE_LOCK)
                || permission.equals(android.Manifest.permission.READ_CONTACTS)
                || permission.equals(android.Manifest.permission.WRITE_CONTACTS)
                || permission.equals(android.Manifest.permission.READ_PHONE_STATE)
                || permission.equals(android.Manifest.permission.CALL_PHONE);
    }

    @ProxyMethod("checkUidPermission")
    public static class CheckUidPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof String) {
                        permission = (String) arg;
                        break;
                    }
                }
            }
            if (permission != null && (isAudioPermission(permission) || isBluetoothPermission(permission))) {
                Slog.d(TAG, "CheckUidPermission: Granting permission: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPermissionFlags")
    public static class GetPermissionFlags extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
'''
pm_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IPackageManagerProxy.java'
t=pm_proxy.read_text(encoding='utf-8')
t=t.replace('private static boolean isStorageOrMediaPermission',bt_helper+'\n    private static boolean isStorageOrMediaPermission')
t=t.replace('addMethodHook(new RequestPermissions());', 'addMethodHook(new RequestPermissions());\n        addMethodHook(new CheckUidPermission());\n        addMethodHook(new GetPermissionFlags());')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "SimpleAudioPermissionHook: Granting audio permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission) || isStorageOrMediaPermission(permission) || isNotificationOrXiaomiPermission(permission)) {\n                Slog.d(TAG, "SimpleAudioPermissionHook: Granting permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "CheckSelfPermission: Granting audio permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission) || isStorageOrMediaPermission(permission) || isNotificationOrXiaomiPermission(permission)) {\n                Slog.d(TAG, "CheckSelfPermission: Granting permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for audio permission: " + permission);\n                return false;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission) || isStorageOrMediaPermission(permission) || isNotificationOrXiaomiPermission(permission)) {\n                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Returning rationale for permission: " + permission);\n                return true;\n            }')

pkg_info_old='''            if ("com.android.vending".equals(packageName)) {
                return createFakeGooglePlayServicesPackageInfo();
            }'''
pkg_info_new='''            if ("com.google.android.gms".equals(packageName) || "com.android.vending".equals(packageName) || "com.google.android.gsf".equals(packageName)) {
                try {
                    PackageInfo hostPi = BlackBoxCore.getContext().getPackageManager().getPackageInfo(packageName, flags);
                    if (hostPi != null) return hostPi;
                } catch (Throwable ignored) {}
                return createFakeGooglePlayServicesPackageInfo(packageName);
            }'''
t=t.replace(pkg_info_old,pkg_info_new)

fake_info_old='''        private PackageInfo createFakeGooglePlayServicesPackageInfo() {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.packageName = "com.android.vending";
            packageInfo.versionName = "33.8.16-21";
            packageInfo.versionCode = 83381621;
            
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = "com.android.vending";
            appInfo.name = "Google Play Store";
            appInfo.flags = ApplicationInfo.FLAG_SYSTEM;
            appInfo.uid = 10001; 
            packageInfo.applicationInfo = appInfo;
            
            Slog.d(TAG, "GetPackageInfo: Providing fake Google Play Services info");
            return packageInfo;
        }'''
fake_info_new='''        private static final String GOOGLE_PLAY_SERVICES_CERT = "308202cf308201b8a0030201020204481b0a8f300d06092a864886f70d01010405003077310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964301e170d3038303530323034333633315a180f32303335303931383034333633315a3077310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430819f300d06092a864886f70d010101050003818d0030818902818100bbda23d24e5a266a8775437812108861cfbf718870ba903c726ee9594b9f2c64b5e282d1b745fd57f5c9ef8f2c3dff2607e15d86efdf5472851cf571d87198d0ceee95ea32560e2db45bb8d154ee4ab0539f150390fa8b25be256d0dcfcd129f1b4c6e94916a695e1e93c8309e44bc6a193ff7db8a9e1e2d785721e25c15077b0203010001300d06092a864886f70d01010405000381810052cf75f3a02a8eb9c0b171f11e9f2d18449c2598be6cc7c1b5a5b5123d537f26c71c13d7894a4c14c81a6c1e5554f6d3f2824df9c336b1d120a1eb95d2c2069b2d861611cfab5d49f694e99f0be828a2b535d8b8a5d3f23a854dd546a3b2a26514782bb81254460f4a8e2cb65d836b8509c250550d51f2d3485c39247385a4";

        private PackageInfo createFakeGooglePlayServicesPackageInfo(String pkg) {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.packageName = pkg;
            packageInfo.versionName = "24.08.12 (190400-610000000)";
            packageInfo.versionCode = 240812000;
            try {
                java.lang.reflect.Field f = PackageInfo.class.getField("longVersionCode");
                f.setLong(packageInfo, 240812000L);
            } catch (Throwable ignored) {}
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = pkg;
            appInfo.name = pkg;
            appInfo.flags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_INSTALLED;
            appInfo.enabled = true;
            appInfo.uid = 10001;
            packageInfo.applicationInfo = appInfo;
            try {
                android.content.pm.Signature sig = new android.content.pm.Signature(GOOGLE_PLAY_SERVICES_CERT);
                packageInfo.signatures = new android.content.pm.Signature[] { sig };
                if (BuildCompat.isPie()) {
                    android.content.pm.PackageParser.SigningDetails details = android.content.pm.PackageParser.SigningDetails.UNKNOWN;
                    black.android.content.pm.BRPackageParserSigningDetails.get(details)._set_signatures(packageInfo.signatures);
                    packageInfo.signingInfo = black.android.content.pm.BRSigningInfo.get()._new(details);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "Failed to set signatures for " + pkg + ": " + t.getMessage());
            }
            Slog.d(TAG, "GetPackageInfo: Providing fake Google Play Services info for " + pkg);
            return packageInfo;
        }'''
t=t.replace(fake_info_old,fake_info_new)

perm_grant_old='''                        if (perm != null && (perm.equals(android.Manifest.permission.RECORD_AUDIO)
                                || perm.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                                || perm.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                                || perm.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT))) {'''
perm_grant_new='''                        if (perm != null && (perm.equals(android.Manifest.permission.RECORD_AUDIO)
                                || perm.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                                || perm.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                                || perm.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                                || isBluetoothPermission(perm))) {'''
t=t.replace(perm_grant_old,perm_grant_new)

open_pkg_old='''            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }'''
open_pkg_new='''            if (AppSystemEnv.isOpenPackage(packageName)) {
                if (args.length >= 3 && args[2] instanceof Integer) {
                    args[2] = BlackBoxCore.getHostUserId();
                }
                try {
                    return method.invoke(who, args);
                } catch (Throwable t) {
                    return null;
                }
            }'''
t=t.replace(open_pkg_old,open_pkg_new)
pm_proxy.write_text(t,encoding='utf-8')

replace_hook(pm_proxy, 'GetApplicationInfo', '''            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);

            if ("com.google.android.gms".equals(packageName) || "com.android.vending".equals(packageName) || "com.google.android.gsf".equals(packageName)) {
                try {
                    ApplicationInfo hostAi = BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, flags);
                    if (hostAi != null) return hostAi;
                } catch (Throwable ignored) {}
                ApplicationInfo appInfo = new ApplicationInfo();
                appInfo.packageName = packageName;
                appInfo.name = packageName;
                appInfo.flags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_INSTALLED;
                appInfo.enabled = true;
                appInfo.uid = 10001;
                return appInfo;
            }

            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, flags, BlackBoxCore.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                if (args.length >= 3 && args[2] instanceof Integer) {
                    args[2] = BlackBoxCore.getHostUserId();
                }
                try {
                    return method.invoke(who, args);
                } catch (Throwable t) {
                    return null;
                }
            }
            return null;''')


am_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IActivityManagerProxy.java'
t=am_proxy.read_text(encoding='utf-8')
old_check_perm='''    @ProxyMethod("checkPermission")
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            String permission = (String) args[0];
            if (permission.equals(Manifest.permission.ACCOUNT_MANAGER)
                    || permission.equals(Manifest.permission.SEND_SMS)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "ActivityManager checkPermission: Granting audio permission: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "ActivityManager checkPermission: Granting storage/media permission: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            return method.invoke(who, args);
        }
    }'''

am_bt_helper='''    private static boolean isBluetoothPermission(String permission) {
        if (permission == null) return false;
        return permission.startsWith("android.permission.BLUETOOTH")
                || permission.startsWith("android.permission.ACCESS_")
                || permission.startsWith("android.permission.READ_MEDIA_")
                || permission.startsWith("android.permission.FOREGROUND_SERVICE")
                || permission.startsWith("android.permission.BODY_SENSORS")
                || permission.equals(android.Manifest.permission.CAMERA)
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.POST_NOTIFICATIONS")
                || permission.equals("android.permission.NEARBY_WIFI_DEVICES")
                || permission.equals("android.permission.ACTIVITY_RECOGNITION")
                || permission.equals(android.Manifest.permission.VIBRATE)
                || permission.equals(android.Manifest.permission.WAKE_LOCK)
                || permission.equals(android.Manifest.permission.READ_CONTACTS)
                || permission.equals(android.Manifest.permission.WRITE_CONTACTS)
                || permission.equals(android.Manifest.permission.READ_PHONE_STATE)
                || permission.equals(android.Manifest.permission.CALL_PHONE);
    }
'''

new_perm_hooks='''    @ProxyMethod("checkPermission")
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof String) {
                        permission = (String) arg;
                        break;
                    }
                }
            }
            if (permission != null && (isBluetoothPermission(permission)
                    || permission.equals(Manifest.permission.ACCOUNT_MANAGER)
                    || permission.equals(Manifest.permission.SEND_SMS)
                    || isAudioPermission(permission)
                    || isStorageOrMediaPermission(permission))) {
                return PackageManager.PERMISSION_GRANTED;
            }
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkPermissionWithToken")
    public static class CheckPermissionWithToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof String) {
                        permission = (String) arg;
                        break;
                    }
                }
            }
            if (permission != null && (isBluetoothPermission(permission)
                    || permission.equals(Manifest.permission.ACCOUNT_MANAGER)
                    || permission.equals(Manifest.permission.SEND_SMS)
                    || isAudioPermission(permission)
                    || isStorageOrMediaPermission(permission))) {
                return PackageManager.PERMISSION_GRANTED;
            }
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkUidPermission")
    public static class CheckUidPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof String) {
                        permission = (String) arg;
                        break;
                    }
                }
            }
            if (permission != null && (isBluetoothPermission(permission)
                    || permission.equals(Manifest.permission.ACCOUNT_MANAGER)
                    || permission.equals(Manifest.permission.SEND_SMS)
                    || isAudioPermission(permission)
                    || isStorageOrMediaPermission(permission))) {
                return PackageManager.PERMISSION_GRANTED;
            }
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }'''

assert t.count(old_check_perm) == 1
t=t.replace(old_check_perm, am_bt_helper + '\n' + new_perm_hooks)
t=t.replace('addMethodHook(new PkgMethodProxy("reportJunkFromApp"));',
            'addMethodHook(new PkgMethodProxy("reportJunkFromApp"));\n        addMethodHook(new CheckPermissionWithToken());\n        addMethodHook(new CheckUidPermission());')

cp_old='''                if (BuildCompat.isQ()) {
                    args[1] = BlackBoxCore.getHostPkg();
                }

                if (auth.equals("settings")
                        || auth.equals("media")
                        || auth.equals("telephony")
                        || ((String) auth).contains("com.google.android.gms")
                        || ((String) auth).contains("com.android.vending")
                        || ((String) auth).contains("com.google.android.gsf")
                        || auth.equals("com.google.android.gms.chimera")
                        || auth.equals("com.huawei.android.launcher.settings")
                        || auth.equals("com.hihonor.android.launcher.settings")) {
                    content = method.invoke(who, args);
                    ContentProviderDelegate.update(content, (String) auth);
                    return content;
                } else {
                    

                    ProviderInfo providerInfo = BlackBoxCore.getBPackageManager()
                            .resolveContentProvider(
                                    (String) auth, GET_META_DATA, BActivityThread.getUserId());
                    if (providerInfo == null) {
                        
                        return null;
                    }'''
cp_new='''                if (BuildCompat.isQ()) {
                    args[1] = BlackBoxCore.getHostPkg();
                }
                int userIdx = getUserIndex();
                if (userIdx >= 0 && userIdx < args.length) {
                    args[userIdx] = BlackBoxCore.getHostUserId();
                }

                if (auth.equals("settings")
                        || auth.equals("media")
                        || auth.equals("telephony")
                        || ((String) auth).contains("com.google.android.gms")
                        || ((String) auth).contains("com.android.vending")
                        || ((String) auth).contains("com.google.android.gsf")
                        || auth.equals("com.google.android.gms.chimera")
                        || auth.equals("com.huawei.android.launcher.settings")
                        || auth.equals("com.hihonor.android.launcher.settings")) {
                    try {
                        content = method.invoke(who, args);
                        ContentProviderDelegate.update(content, (String) auth);
                        return content;
                    } catch (Throwable t) {
                        Slog.w(TAG, "GetContentProvider: system provider " + auth + " failed: " + t.getMessage());
                        return null;
                    }
                } else {
                    ProviderInfo providerInfo = BlackBoxCore.getBPackageManager()
                            .resolveContentProvider(
                                    (String) auth, GET_META_DATA, BActivityThread.getUserId());
                    if (providerInfo == null) {
                        try {
                            content = method.invoke(who, args);
                            ContentProviderDelegate.update(content, (String) auth);
                            return content;
                        } catch (Throwable t) {
                            return null;
                        }
                    }'''
assert t.count(cp_old)==1
t=t.replace(cp_old,cp_new)
am_proxy.write_text(t,encoding='utf-8')

replace_method(am_proxy,'public static Object BindServiceCommon(', '''        if (args == null) return 0;
        try {
            if (callingPackageIndex >= 0 && callingPackageIndex < args.length && args[callingPackageIndex] instanceof String) {
                args[callingPackageIndex] = BlackBoxCore.getHostPkg();
            }
            for (int i = 5; i < args.length; i++) {
                if (args[i] instanceof Integer) {
                    int val = (Integer) args[i];
                    if (val == BActivityThread.getUserId() || val == BlackBoxCore.getUserId() || val >= 10) {
                        args[i] = BlackBoxCore.getHostUserId();
                    }
                }
            }

            Intent intent = args.length > 2 && args[2] instanceof Intent ? (Intent) args[2] : null;
            if (intent == null) {
                try {
                    return method.invoke(who, args);
                } catch (Throwable t) {
                    return 0;
                }
            }

            String resolvedType = args.length > 3 && args[3] instanceof String ? (String) args[3] : null;
            IServiceConnection connection = args.length > 4 && args[4] instanceof IServiceConnection ? (IServiceConnection) args[4] : null;

            int userId = intent.getIntExtra("_B_|_UserId", -1);
            userId = userId == -1 ? BActivityThread.getUserId() : userId;
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, 0, resolvedType, userId);

            boolean isOpen = AppSystemEnv.isOpenPackage(intent.getComponent())
                    || (intent.getPackage() != null && AppSystemEnv.isOpenPackage(intent.getPackage()));

            if (resolveInfo != null || isOpen) {
                Intent proxyIntent = BlackBoxCore.getBActivityManager().bindService(intent,
                        connection == null ? null : connection.asBinder(),
                        resolvedType,
                        userId);
                if (connection != null) {
                    if (intent.getComponent() == null && resolveInfo != null) {
                        intent.setComponent(new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
                    }
                    IServiceConnection proxy = ServiceConnectionDelegate.createProxy(connection, intent);
                    args[4] = proxy;

                    WeakReference<?> weakReference = BRLoadedApkServiceDispatcherInnerConnection.get(connection).mDispatcher();
                    if (weakReference != null) {
                        BRLoadedApkServiceDispatcher.get(weakReference.get())._set_mConnection(proxy);
                    }
                }

                if (proxyIntent != null && proxyIntent.getComponent() != null &&
                        proxyIntent.getComponent().getPackageName().equals(BlackBoxCore.getHostPkg())) {
                    int flagsIndex = getFlagsIndex(args);
                    if (flagsIndex >= 0) {
                        int flags = MethodParameterUtils.toInt(args[flagsIndex]);
                        flags &= ~Context.BIND_EXTERNAL_SERVICE;
                        args[flagsIndex] = flags;
                    }
                }
                if (callingPackageIndex >= 0 && callingPackageIndex < args.length) {
                    args[callingPackageIndex] = BlackBoxCore.getHostPkg();
                }

                if (proxyIntent != null) {
                    args[2] = proxyIntent;
                    try {
                        return method.invoke(who, args);
                    } catch (Throwable t) {
                        Slog.w(TAG, "BindServiceCommon: invoke proxyIntent failed: " + t.getMessage());
                        return 0;
                    }
                }
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "BindServiceCommon: invoke original failed: " + t.getMessage());
                return 0;
            }
        } catch (Throwable e) {
            Slog.e(TAG, "BindServiceCommon: Unexpected error", e);
            return 0;
        }''')

replace_hook(am_proxy, 'StartService', '''            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, 0, resolvedType, BActivityThread.getUserId());
            if (resolveInfo == null) {
                com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Integer && ((Integer) args[i] == BActivityThread.getUserId() || (Integer) args[i] == BlackBoxCore.getUserId() || (Integer) args[i] >= 10)) {
                        args[i] = BlackBoxCore.getHostUserId();
                    }
                }
                try {
                    return method.invoke(who, args);
                } catch (Throwable t) {
                    Slog.w(TAG, "startService host delegate failed: " + t.getMessage());
                    return null;
                }
            }

            int requireForegroundIndex = getRequireForeground();
            boolean requireForeground = false;
            if (requireForegroundIndex != -1) {
                requireForeground = (boolean) args[requireForegroundIndex];
            }
            return BlackBoxCore.getBActivityManager().startService(intent, resolvedType, requireForeground, BActivityThread.getUserId());''')

replace_hook(am_proxy, 'RegisterReceiverWithFeature', '''            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = getReceiverIndex();
            if (args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            
            if (args[getPermissionIndex()] != null) {
                args[getPermissionIndex()] = null;
            }

            boolean isSystemFilter = false;
            for (Object arg : args) {
                if (arg instanceof android.content.IntentFilter) {
                    android.content.IntentFilter f = (android.content.IntentFilter) arg;
                    for (int a = 0; a < f.countActions(); a++) {
                        String act = f.getAction(a);
                        if (act != null && (act.startsWith("android.bluetooth.") ||
                                            act.startsWith("android.intent.action.") ||
                                            act.startsWith("android.net.") ||
                                            act.startsWith("android.hardware."))) {
                            isSystemFilter = true;
                            break;
                        }
                    }
                }
            }

            if (BuildCompat.isU()) {
                int flagsIndex = args.length - 1;
                if (flagsIndex >= 0 && args[flagsIndex] instanceof Integer) {
                    int flags = (int) args[flagsIndex];
                    if (isSystemFilter) {
                        flags = (flags & ~2) | 4;
                    } else if ((flags & 2) == 0 && (flags & 4) == 0) {
                        flags |= 2;
                    }
                    args[flagsIndex] = flags;
                }
            }

            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Integer) {
                    int val = (Integer) args[i];
                    if (val == BActivityThread.getUserId() || val == BlackBoxCore.getUserId() || (val >= 10 && i < args.length - 1)) {
                        args[i] = BlackBoxCore.getHostUserId();
                    }
                }
            }

            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "registerReceiverWithFeature threw: " + t.getMessage());
                return null;
            }''')

replace_hook(am_proxy, 'RegisterReceiver', '''            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = 2;
            if (args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            int permissionIndex = 4;
            if (permissionIndex < args.length && args[permissionIndex] != null) {
                args[permissionIndex] = null;
            }

            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Integer) {
                    int val = (Integer) args[i];
                    if (val == BActivityThread.getUserId() || val == BlackBoxCore.getUserId() || (val >= 10 && i < args.length - 1)) {
                        args[i] = BlackBoxCore.getHostUserId();
                    }
                }
            }

            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "registerReceiver threw: " + t.getMessage());
                return null;
            }''')


perm_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IPermissionManagerProxy.java'
t=perm_proxy.read_text(encoding='utf-8')
t=t.replace('import com.tegufy.blackbox.fake.hook.BinderInvocationStub;',
            'import com.tegufy.blackbox.fake.hook.BinderInvocationStub;\nimport com.tegufy.blackbox.fake.hook.MethodHook;')
perm_check_hooks='''        addMethodHook("checkPermission", new MethodHook() {
            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                String permission = null;
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            permission = (String) arg;
                            break;
                        }
                    }
                }
                if (isAllowedPermission(permission)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);
                return method.invoke(who, args);
            }
        });
        addMethodHook("checkUidPermission", new MethodHook() {
            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                String permission = null;
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            permission = (String) arg;
                            break;
                        }
                    }
                }
                if (isAllowedPermission(permission)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                com.tegufy.blackbox.utils.MethodParameterUtils.replaceLastUid(args);
                return method.invoke(who, args);
            }
        });
        addMethodHook("getPermissionFlags", new MethodHook() {
            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                return 0;
            }
        });
        addMethodHook("shouldShowRequestPermissionRationale", new MethodHook() {
            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                return true;
            }
        });
'''
allowed_helper='''
    private static boolean isAllowedPermission(String permission) {
        if (permission == null) return false;
        return permission.startsWith("android.permission.BLUETOOTH")
                || permission.startsWith("android.permission.ACCESS_")
                || permission.startsWith("android.permission.READ_MEDIA_")
                || permission.startsWith("android.permission.FOREGROUND_SERVICE")
                || permission.startsWith("android.permission.BODY_SENSORS")
                || permission.equals(android.Manifest.permission.CAMERA)
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.POST_NOTIFICATIONS")
                || permission.equals("android.permission.NEARBY_WIFI_DEVICES")
                || permission.equals("android.permission.ACTIVITY_RECOGNITION")
                || permission.equals(android.Manifest.permission.VIBRATE)
                || permission.equals(android.Manifest.permission.WAKE_LOCK)
                || permission.equals(android.Manifest.permission.READ_CONTACTS)
                || permission.equals(android.Manifest.permission.WRITE_CONTACTS)
                || permission.equals(android.Manifest.permission.READ_PHONE_STATE)
                || permission.equals(android.Manifest.permission.CALL_PHONE);
    }
'''
t=t.replace('addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));', perm_check_hooks)
t=t.replace('@Override\n    public boolean isBadEnv()',allowed_helper+'\n    @Override\n    public boolean isBadEnv()')
perm_proxy.write_text(t,encoding='utf-8')

audio_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IAudioServiceProxy.java'
t=audio_proxy.read_text(encoding='utf-8')
audio_bt_hooks='''
    @ProxyMethod("startBluetoothSco")
    public static class StartBluetoothSco extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: startBluetoothSco called");
            com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("stopBluetoothSco")
    public static class StopBluetoothSco extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: stopBluetoothSco called");
            com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setBluetoothScoOn")
    public static class SetBluetoothScoOn extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: setBluetoothScoOn called");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setBluetoothA2dpOn")
    public static class SetBluetoothA2dpOn extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: setBluetoothA2dpOn called");
            return method.invoke(who, args);
        }
    }
'''
t=t.replace('    @ProxyMethod("abandonAudioFocus")',audio_bt_hooks+'\n    @ProxyMethod("abandonAudioFocus")')
audio_proxy.write_text(t,encoding='utf-8')

wifi_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IWifiManagerProxy.java'
t=wifi_proxy.read_text(encoding='utf-8')
t=t.replace('replaceSystemService(Context.WIFI_SERVICE);\n    }',
            'replaceSystemService(Context.WIFI_SERVICE);\n    }\n\n    @Override\n    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {\n        com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);\n        try {\n            return super.invoke(proxy, method, args);\n        } catch (Throwable t) {\n            if ("startScan".equals(method.getName())) return Boolean.FALSE;\n            throw t;\n        }\n    }')
wifi_proxy.write_text(t,encoding='utf-8')

wifi_scanner=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IWifiScannerProxy.java'
t=wifi_scanner.read_text(encoding='utf-8')
t=t.replace('replaceSystemService("wifiscanner");\n    }',
            'replaceSystemService("wifiscanner");\n    }\n\n    @Override\n    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {\n        com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);\n        return super.invoke(proxy, method, args);\n    }')
wifi_scanner.write_text(t,encoding='utf-8')

appops=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IAppOpsManagerProxy.java'
t=appops.read_text(encoding='utf-8')
sync_noted_helper='''
    public static Object createSyncNotedAppOp(int op, String packageName) {
        if (android.os.Build.VERSION.SDK_INT < 30) return null;
        try {
            Class<?> clazz = Class.forName("android.app.SyncNotedAppOp");
            for (java.lang.reflect.Constructor<?> ctor : clazz.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 4 && params[0] == int.class && params[1] == int.class) {
                    return ctor.newInstance(AppOpsManager.MODE_ALLOWED, op, null, packageName);
                }
                if (params.length == 2 && params[0] == int.class && params[1] == String.class) {
                    return ctor.newInstance(AppOpsManager.MODE_ALLOWED, null);
                }
                if (params.length == 1 && params[0] == int.class) {
                    return ctor.newInstance(AppOpsManager.MODE_ALLOWED);
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "createSyncNotedAppOp failed: " + t.getMessage());
        }
        return null;
    }
'''
t=t.replace('private static String getOpPublicName(int op)',sync_noted_helper+'\n    private static String getOpPublicName(int op)')

old_appops_invoke='''        if (methodName.startsWith("check") || 
            methodName.startsWith("note") || 
            methodName.startsWith("start")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }'''
new_appops_invoke='''        if (methodName.startsWith("check") || 
            methodName.startsWith("note") || 
            methodName.startsWith("start")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");
            Class<?> returnType = method.getReturnType();
            if (returnType != null && returnType.getName().contains("SyncNotedAppOp")) {
                int opCode = 0;
                if (args != null && args.length > 0 && args[0] instanceof Integer) {
                    opCode = (Integer) args[0];
                }
                return createSyncNotedAppOp(opCode, BlackBoxCore.getHostPkg());
            }
            if (returnType == int.class || returnType == Integer.class) {
                return AppOpsManager.MODE_ALLOWED;
            }
            return null;
        }'''
t=t.replace(old_appops_invoke, new_appops_invoke)

old_note_op='''    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOp: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOpNoThrow: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }'''
new_note_op='''    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOpRaw")
    public static class CheckOpRaw extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOp")
    public static class CheckOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }'''
t=t.replace(old_note_op, new_note_op)

old_note_operation='''    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps NoteOperation: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }'''
new_note_operation='''    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AppOps NoteOperation: Bypassing system check, allowing operation");
            Class<?> returnType = method.getReturnType();
            if (returnType != null && returnType.getName().contains("SyncNotedAppOp")) {
                int opCode = 0;
                if (args != null && args.length > 0 && args[0] instanceof Integer) {
                    opCode = (Integer) args[0];
                }
                return createSyncNotedAppOp(opCode, BlackBoxCore.getHostPkg());
            }
            return AppOpsManager.MODE_ALLOWED;
        }
    }'''
t=t.replace(old_note_operation, new_note_operation)
appops.write_text(t,encoding='utf-8')

act_common=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/ActivityManagerCommonProxy.java'
t=act_common.read_text(encoding='utf-8')
needle_perm='if (dataString != null && dataString.equals("package:" + BActivityThread.getAppPackageName())) {\n                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));\n            }'
replace_perm=needle_perm+'''
            if (android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(intent.getAction())
                    || "android.settings.APPLICATION_DETAILS_SETTINGS".equals(intent.getAction())) {
                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));
            }
            if ("android.content.pm.action.REQUEST_PERMISSIONS".equals(intent.getAction())) {
                final String[] perms = intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES");
                final IBinder rTo = StartActivityCompat.getResultTo(args);
                final String rWho = StartActivityCompat.getResultWho(args);
                final int reqCode = StartActivityCompat.getRequestCode(args);
                if (perms != null && perms.length > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                android.app.Activity activity = null;
                                if (rTo != null) {
                                    activity = BActivityThread.getActivityByToken(rTo);
                                }
                                if (activity == null) {
                                    java.util.Map<IBinder, Object> acts = black.android.app.BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
                                    if (acts != null && !acts.isEmpty()) {
                                        for (Object rec : acts.values()) {
                                            android.app.Activity a = black.android.app.BRActivityThreadActivityClientRecord.get(rec).activity();
                                            if (a != null && !a.isFinishing()) {
                                                activity = a;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (activity != null) {
                                    int[] grantResults = new int[perms.length];
                                    for (int i = 0; i < perms.length; i++) {
                                        grantResults[i] = android.content.pm.PackageManager.PERMISSION_GRANTED;
                                    }
                                    Intent resultIntent = new Intent();
                                    resultIntent.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", perms);
                                    resultIntent.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", grantResults);

                                    try {
                                        Method dispatch = android.app.Activity.class.getDeclaredMethod("dispatchActivityResult", String.class, int.class, int.class, Intent.class);
                                        dispatch.setAccessible(true);
                                        dispatch.invoke(activity, rWho, reqCode, android.app.Activity.RESULT_OK, resultIntent);
                                    } catch (Throwable t) {
                                        try {
                                            Method onReq = android.app.Activity.class.getDeclaredMethod("onRequestPermissionsResult", int.class, String[].class, int[].class);
                                            onReq.setAccessible(true);
                                            onReq.invoke(activity, reqCode, perms, grantResults);
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            } catch (Throwable t) {
                                Slog.w(TAG, "Failed to dispatch granted permission result: " + t.getMessage());
                            }
                        }
                    });
                    return 0;
                }
            }
            if ("com.google.android.gms.availability.ACTION_RESOLVE_ERROR".equals(intent.getAction())) {
                Slog.d(TAG, "Blocked GMS ACTION_RESOLVE_ERROR");
                return 0;
            }
            ComponentName gmsComp = intent.getComponent();
            if (gmsComp != null && "com.google.android.gms".equals(gmsComp.getPackageName()) && gmsComp.getClassName().contains("Error")) {
                Slog.d(TAG, "Blocked GMS error dialog: " + gmsComp.getClassName());
                return 0;
            }'''
assert t.count(needle_perm)==1
act_common.write_text(t.replace(needle_perm,replace_perm),encoding='utf-8')

notif_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/INotificationManagerProxy.java'
t=notif_proxy.read_text(encoding='utf-8')
notif_enqueue_old='''            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            Notification notification = MethodParameterUtils.getFirstParam(args, Notification.class);
            BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
            return 0;'''
notif_enqueue_new='''            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            Notification notification = MethodParameterUtils.getFirstParam(args, Notification.class);
            if (tag != null && (tag.contains("GoogleApiAvailability") || tag.contains("GmsError") || tag.contains("GooglePlayServices") || tag.contains("com.google.android.gms"))) {
                return 0;
            }
            if (notification != null && notification.extras != null) {
                CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                if ((title != null && (title.toString().contains("Google Play services") || title.toString().contains("Google Play Services")))
                        || (text != null && (text.toString().contains("Google Play services") || text.toString().contains("Google Play Services")))) {
                    return 0;
                }
            }
            BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
            return 0;'''
assert t.count(notif_enqueue_old)==1
notif_proxy.write_text(t.replace(notif_enqueue_old,notif_enqueue_new),encoding='utf-8')

crash_handler_code='''package com.tegufy.blackbox.core;

import com.tegufy.blackbox.BlackBoxCore;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    private static boolean isGooglePlayServicesCrash(Throwable throwable) {
        if (throwable == null) return false;
        Throwable curr = throwable;
        while (curr != null) {
            String msg = curr.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("google play services") ||
                    lower.contains("googleplayservices") ||
                    lower.contains("googleapiavailability") ||
                    lower.contains("chimera") ||
                    lower.contains("dynamitemodule") ||
                    lower.contains("firebase") ||
                    lower.contains("com.google.android.gms")) {
                    return true;
                }
            }
            for (StackTraceElement elem : curr.getStackTrace()) {
                String cls = elem.getClassName();
                if (cls.contains("com.google.android.gms") ||
                    cls.contains("GoogleApiAvailability") ||
                    cls.contains("GooglePlayServicesUtil") ||
                    cls.contains("com.google.firebase")) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (isGooglePlayServicesCrash(e)) {
            android.util.Log.w("CrashHandler", "Suppressed uncaught Google Play Services crash on thread " + t.getName(), e);
            return;
        }
        if (BlackBoxCore.get().getExceptionHandler() != null) {
            BlackBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }
}
'''
write(Path('runtime/src/main/java/com/tegufy/blackbox/core/CrashHandler.java'),crash_handler_code)

gms_proxy_code='''package com.tegufy.blackbox.fake.service;

import android.content.Context;
import java.lang.reflect.Method;
import com.tegufy.blackbox.BlackBoxCore;
import com.tegufy.blackbox.fake.hook.ClassInvocationStub;
import com.tegufy.blackbox.fake.hook.MethodHook;
import com.tegufy.blackbox.fake.hook.ProxyMethod;
import com.tegufy.blackbox.utils.Slog;

/**
 * Proxy for Google Play Services availability checks.
 * Ensures guest applications (e.g. WhatsApp) see GMS as available and never show error dialogs.
 */
public class GooglePlayServicesProxy extends ClassInvocationStub {
    public static final String TAG = "GooglePlayServicesProxy";
    private static final int SUCCESS = 0;

    public GooglePlayServicesProxy() {
        super();
        onlyProxy(true);
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isGooglePlayServicesAvailable")
    public static class IsGooglePlayServicesAvailable extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return SUCCESS;
        }
    }

    @ProxyMethod("getErrorString")
    public static class GetErrorString extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("isUserRecoverableError")
    public static class IsUserRecoverableError extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("getErrorResolutionIntent")
    public static class GetErrorResolutionIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("showErrorNotification")
    public static class ShowErrorNotification extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("showErrorDialogFragment")
    public static class ShowErrorDialogFragment extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("getApkVersion")
    public static class GetApkVersion extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 240812000;
        }
    }

    @ProxyMethod("getClientVersion")
    public static class GetClientVersion extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 240812000;
        }
    }
}
'''
write(Path('runtime/src/main/java/com/tegufy/blackbox/fake/service/GooglePlayServicesProxy.java'),gms_proxy_code)

bt_mgr_code='''package com.tegufy.blackbox.fake.service;

import android.bluetooth.BluetoothAdapter;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import black.android.os.BRServiceManager;
import com.tegufy.blackbox.BlackBoxCore;
import com.tegufy.blackbox.app.BActivityThread;
import com.tegufy.blackbox.fake.hook.BinderInvocationStub;
import com.tegufy.blackbox.fake.hook.MethodHook;
import com.tegufy.blackbox.fake.hook.ProxyMethod;
import com.tegufy.blackbox.utils.MethodParameterUtils;
import com.tegufy.blackbox.utils.Slog;

public class IBluetoothManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IBluetoothManagerProxy";

    public IBluetoothManagerProxy() {
        super(BRServiceManager.get().getService("bluetooth_manager"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("bluetooth_manager");
        if (binder == null) {
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("android.bluetooth.IBluetoothManager$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
            return asInterfaceMethod.invoke(null, binder);
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to get IBluetoothManager interface: " + t.getMessage(), t);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("bluetooth_manager");
        fixBluetoothAdapterAttributionSource();
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    public static Field findField(Class<?> clazz, String name) {
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            try {
                Field f = curr.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {}
            curr = curr.getSuperclass();
        }
        return null;
    }

    public static boolean isHostBluetoothEnabled() {
        try {
            int on = android.provider.Settings.Global.getInt(
                BlackBoxCore.getContext().getContentResolver(), "bluetooth_on", 0);
            if (on != 0) return true;
        } catch (Throwable ignored) {}
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) BlackBoxCore.getContext().getSystemService(android.content.Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter() != null) {
                return bm.getAdapter().isEnabled();
            }
        } catch (Throwable ignored) {}
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) return adapter.isEnabled();
        } catch (Throwable ignored) {}
        return false;
    }

    public static int getHostBluetoothState() {
        try {
            int on = android.provider.Settings.Global.getInt(
                BlackBoxCore.getContext().getContentResolver(), "bluetooth_on", 0);
            if (on != 0) return BluetoothAdapter.STATE_ON;
        } catch (Throwable ignored) {}
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) BlackBoxCore.getContext().getSystemService(android.content.Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter() != null) {
                int s = bm.getAdapter().getState();
                if (s != BluetoothAdapter.STATE_OFF) return s;
            }
        } catch (Throwable ignored) {}
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) return adapter.getState();
        } catch (Throwable ignored) {}
        return isHostBluetoothEnabled() ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
    }

    public static void fixBluetoothAdapterAttributionSource() {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                Field f = findField(BluetoothAdapter.class, "mAttributionSource");
                if (f != null) {
                    Object hostSource = BlackBoxCore.getContext().getAttributionSource();
                    if (hostSource != null) {
                        f.set(adapter, hostSource);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void sanitizeBluetoothArgs(Object[] args) {
        if (args == null) return;
        fixBluetoothAdapterAttributionSource();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            String clsName = arg.getClass().getName();
            if (clsName.equals("android.content.AttributionSource")) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    try {
                        Object hostSource = BlackBoxCore.getContext().getAttributionSource();
                        if (hostSource != null) {
                            args[i] = hostSource;
                            continue;
                        }
                    } catch (Throwable ignored) {}
                }
                fixAttributionSource(arg);
            } else if (clsName.equals("android.os.WorkSource")) {
                args[i] = null;
            } else if (arg instanceof Object[]) {
                sanitizeBluetoothArgs((Object[]) arg);
            } else if (arg instanceof java.util.List) {
                java.util.List list = (java.util.List) arg;
                for (int j = 0; j < list.size(); j++) {
                    Object item = list.get(j);
                    if (item instanceof String) {
                        String str = (String) item;
                        String guestPkg = BActivityThread.getAppPackageName();
                        if ((guestPkg != null && guestPkg.equals(str)) || BlackBoxCore.get().isInstalled(str, BlackBoxCore.getUserId())) {
                            try {
                                list.set(j, BlackBoxCore.getHostPkg());
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } else if (arg instanceof String) {
                String str = (String) arg;
                String guestPkg = BActivityThread.getAppPackageName();
                if ((guestPkg != null && guestPkg.equals(str)) || BlackBoxCore.get().isInstalled(str, BlackBoxCore.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                }
            }
        }
    }

    public static void fixAttributionSource(Object attributionSource) {
        if (attributionSource == null) return;
        try {
            Field stateField = findField(attributionSource.getClass(), "mAttributionSourceState");
            if (stateField != null) {
                Object state = stateField.get(attributionSource);
                if (state != null) {
                    fixAttributionSourceState(state);
                }
            }
        } catch (Throwable ignored) {}
        try {
            Field pkgField = findField(attributionSource.getClass(), "mPackageName");
            if (pkgField != null) pkgField.set(attributionSource, BlackBoxCore.getHostPkg());
        } catch (Throwable ignored) {}
        try {
            Field uidField = findField(attributionSource.getClass(), "mUid");
            if (uidField != null) uidField.set(attributionSource, BlackBoxCore.getHostUid());
        } catch (Throwable ignored) {}
    }

    public static void fixAttributionSourceState(Object state) {
        if (state == null) return;
        try {
            Class<?> stateClass = state.getClass();
            Field uidField = findField(stateClass, "uid");
            if (uidField != null) {
                try { uidField.setInt(state, BlackBoxCore.getHostUid()); } catch (Throwable ignored) {}
            }

            Field pkgField = findField(stateClass, "packageName");
            if (pkgField != null) {
                try { pkgField.set(state, BlackBoxCore.getHostPkg()); } catch (Throwable ignored) {}
            }

            Field pidField = findField(stateClass, "pid");
            if (pidField != null) {
                try { pidField.setInt(state, android.os.Process.myPid()); } catch (Throwable ignored) {}
            }

            Field deviceIdField = findField(stateClass, "deviceId");
            if (deviceIdField != null) {
                try { deviceIdField.setInt(state, 0); } catch (Throwable ignored) {}
            }

            Field nextField = findField(stateClass, "next");
            if (nextField != null) {
                try {
                    Object nextArray = nextField.get(state);
                    if (nextArray instanceof Object[]) {
                        for (Object nextState : (Object[]) nextArray) {
                            fixAttributionSourceState(nextState);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to fix AttributionSourceState: " + t.getMessage());
        }
    }

    public static IBinder createBluetoothBinderProxy(final IBinder rawBinder) {
        if (rawBinder == null) return null;
        if (Proxy.isProxyClass(rawBinder.getClass())) {
            return rawBinder;
        }
        return (IBinder) Proxy.newProxyInstance(rawBinder.getClass().getClassLoader(), new Class<?>[]{IBinder.class}, new InvocationHandler() {
            private Object mIBluetoothProxyInstance = null;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("queryLocalInterface".equals(name) && args != null && args.length == 1) {
                    String descriptor = (String) args[0];
                    if ("android.bluetooth.IBluetooth".equals(descriptor)) {
                        if (mIBluetoothProxyInstance == null) {
                            try {
                                Class<?> stubClass = Class.forName("android.bluetooth.IBluetooth$Stub");
                                Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
                                Object realIface = asInterfaceMethod.invoke(null, rawBinder);
                                mIBluetoothProxyInstance = createBluetoothProxy(realIface);
                            } catch (Throwable t) {
                                Slog.w(TAG, "Failed to create IBluetooth proxy from binder: " + t.getMessage());
                            }
                        }
                        if (mIBluetoothProxyInstance != null) {
                            return mIBluetoothProxyInstance;
                        }
                    }
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0] || rawBinder.equals(args[0]);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return rawBinder.hashCode();
                }
                if ("toString".equals(name) && (args == null || args.length == 0)) {
                    return "IBluetoothBinderProxy$" + rawBinder.toString();
                }
                return method.invoke(rawBinder, args);
            }
        });
    }

    public static IBinder createBluetoothScanBinderProxy(final IBinder rawBinder) {
        if (rawBinder == null) return null;
        if (Proxy.isProxyClass(rawBinder.getClass())) return rawBinder;
        return (IBinder) Proxy.newProxyInstance(rawBinder.getClass().getClassLoader(), new Class<?>[]{IBinder.class}, new InvocationHandler() {
            private Object mProxyInstance = null;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("queryLocalInterface".equals(name) && args != null && args.length == 1) {
                    String descriptor = (String) args[0];
                    if ("android.bluetooth.IBluetoothScan".equals(descriptor)) {
                        if (mProxyInstance == null) {
                            try {
                                Class<?> stubClass = Class.forName("android.bluetooth.IBluetoothScan$Stub");
                                Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
                                Object realIface = asInterfaceMethod.invoke(null, rawBinder);
                                mProxyInstance = createBluetoothScanProxy(realIface);
                            } catch (Throwable t) {
                                Slog.w(TAG, "Failed to create IBluetoothScan proxy from binder: " + t.getMessage());
                            }
                        }
                        if (mProxyInstance != null) {
                            return mProxyInstance;
                        }
                    }
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0] || rawBinder.equals(args[0]);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return rawBinder.hashCode();
                }
                if ("toString".equals(name) && (args == null || args.length == 0)) {
                    return "IBluetoothScanBinderProxy$" + rawBinder.toString();
                }
                return method.invoke(rawBinder, args);
            }
        });
    }

    public static Object createBluetoothScanProxy(final Object target) {
        if (target == null) return null;
        Class<?>[] interfaces = MethodParameterUtils.getAllInterface(target.getClass());
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("asBinder".equals(name)) {
                    if (target instanceof IInterface) {
                        return createBluetoothScanBinderProxy(((IInterface) target).asBinder());
                    }
                    if (target instanceof IBinder) {
                        return createBluetoothScanBinderProxy((IBinder) target);
                    }
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0] || target.equals(args[0]);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return target.hashCode();
                }
                if ("toString".equals(name) && (args == null || args.length == 0)) {
                    return "IBluetoothScanProxy$" + target.toString();
                }

                sanitizeBluetoothArgs(args);

                try {
                    return method.invoke(target, args);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    Slog.w(TAG, "IBluetoothScan." + name + " threw: " + cause.getMessage());
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
                    if (returnType == int.class || returnType == Integer.class) return 0;
                    if (returnType.isArray()) return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                    if (List.class.isAssignableFrom(returnType)) return Collections.emptyList();
                    if (Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
                    return null;
                }
            }
        });
    }

    public static IBinder createBluetoothGattBinderProxy(final IBinder rawBinder) {
        if (rawBinder == null) return null;
        if (Proxy.isProxyClass(rawBinder.getClass())) return rawBinder;
        return (IBinder) Proxy.newProxyInstance(rawBinder.getClass().getClassLoader(), new Class<?>[]{IBinder.class}, new InvocationHandler() {
            private Object mProxyInstance = null;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("queryLocalInterface".equals(name) && args != null && args.length == 1) {
                    String descriptor = (String) args[0];
                    if ("android.bluetooth.IBluetoothGatt".equals(descriptor)) {
                        if (mProxyInstance == null) {
                            try {
                                Class<?> stubClass = Class.forName("android.bluetooth.IBluetoothGatt$Stub");
                                Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
                                Object realIface = asInterfaceMethod.invoke(null, rawBinder);
                                mProxyInstance = createBluetoothGattProxy(realIface);
                            } catch (Throwable t) {
                                Slog.w(TAG, "Failed to create IBluetoothGatt proxy from binder: " + t.getMessage());
                            }
                        }
                        if (mProxyInstance != null) {
                            return mProxyInstance;
                        }
                    }
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0] || rawBinder.equals(args[0]);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return rawBinder.hashCode();
                }
                if ("toString".equals(name) && (args == null || args.length == 0)) {
                    return "IBluetoothGattBinderProxy$" + rawBinder.toString();
                }
                return method.invoke(rawBinder, args);
            }
        });
    }

    public static Object createBluetoothProxy(final Object target) {
        if (target == null) return null;
        Class<?>[] interfaces = MethodParameterUtils.getAllInterface(target.getClass());
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("asBinder".equals(name)) {
                    if (target instanceof IInterface) {
                        return createBluetoothBinderProxy(((IInterface) target).asBinder());
                    }
                    if (target instanceof IBinder) {
                        return createBluetoothBinderProxy((IBinder) target);
                    }
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0] || target.equals(args[0]);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return target.hashCode();
                }
                if ("toString".equals(name) && (args == null || args.length == 0)) {
                    return "IBluetoothProxy$" + target.toString();
                }

                sanitizeBluetoothArgs(args);

                try {
                    if ("getBluetoothScan".equals(name)) {
                        try {
                            Object result = method.invoke(target, args);
                            if (result instanceof IBinder) return createBluetoothScanBinderProxy((IBinder) result);
                            if (result != null) return createBluetoothScanProxy(result);
                        } catch (Throwable t) {
                            Slog.w(TAG, "IBluetooth.getBluetoothScan threw: " + t.getMessage());
                        }
                        return null;
                    }
                    if ("getBluetoothGatt".equals(name)) {
                        try {
                            Object result = method.invoke(target, args);
                            if (result instanceof IBinder) return createBluetoothGattBinderProxy((IBinder) result);
                            if (result != null) return createBluetoothGattProxy(result);
                        } catch (Throwable t) {
                            Slog.w(TAG, "IBluetooth.getBluetoothGatt threw: " + t.getMessage());
                        }
                        return null;
                    }

                    Object result = method.invoke(target, args);
                    if ("isEnabled".equals(name) || "isLeEnabled".equals(name)) {
                        if (result instanceof Boolean && (Boolean) result) return true;
                        if (isHostBluetoothEnabled()) return true;
                    }
                    if ("getState".equals(name) || "getLeState".equals(name)) {
                        if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) return result;
                        int hostState = getHostBluetoothState();
                        if (hostState != BluetoothAdapter.STATE_OFF) return hostState;
                    }
                    if ("isMultipleAdvertisementSupported".equals(name) ||
                        "isPeripheralModeSupported".equals(name) ||
                        "isOffloadedFilteringSupported".equals(name) ||
                        "isOffloadedScanBatchingSupported".equals(name) ||
                        "isLe2MPhySupported".equals(name) ||
                        "isLeCodedPhySupported".equals(name) ||
                        "isLeExtendedAdvertisingSupported".equals(name) ||
                        "isLePeriodicAdvertisingSupported".equals(name)) {
                        if (result instanceof Boolean && (Boolean) result) return true;
                        if (isHostBluetoothEnabled()) return true;
                    }
                    if ("getBondedDevices".equals(name) || "getConnectedDevices".equals(name)) {
                        if (result == null) {
                            Class<?> returnType = method.getReturnType();
                            if (returnType.isArray()) return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                            if (Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
                            return Collections.emptyList();
                        }
                    }
                    return result;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    Slog.w(TAG, "IBluetooth." + name + " threw exception: " + cause.getMessage());
                    if ("isEnabled".equals(name) || "isLeEnabled".equals(name)) {
                        return isHostBluetoothEnabled();
                    }
                    if ("getState".equals(name) || "getLeState".equals(name)) {
                        return getHostBluetoothState();
                    }
                    if ("isMultipleAdvertisementSupported".equals(name) ||
                        "isPeripheralModeSupported".equals(name) ||
                        "isOffloadedFilteringSupported".equals(name) ||
                        "isOffloadedScanBatchingSupported".equals(name) ||
                        "isLe2MPhySupported".equals(name) ||
                        "isLeCodedPhySupported".equals(name) ||
                        "isLeExtendedAdvertisingSupported".equals(name) ||
                        "isLePeriodicAdvertisingSupported".equals(name)) {
                        return isHostBluetoothEnabled();
                    }
                    if ("getBondedDevices".equals(name) || "getConnectedDevices".equals(name)) {
                        Class<?> returnType = method.getReturnType();
                        if (returnType.isArray()) {
                            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                        }
                        if (Set.class.isAssignableFrom(returnType)) {
                            return Collections.emptySet();
                        }
                        return Collections.emptyList();
                    }
                    if ("isDiscovering".equals(name)) return Boolean.FALSE;
                    if ("startDiscovery".equals(name)) return Boolean.FALSE;
                    if ("cancelDiscovery".equals(name)) return Boolean.TRUE;
                    if ("getAddress".equals(name)) return "02:00:00:00:00:00";
                    if ("getName".equals(name)) return "Bluetooth";
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
                    if (returnType == int.class || returnType == Integer.class) return 0;
                    return null;
                }
            }
        });
    }

    public static Object createBluetoothGattProxy(final Object target) {
        if (target == null) return null;
        Class<?>[] interfaces = MethodParameterUtils.getAllInterface(target.getClass());
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("asBinder".equals(name)) {
                    if (target instanceof IInterface) {
                        return createBluetoothGattBinderProxy(((IInterface) target).asBinder());
                    }
                    if (target instanceof IBinder) {
                        return createBluetoothGattBinderProxy((IBinder) target);
                    }
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0] || target.equals(args[0]);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                    return target.hashCode();
                }
                if ("toString".equals(name) && (args == null || args.length == 0)) {
                    return "IBluetoothGattProxy$" + target.toString();
                }

                sanitizeBluetoothArgs(args);

                try {
                    return method.invoke(target, args);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    Slog.w(TAG, "IBluetoothGatt." + name + " threw: " + cause.getMessage());
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
                    if (returnType == int.class || returnType == Integer.class) return 0;
                    if (returnType.isArray()) return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                    if (List.class.isAssignableFrom(returnType)) return Collections.emptyList();
                    if (Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
                    return null;
                }
            }
        });
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        sanitizeBluetoothArgs(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String name = method.getName();
            Slog.w(TAG, "IBluetoothManager." + name + " threw: " + cause.getMessage());
            if ("isEnabled".equals(name)) {
                return isHostBluetoothEnabled();
            }
            if ("getState".equals(name)) {
                return getHostBluetoothState();
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
            if (returnType == int.class || returnType == Integer.class) return 0;
            return null;
        }
    }

    @ProxyMethod("registerAdapter")
    public static class RegisterAdapter extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] != null) {
                final Object origCallback = args[0];
                Class<?>[] ifaces = MethodParameterUtils.getAllInterface(origCallback.getClass());
                args[0] = Proxy.newProxyInstance(origCallback.getClass().getClassLoader(), ifaces, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] mArgs) throws Throwable {
                        if ("onBluetoothServiceUp".equals(m.getName()) && mArgs != null && mArgs.length > 0 && mArgs[0] instanceof IBinder) {
                            mArgs[0] = createBluetoothBinderProxy((IBinder) mArgs[0]);
                        }
                        return m.invoke(origCallback, mArgs);
                    }
                });
            }
            sanitizeBluetoothArgs(args);
            Object result = null;
            try {
                result = method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "registerAdapter threw: " + t.getMessage());
            }
            if (result != null) {
                return createBluetoothProxy(result);
            }
            return null;
        }
    }

    @ProxyMethod("getBluetoothGatt")
    public static class GetBluetoothGatt extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            Object result = null;
            try {
                result = method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "getBluetoothGatt threw: " + t.getMessage());
            }
            if (result instanceof IBinder) {
                return createBluetoothGattBinderProxy((IBinder) result);
            }
            if (result != null) {
                return createBluetoothGattProxy(result);
            }
            return null;
        }
    }

    @ProxyMethod("bindBluetoothProfileService")
    public static class BindBluetoothProfileService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("unbindBluetoothProfileService")
    public static class UnbindBluetoothProfileService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("isEnabled")
    public static class IsEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            } catch (Throwable t) {
                Slog.w(TAG, "IBluetoothManager.isEnabled threw: " + t.getMessage());
            }
            return isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("getState")
    public static class GetState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) {
                    return result;
                }
            } catch (Throwable t) {
                Slog.w(TAG, "IBluetoothManager.getState threw: " + t.getMessage());
            }
            int hostState = getHostBluetoothState();
            if (hostState != BluetoothAdapter.STATE_OFF) return hostState;
            return BluetoothAdapter.STATE_OFF;
        }
    }

    @ProxyMethod("isMultipleAdvertisementSupported")
    public static class IsMultipleAdvertisementSupported extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) return true;
            } catch (Throwable ignored) {}
            return isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("isPeripheralModeSupported")
    public static class IsPeripheralModeSupported extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) return true;
            } catch (Throwable ignored) {}
            return isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("isLeEnabled")
    public static class IsLeEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) return true;
            } catch (Throwable ignored) {}
            return isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("getLeState")
    public static class GetLeState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) return result;
            } catch (Throwable ignored) {}
            int hostState = getHostBluetoothState();
            if (hostState != BluetoothAdapter.STATE_OFF) return hostState;
            return BluetoothAdapter.STATE_OFF;
        }
    }

    @ProxyMethod("enable")
    public static class Enable extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "IBluetoothManager.enable threw: " + t.getMessage());
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) return adapter.enable();
                } catch (Throwable ignored) {}
                return false;
            }
        }
    }

    @ProxyMethod("disable")
    public static class Disable extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sanitizeBluetoothArgs(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "IBluetoothManager.disable threw: " + t.getMessage());
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) return adapter.disable();
                } catch (Throwable ignored) {}
                return false;
            }
        }
    }
}
'''
write(Path('runtime/src/main/java/com/tegufy/blackbox/fake/service/IBluetoothManagerProxy.java'),bt_mgr_code)

bt_proxy_code='''package com.tegufy.blackbox.fake.service;

import android.bluetooth.BluetoothAdapter;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import black.android.os.BRServiceManager;
import com.tegufy.blackbox.fake.hook.BinderInvocationStub;
import com.tegufy.blackbox.fake.hook.MethodHook;
import com.tegufy.blackbox.fake.hook.ProxyMethod;
import com.tegufy.blackbox.utils.Slog;

public class IBluetoothProxy extends BinderInvocationStub {
    public static final String TAG = "IBluetoothProxy";

    public IBluetoothProxy() {
        super(BRServiceManager.get().getService("bluetooth"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("bluetooth");
        if (binder == null) {
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("android.bluetooth.IBluetooth$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
            return asInterfaceMethod.invoke(null, binder);
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to get IBluetooth interface: " + t.getMessage(), t);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("bluetooth");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
        try {
            if ("getBluetoothScan".equals(method.getName())) {
                Object result = super.invoke(proxy, method, args);
                if (result instanceof IBinder) return IBluetoothManagerProxy.createBluetoothScanBinderProxy((IBinder) result);
                if (result != null) return IBluetoothManagerProxy.createBluetoothScanProxy(result);
                return null;
            }
            if ("getBluetoothGatt".equals(method.getName())) {
                Object result = super.invoke(proxy, method, args);
                if (result instanceof IBinder) return IBluetoothManagerProxy.createBluetoothGattBinderProxy((IBinder) result);
                if (result != null) return IBluetoothManagerProxy.createBluetoothGattProxy(result);
                return null;
            }
            Object result = super.invoke(proxy, method, args);
            String name = method.getName();
            if ("isEnabled".equals(name) || "isLeEnabled".equals(name)) {
                if (result instanceof Boolean && (Boolean) result) return true;
                if (IBluetoothManagerProxy.isHostBluetoothEnabled()) return true;
            }
            if ("getState".equals(name) || "getLeState".equals(name)) {
                if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) return result;
                int hostState = IBluetoothManagerProxy.getHostBluetoothState();
                if (hostState != BluetoothAdapter.STATE_OFF) return hostState;
            }
            if ("isMultipleAdvertisementSupported".equals(name) ||
                "isPeripheralModeSupported".equals(name) ||
                "isOffloadedFilteringSupported".equals(name) ||
                "isOffloadedScanBatchingSupported".equals(name) ||
                "isLe2MPhySupported".equals(name) ||
                "isLeCodedPhySupported".equals(name) ||
                "isLeExtendedAdvertisingSupported".equals(name) ||
                "isLePeriodicAdvertisingSupported".equals(name)) {
                if (result instanceof Boolean && (Boolean) result) return true;
                if (IBluetoothManagerProxy.isHostBluetoothEnabled()) return true;
            }
            if (("getBondedDevices".equals(name) || "getConnectedDevices".equals(name)) && result == null) {
                Class<?> returnType = method.getReturnType();
                if (returnType.isArray()) return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                if (Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
                return Collections.emptyList();
            }
            return result;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String name = method.getName();
            Slog.w(TAG, "IBluetooth." + name + " threw: " + cause.getMessage());
            if ("isEnabled".equals(name) || "isLeEnabled".equals(name)) {
                return IBluetoothManagerProxy.isHostBluetoothEnabled();
            }
            if ("getState".equals(name) || "getLeState".equals(name)) {
                return IBluetoothManagerProxy.getHostBluetoothState();
            }
            if ("isMultipleAdvertisementSupported".equals(name) ||
                "isPeripheralModeSupported".equals(name) ||
                "isOffloadedFilteringSupported".equals(name) ||
                "isOffloadedScanBatchingSupported".equals(name) ||
                "isLe2MPhySupported".equals(name) ||
                "isLeCodedPhySupported".equals(name) ||
                "isLeExtendedAdvertisingSupported".equals(name) ||
                "isLePeriodicAdvertisingSupported".equals(name)) {
                return IBluetoothManagerProxy.isHostBluetoothEnabled();
            }
            if ("getBondedDevices".equals(name) || "getConnectedDevices".equals(name)) {
                Class<?> returnType = method.getReturnType();
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                if (Set.class.isAssignableFrom(returnType)) {
                    return Collections.emptySet();
                }
                return Collections.emptyList();
            }
            if ("isDiscovering".equals(name)) return Boolean.FALSE;
            if ("startDiscovery".equals(name)) return Boolean.FALSE;
            if ("cancelDiscovery".equals(name)) return Boolean.TRUE;
            if ("getAddress".equals(name)) return "02:00:00:00:00:00";
            if ("getName".equals(name)) return "Bluetooth";
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
            if (returnType == int.class || returnType == Integer.class) return 0;
            return null;
        }
    }

    @ProxyMethod("isEnabled")
    public static class IsEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            } catch (Throwable t) {
                Slog.w(TAG, "IBluetooth.isEnabled hook threw: " + t.getMessage());
            }
            return IBluetoothManagerProxy.isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("getState")
    public static class GetState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) {
                    return result;
                }
            } catch (Throwable t) {
                Slog.w(TAG, "IBluetooth.getState hook threw: " + t.getMessage());
            }
            int hostState = IBluetoothManagerProxy.getHostBluetoothState();
            if (hostState != BluetoothAdapter.STATE_OFF) return hostState;
            return BluetoothAdapter.STATE_OFF;
        }
    }

    @ProxyMethod("isMultipleAdvertisementSupported")
    public static class IsMultipleAdvertisementSupported extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) return true;
            } catch (Throwable ignored) {}
            return IBluetoothManagerProxy.isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("isPeripheralModeSupported")
    public static class IsPeripheralModeSupported extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) return true;
            } catch (Throwable ignored) {}
            return IBluetoothManagerProxy.isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("isLeEnabled")
    public static class IsLeEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Boolean && (Boolean) result) return true;
            } catch (Throwable ignored) {}
            return IBluetoothManagerProxy.isHostBluetoothEnabled();
        }
    }

    @ProxyMethod("getLeState")
    public static class GetLeState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBluetoothManagerProxy.sanitizeBluetoothArgs(args);
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) return result;
            } catch (Throwable ignored) {}
            int hostState = IBluetoothManagerProxy.getHostBluetoothState();
            if (hostState != BluetoothAdapter.STATE_OFF) return hostState;
            return BluetoothAdapter.STATE_OFF;
        }
    }
}
'''
write(Path('runtime/src/main/java/com/tegufy/blackbox/fake/service/IBluetoothProxy.java'),bt_proxy_code)

camera_proxy_code='''package com.tegufy.blackbox.fake.service;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import black.android.os.BRServiceManager;
import com.tegufy.blackbox.BlackBoxCore;
import com.tegufy.blackbox.app.BActivityThread;
import com.tegufy.blackbox.fake.hook.BinderInvocationStub;
import com.tegufy.blackbox.utils.MethodParameterUtils;
import com.tegufy.blackbox.utils.Slog;

public class CameraServiceProxy extends BinderInvocationStub {
    public static final String TAG = "CameraServiceProxy";

    public CameraServiceProxy() {
        super(BRServiceManager.get().getService("media.camera"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("media.camera");
        if (binder == null) {
            Slog.w(TAG, "media.camera binder is null");
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("android.hardware.ICameraService$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
            return asInterfaceMethod.invoke(null, binder);
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to get ICameraService interface: " + t.getMessage());
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("media.camera");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    public static void sanitizeCameraArgs(Object[] args) {
        if (args == null) return;
        String guestPkg = BActivityThread.getAppPackageName();
        String hostPkg = BlackBoxCore.getHostPkg();
        int hostUid = BlackBoxCore.getHostUid();

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;

            if (arg instanceof String) {
                String str = (String) arg;
                if ((guestPkg != null && guestPkg.equals(str)) || BlackBoxCore.get().isInstalled(str, BlackBoxCore.getUserId())) {
                    args[i] = hostPkg;
                }
            } else if (arg.getClass().getName().equals("android.content.AttributionSource")) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    try {
                        Object hostSource = BlackBoxCore.getContext().getAttributionSource();
                        if (hostSource != null) {
                            args[i] = hostSource;
                            continue;
                        }
                    } catch (Throwable ignored) {}
                }
                IBluetoothManagerProxy.fixAttributionSource(arg);
            } else if (arg instanceof Integer) {
                int val = (Integer) arg;
                if (val == BActivityThread.getUserId() || val == BlackBoxCore.getUserId() || val > 10000) {
                    args[i] = hostUid;
                }
            } else if (arg instanceof Object[]) {
                sanitizeCameraArgs((Object[]) arg);
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        sanitizeCameraArgs(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Slog.w(TAG, "ICameraService." + method.getName() + " threw: " + cause.getMessage());
            throw cause;
        }
    }
}
'''
write(Path('runtime/src/main/java/com/tegufy/blackbox/fake/service/CameraServiceProxy.java'),camera_proxy_code)

hook_mgr=P/'runtime/src/main/java/com/tegufy/blackbox/fake/hook/HookManager.java'
t=hook_mgr.read_text(encoding='utf-8')
t=t.replace('import com.tegufy.blackbox.fake.service.IAudioServiceProxy;',
            'import com.tegufy.blackbox.fake.service.IAudioServiceProxy;\nimport com.tegufy.blackbox.fake.service.IBluetoothManagerProxy;\nimport com.tegufy.blackbox.fake.service.IBluetoothProxy;\nimport com.tegufy.blackbox.fake.service.GooglePlayServicesProxy;\nimport com.tegufy.blackbox.fake.service.CameraServiceProxy;')
t=t.replace('addInjector(new GooglePlayServicesProxy());', '// GooglePlayServicesProxy moved to unconditional init')
t=t.replace('addInjector(new IAudioServiceProxy());',
            'addInjector(new IAudioServiceProxy());\n            addInjector(new GooglePlayServicesProxy());\n            addInjector(new IBluetoothManagerProxy());\n            addInjector(new IBluetoothProxy());\n            addInjector(new CameraServiceProxy());')
hook_mgr.write_text(t,encoding='utf-8')

# Runtime licenses accompany the generated source and the experimental APK.
notices=['Experimental Clone Apps runtime: locally adapted sources, not an upstream APK.',
    'BlackBox derives from the BlackBox project by niunaijun/FBlackBox and contributors, adapted by TeguFy. See the pinned source revisions and their licenses below.']
for license in sorted((P/'licenses').glob('*.txt')):
    notices.append(license.name+'\n'+license.read_text(encoding='utf-8'))
notices.append('Dobby contains LLVM cache maintenance code (Apache-2.0 WITH LLVM-exception).\n'+
    (ROOT/'scripts/runtime-proof/LLVM-LICENSE.txt').read_text(encoding='utf-8'))
notices.append('Pinned source revisions:\n'+(P/'source-revisions.json').read_text(encoding='utf-8'))
write(Path('runtime/src/main/assets/third-party-runtime-notices.txt'),'\n\n'.join(notices))
# First-party test fixture: launch component, executable code and resources all
# reside in an install-time feature split. This is not an ordinary-app claim.
with (P/'settings.gradle').open('a',encoding='utf-8') as f:
    f.write("\ninclude ':split-fixture-base', ':split_fixture_extra'\n")
write(Path('split-fixture-base/build.gradle'),'''plugins { id 'com.android.application' }
android {
 namespace 'org.parallelnest.splitfixture'; compileSdk 36
 defaultConfig { applicationId 'org.parallelnest.splitfixture'; minSdk 33; targetSdk 36; versionCode 1; versionName '1.0' }
 dynamicFeatures=[':split_fixture_extra']
 compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
''')
write(Path('split-fixture-base/src/main/AndroidManifest.xml'),'''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="Split Runtime Fixture" android:allowBackup="false" android:theme="@android:style/Theme.Material.Light.NoActionBar"/>
</manifest>''')
write(Path('split-fixture-base/src/main/res/values/strings.xml'),'<resources><string name="extra_feature_title">Split fixture feature</string></resources>')
write(Path('split_fixture_extra/build.gradle'),'''plugins { id 'com.android.dynamic-feature' }
android {
 namespace 'org.parallelnest.splitfixture.extra'; compileSdk 36
 defaultConfig { minSdk 33 }
 compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
dependencies { implementation project(':split-fixture-base') }
''')
write(Path('split_fixture_extra/src/main/AndroidManifest.xml'),'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:dist="http://schemas.android.com/apk/distribution">
<dist:module dist:title="@string/extra_feature_title"><dist:delivery><dist:install-time><dist:removable dist:value="true"/></dist:install-time></dist:delivery><dist:fusing dist:include="true"/></dist:module>
<application><activity android:name="org.parallelnest.splitfixture.extra.FeatureActivity" android:exported="true">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>''')
write(Path('split_fixture_extra/src/main/res/values/strings.xml'),'<resources><string name="feature_message">FEATURE_RESOURCE_20261016</string></resources>')
write(Path('split_fixture_extra/src/main/java/org/parallelnest/splitfixture/extra/FeaturePayload.java'),'''package org.parallelnest.splitfixture.extra;
public final class FeaturePayload { public static String value(){return "FEATURE_CODE_20261016";} }
''')
write(Path('split_fixture_extra/src/main/java/org/parallelnest/splitfixture/extra/FeatureActivity.java'),'''package org.parallelnest.splitfixture.extra;
import android.app.Activity; import android.os.Bundle; import android.widget.*;
public class FeatureActivity extends Activity {
 public void onCreate(Bundle saved){super.onCreate(saved);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(20,60,20,20);
  TextView value=new TextView(this);value.setId(android.R.id.text1);
  android.content.SharedPreferences prefs=getSharedPreferences("split-fixture",0);
  Runnable refresh=()->value.setText(getString(R.string.feature_message)+" / "+FeaturePayload.value()+" / count="+prefs.getInt("count",0));
  Button button=new Button(this);button.setId(android.R.id.button1);button.setText("Increment split counter");
  button.setOnClickListener(v->{prefs.edit().putInt("count",prefs.getInt("count",0)+1).commit();refresh.run();});
  layout.addView(value);layout.addView(button);setContentView(layout);refresh.run();
 }
}
''')
# Preserve upstream notices and identify changed source files in the generated
# source tree as required by their license. No executable prebuilt is copied.
for target,original in [
    (P/'runtime/src/main',UP/'Bcore/src/main'),
    (P/'free-reflection/src/main',ROOT/'.research/FreeReflection-source/library/src/main')
]:
    for file in target.rglob('*'):
        source=original/file.relative_to(target)
        if file.suffix not in {'.java','.cpp','.h','.xml'} or not source.is_file(): continue
        if file.read_bytes()==source.read_bytes(): continue
        text=file.read_text(encoding='utf-8')
        notice='Modified by Clone Apps for the 2026 compatibility experiment; see scripts/runtime-proof and the source manifest.'
        if file.suffix=='.xml':
            comment='<!-- '+notice+' -->\n'
            text=text.replace('?>','?>\n'+comment,1) if text.startswith('<?xml') else comment+text
        else: text='// '+notice+'\n'+text
        file.write_text(text,encoding='utf-8')
manifest={
    'upstreams':json.loads((P/'source-revisions.json').read_text(encoding='utf-8')),
    'generator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'adaptations':{file.name:hashlib.sha256(file.read_bytes()).hexdigest()
        for file in sorted((ROOT/'scripts/runtime-proof').iterdir()) if file.is_file()}
}
write(Path('runtime/src/main/assets/runtime-source-manifest.json'),json.dumps(manifest,indent=2))
print(P)
