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
classes=P/'bootstrap-classes'; dex=P/'bootstrap-dex'; classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True)
source=P/'free-reflection/src/main/java/me/weishu/reflection/BootstrapClass.java'
subprocess.run([str(jdk/'bin/javac.exe'),'-source','8','-target','8','-classpath',str(sdk/'platforms/android-36/android.jar'),'-d',str(classes),str(source)],check=True)
subprocess.run([str(sdk/'build-tools/36.0.0/d8.bat'),'--min-api','33','--lib',str(sdk/'platforms/android-36/android.jar'),'--output',str(dex),str(classes/'me/weishu/reflection/BootstrapClass.class')],check=True)
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
subprocess.run([str(jdk/'bin/javac.exe'),'-source','8','-target','8','-d',str(emptyclasses),str(P/'marker/Empty.java')],check=True)
subprocess.run([str(sdk/'build-tools/36.0.0/d8.bat'),'--min-api','33','--output',str(emptydex),str(emptyclasses/'org/parallelnest/runtimeproof/Empty.class')],check=True)
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
    'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION'
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

# Bluetooth and permission runtime patches for guest applications (WhatsApp, calls, audio accessories)
bt_helper='''
    private static boolean isBluetoothPermission(String permission) {
        if (permission == null) return false;
        return permission.equals("android.permission.BLUETOOTH")
                || permission.equals("android.permission.BLUETOOTH_ADMIN")
                || permission.equals("android.permission.BLUETOOTH_CONNECT")
                || permission.equals("android.permission.BLUETOOTH_SCAN")
                || permission.equals("android.permission.BLUETOOTH_ADVERTISE");
    }
'''
pm_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IPackageManagerProxy.java'
t=pm_proxy.read_text(encoding='utf-8')
t=t.replace('private static boolean isStorageOrMediaPermission',bt_helper+'\n    private static boolean isStorageOrMediaPermission')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "SimpleAudioPermissionHook: Granting audio permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission)) {\n                Slog.d(TAG, "SimpleAudioPermissionHook: Granting permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "CheckSelfPermission: Granting audio permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission)) {\n                Slog.d(TAG, "CheckSelfPermission: Granting permission: " + permission + " to " + packageName);\n                return PackageManager.PERMISSION_GRANTED;\n            }')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for audio permission: " + permission);\n                return false;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission)) {\n                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for permission: " + permission);\n                return false;\n            }')
pm_proxy.write_text(t,encoding='utf-8')

am_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IActivityManagerProxy.java'
t=am_proxy.read_text(encoding='utf-8')
t=t.replace('private static boolean isAudioPermission',bt_helper+'\n    private static boolean isAudioPermission')
t=t.replace('if (isAudioPermission(permission)) {\n                Slog.d(TAG, "ActivityManager checkPermission: Granting audio permission: " + permission);\n                return PackageManager.PERMISSION_GRANTED;\n            }',
            'if (isAudioPermission(permission) || isBluetoothPermission(permission)) {\n                Slog.d(TAG, "ActivityManager checkPermission: Granting permission: " + permission);\n                return PackageManager.PERMISSION_GRANTED;\n            }')
needle_bind='public static Object BindServiceCommon(Object who, Method method, Object[] args,int callingPackageIndex) throws Throwable {\n        try {'
replace_bind=needle_bind+'''
            if (callingPackageIndex >= 0 && args != null && callingPackageIndex < args.length && args[callingPackageIndex] instanceof String) {
                args[callingPackageIndex] = BlackBoxCore.getHostPkg();
            }'''
assert t.count(needle_bind)==1
t=t.replace(needle_bind,replace_bind)
am_proxy.write_text(t,encoding='utf-8')

perm_proxy=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IPermissionManagerProxy.java'
t=perm_proxy.read_text(encoding='utf-8')
t=t.replace('import com.tegufy.blackbox.fake.hook.BinderInvocationStub;',
            'import com.tegufy.blackbox.fake.hook.BinderInvocationStub;\nimport com.tegufy.blackbox.fake.hook.MethodHook;')
perm_check_hooks='''        addMethodHook("checkPermission", new MethodHook() {
            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String permission = (String) args[0];
                    if (isAllowedPermission(permission)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                }
                com.tegufy.blackbox.utils.MethodParameterUtils.replaceFirstAppPkg(args);
                return method.invoke(who, args);
            }
        });
        addMethodHook("checkUidPermission", new MethodHook() {
            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                if (args != null && args.length > 1 && args[1] instanceof String) {
                    String permission = (String) args[1];
                    if (isAllowedPermission(permission)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                }
                com.tegufy.blackbox.utils.MethodParameterUtils.replaceLastUid(args);
                return method.invoke(who, args);
            }
        });
'''
allowed_helper='''
    private static boolean isAllowedPermission(String permission) {
        if (permission == null) return false;
        return permission.startsWith("android.permission.BLUETOOTH")
                || permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals(android.Manifest.permission.CAMERA)
                || permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.startsWith("android.permission.READ_MEDIA_")
                || permission.equals("android.permission.POST_NOTIFICATIONS")
                || permission.startsWith("android.permission.FOREGROUND_SERVICE");
    }
'''
t=t.replace('addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));',
            'addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));\n'+perm_check_hooks)
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

appops=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/IAppOpsManagerProxy.java'
t=appops.read_text(encoding='utf-8')
t=t.replace('if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE")))',
            'if (name != null && isMediaStorageOrAudioOp(name))')
appops.write_text(t,encoding='utf-8')

act_common=P/'runtime/src/main/java/com/tegufy/blackbox/fake/service/ActivityManagerCommonProxy.java'
t=act_common.read_text(encoding='utf-8')
needle_perm='if (dataString != null && dataString.equals("package:" + BActivityThread.getAppPackageName())) {\n                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));\n            }'
replace_perm=needle_perm+'''
            if ("android.content.pm.action.REQUEST_PERMISSIONS".equals(intent.getAction())) {
                intent.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_PACKAGE_NAME", BlackBoxCore.getHostPkg());
            }'''
assert t.count(needle_perm)==1
act_common.write_text(t.replace(needle_perm,replace_perm),encoding='utf-8')

bt_mgr_code='''package com.tegufy.blackbox.fake.service;

import android.bluetooth.BluetoothAdapter;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

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
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    public static void sanitizeBluetoothArgs(Object[] args) {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            if (arg.getClass().getName().equals("android.content.AttributionSource")) {
                fixAttributionSource(arg);
            } else if (arg instanceof Object[]) {
                sanitizeBluetoothArgs((Object[]) arg);
            } else if (arg instanceof String) {
                String str = (String) arg;
                String guestPkg = BActivityThread.getAppPackageName();
                if ((guestPkg != null && guestPkg.equals(str)) || BlackBoxCore.get().isInstalled(str, BlackBoxCore.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                }
            } else if (arg instanceof Integer) {
                int uid = (Integer) arg;
                if (uid == BlackBoxCore.getBUid()) {
                    args[i] = BlackBoxCore.getHostUid();
                }
            }
        }
    }

    public static void fixAttributionSource(Object attributionSource) {
        if (attributionSource == null) return;
        try {
            Field stateField = attributionSource.getClass().getDeclaredField("mAttributionSourceState");
            stateField.setAccessible(true);
            Object state = stateField.get(attributionSource);
            if (state != null) {
                fixAttributionSourceState(state);
            }
        } catch (Throwable t) {
            try {
                Field pkgField = attributionSource.getClass().getDeclaredField("mPackageName");
                pkgField.setAccessible(true);
                pkgField.set(attributionSource, BlackBoxCore.getHostPkg());
            } catch (Throwable ignored) {}
            try {
                Field uidField = attributionSource.getClass().getDeclaredField("mUid");
                uidField.setAccessible(true);
                uidField.set(attributionSource, BlackBoxCore.getHostUid());
            } catch (Throwable ignored) {}
        }
    }

    public static void fixAttributionSourceState(Object state) {
        if (state == null) return;
        try {
            Class<?> stateClass = state.getClass();
            try {
                Field uidField = stateClass.getField("uid");
                uidField.setInt(state, BlackBoxCore.getHostUid());
            } catch (Throwable ignored) {}

            try {
                Field pkgField = stateClass.getField("packageName");
                pkgField.set(state, BlackBoxCore.getHostPkg());
            } catch (Throwable ignored) {}

            try {
                Field nextField = stateClass.getField("next");
                Object nextArray = nextField.get(state);
                if (nextArray instanceof Object[]) {
                    for (Object nextState : (Object[]) nextArray) {
                        fixAttributionSourceState(nextState);
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to fix AttributionSourceState: " + t.getMessage());
        }
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
                        return ((IInterface) target).asBinder();
                    }
                    if (target instanceof IBinder) {
                        return target;
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
                    Object result = method.invoke(target, args);
                    if ("isEnabled".equals(name)) {
                        if (result instanceof Boolean && (Boolean) result) return true;
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null && adapter.isEnabled()) return true;
                    }
                    if ("getState".equals(name)) {
                        if (result instanceof Integer && (Integer) result != BluetoothAdapter.STATE_OFF) return result;
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null) return adapter.getState();
                    }
                    return result;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    Slog.w(TAG, "IBluetooth." + name + " threw exception: " + cause.getMessage());
                    if ("isEnabled".equals(name)) {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        return adapter != null && adapter.isEnabled();
                    }
                    if ("getState".equals(name)) {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        return adapter != null ? adapter.getState() : BluetoothAdapter.STATE_OFF;
                    }
                    if ("getBondedDevices".equals(name)) {
                        Class<?> returnType = method.getReturnType();
                        if (returnType.isArray()) {
                            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                        }
                        return null;
                    }
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
                        return ((IInterface) target).asBinder();
                    }
                    if (target instanceof IBinder) {
                        return target;
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
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                return adapter != null && adapter.isEnabled();
            }
            if ("getState".equals(name)) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                return adapter != null ? adapter.getState() : BluetoothAdapter.STATE_OFF;
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
            sanitizeBluetoothArgs(args);
            Object result = null;
            try {
                result = method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "registerAdapter threw: " + t.getMessage());
            }
            if (result != null) {
                Object proxied = createBluetoothProxy(result);
                try {
                    if (proxied instanceof IBinder) {
                        Map<String, IBinder> cache = BRServiceManager.get().sCache();
                        if (cache != null) {
                            cache.put("bluetooth", (IBinder) proxied);
                        }
                    }
                } catch (Throwable ignored) {}
                return proxied;
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
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
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
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    return adapter.getState();
                }
            } catch (Throwable ignored) {}
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
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String name = method.getName();
            Slog.w(TAG, "IBluetooth." + name + " threw: " + cause.getMessage());
            if ("isEnabled".equals(name)) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                return adapter != null && adapter.isEnabled();
            }
            if ("getState".equals(name)) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                return adapter != null ? adapter.getState() : BluetoothAdapter.STATE_OFF;
            }
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
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) return true;
            } catch (Throwable ignored) {}
            return false;
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
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) return adapter.getState();
            } catch (Throwable ignored) {}
            return BluetoothAdapter.STATE_OFF;
        }
    }
}
'''
write(Path('runtime/src/main/java/com/tegufy/blackbox/fake/service/IBluetoothProxy.java'),bt_proxy_code)

hook_mgr=P/'runtime/src/main/java/com/tegufy/blackbox/fake/hook/HookManager.java'
t=hook_mgr.read_text(encoding='utf-8')
t=t.replace('import com.tegufy.blackbox.fake.service.IAudioServiceProxy;',
            'import com.tegufy.blackbox.fake.service.IAudioServiceProxy;\nimport com.tegufy.blackbox.fake.service.IBluetoothManagerProxy;\nimport com.tegufy.blackbox.fake.service.IBluetoothProxy;')
t=t.replace('addInjector(new IAudioServiceProxy());',
            'addInjector(new IAudioServiceProxy());\n            addInjector(new IBluetoothManagerProxy());\n            addInjector(new IBluetoothProxy());')
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
