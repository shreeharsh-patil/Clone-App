package org.parallelnest.app.engine.virtualization

import android.app.Application
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.view.LayoutInflater
import java.io.*

internal class GuestContext(
    base: Context, private val root: File, private val guestInfo: ApplicationInfo,
    private val loader: ClassLoader, private val guestResources: Resources
) : ContextWrapper(base) {
    var guestApplication: Application? = null
    private val preferences = mutableMapOf<String, GuestPreferences>()
    override fun getApplicationContext(): Context = guestApplication ?: this
    override fun getClassLoader() = loader
    override fun getResources() = guestResources
    override fun getAssets() = guestResources.assets
    override fun getPackageName() = guestInfo.packageName
    override fun getApplicationInfo() = guestInfo
    override fun getPackageCodePath() = guestInfo.sourceDir
    override fun getPackageResourcePath() = guestInfo.sourceDir
    override fun getDataDir() = root
    override fun getFilesDir() = GuestStorage.child(root, "files")
    override fun getCacheDir() = GuestStorage.child(root, "cache")
    override fun getCodeCacheDir() = GuestStorage.child(root, "code_cache")
    override fun getNoBackupFilesDir() = filesDir
    override fun getExternalFilesDir(type: String?): File = if (type == null) filesDir else GuestStorage.child(filesDir, type).apply { mkdirs() }
    override fun getExternalFilesDirs(type: String?) = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir() = cacheDir
    override fun getExternalCacheDirs() = arrayOf(cacheDir)
    override fun getDir(name: String, mode: Int): File { privateMode(mode); return GuestStorage.child(filesDir, name).apply { mkdirs() } }
    private fun privateMode(mode: Int) { require(mode == MODE_PRIVATE || mode == MODE_APPEND) { "Only private guest files are supported" } }
    @Synchronized override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        privateMode(mode)
        return preferences.getOrPut(name) { GuestPreferences(GuestStorage.child(GuestStorage.child(root, "preferences"), "$name.json")) }
    }
    override fun deleteSharedPreferences(name: String): Boolean { synchronized(this) { preferences.remove(name) }; return GuestStorage.child(GuestStorage.child(root, "preferences"), "$name.json").delete() }
    override fun getDatabasePath(name: String) = GuestStorage.child(GuestStorage.child(root, "databases"), name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        require(mode == MODE_PRIVATE) { "Only private guest databases are supported" }
        return SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }
    override fun databaseList() = GuestStorage.child(root, "databases").list().orEmpty()
    override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun openFileInput(name: String) = FileInputStream(GuestStorage.child(filesDir, name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream { privateMode(mode); return FileOutputStream(GuestStorage.child(filesDir, name), mode == MODE_APPEND) }
    override fun getFileStreamPath(name: String) = GuestStorage.child(filesDir, name)
    override fun deleteFile(name: String) = GuestStorage.child(filesDir, name).delete()
    override fun fileList() = filesDir.list().orEmpty()
    override fun checkSelfPermission(permission: String) = GuestPermissionPolicy.decision(permission) ?: super.checkSelfPermission(permission)
    override fun checkPermission(permission: String, pid: Int, uid: Int) = GuestPermissionPolicy.decision(permission) ?: super.checkPermission(permission, pid, uid)
    override fun checkCallingOrSelfPermission(permission: String) = GuestPermissionPolicy.decision(permission) ?: super.checkCallingOrSelfPermission(permission)
    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        check(GuestPermissionPolicy.decision(permission)==null) { "Guest permission bridge unsupported: $permission" }; super.enforcePermission(permission,pid,uid,message)
    }
    override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE) LayoutInflater.from(baseContext).cloneInContext(this) else super.getSystemService(name)
    override fun startActivity(intent: Intent) { throw UnsupportedOperationException("Guest Activity routing is not supported by this prototype") }
    override fun startActivity(intent: Intent, options: android.os.Bundle?) { startActivity(intent) }
    override fun startService(service: Intent): ComponentName? = throw UnsupportedOperationException("Guest Services are not supported by this prototype")
    override fun startForegroundService(service: Intent): ComponentName? = startService(service)
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = throw UnsupportedOperationException("Guest Binder services are not supported by this prototype")
    override fun sendBroadcast(intent: Intent) { throw UnsupportedOperationException("Guest broadcast routing is not supported by this prototype") }
}
