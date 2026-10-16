package org.parallelnest.app.engine.virtualization

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Supported archive parsing only. No system PackageInstaller operation or APK rewriting. */
class GuestPackageManager(private val context: Context) {
    companion object {
        const val PROBE = "org.parallelnest.probe"
        const val ENTRY = "org.parallelnest.probe.GuestProbeActivity"
        const val LIMIT = "Prototype supports only the matching-signed first-party isolation probe; general Android apps need an audited component and Binder runtime"
        private val FLAGS = PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES).toLong())
        fun digest(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    fun installed(pkg: String): PackageInfo {
        require(pkg == PROBE) { LIMIT }
        val info = context.packageManager.getPackageInfo(pkg, FLAGS)
        check(context.packageManager.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH) { "Probe must be signed with the host signing identity" }
        check(info.applicationInfo?.splitSourceDirs.isNullOrEmpty()) { "Split APKs are recognized but unsupported by the prototype" }
        check(info.applicationInfo?.metaData?.getInt("org.parallelnest.GUEST_PROTOCOL") == 1) { "Probe has not opted into Activity hosting" }
        check(info.activities.orEmpty().any { it.name == ENTRY && it.targetActivity == null }) { "Missing guest Activity" }
        return info
    }
    fun import(pkg: String, root: File): JSONObject {
        val before = installed(pkg)
        val source = File(requireNotNull(before.applicationInfo).sourceDir)
        require(source.isFile && source.canRead() && source.length() in 1..134_217_728) { "APK unavailable or exceeds 128 MiB" }
        val apk = GuestStorage.child(GuestStorage.child(root, "apk"), "pending-${java.util.UUID.randomUUID()}.apk")
        source.inputStream().use { input -> FileOutputStream(apk).use { output ->
            check(apk.setReadOnly()) { "Cannot make guest code read-only" }; input.copyTo(output); output.fd.sync()
        } }
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, FLAGS) ?: error("Invalid base APK")
        val after = installed(pkg)
        check(archive.packageName == pkg && archive.longVersionCode == before.longVersionCode && before.longVersionCode == after.longVersionCode && before.applicationInfo?.sourceDir == after.applicationInfo?.sourceDir) { "Package changed during import; retry" }
        val signer = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        check(!signer.isNullOrEmpty() && signer == before.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()) { "APK signing identity mismatch" }
        val abis = ZipFile(apk).use { zip -> zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.map { it.name.split('/')[1] }.toSet() }
        check(abis.isEmpty()) { "Native guest libraries require native filesystem and linker routing; unsupported" }
        val hash = digest(apk)
        val immutable = GuestStorage.child(GuestStorage.child(root, "apk"), "$hash.apk")
        if(immutable.exists()) { check(digest(immutable)==hash);check(apk.delete()) } else check(apk.renameTo(immutable))
        return JSONObject().put("package", pkg).put("entry", ENTRY).put("version", archive.longVersionCode).put("sha256", hash).put("apk", immutable.name).put("abi", "bytecode")
    }
    fun read(root: File): Pair<JSONObject, PackageInfo> {
        val meta = AtomicFile(GuestStorage.child(root, "package.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
        check(meta.getString("package") == PROBE && meta.getString("entry") == ENTRY) { LIMIT }
        val apk = GuestStorage.child(GuestStorage.child(root, "apk"), meta.optString("apk", "base.apk"))
        check(apk.isFile && digest(apk) == meta.getString("sha256")) { "Guest APK is missing or corrupt" }
        val info = context.packageManager.getPackageArchiveInfo(apk.path, FLAGS) ?: error("Unreadable guest APK")
        val host = context.packageManager.getPackageInfo(context.packageName, FLAGS)
        check(info.packageName == PROBE && info.longVersionCode == meta.getLong("version")) { "Guest package metadata mismatch" }
        check(info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() == host.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()) { "Guest no longer matches host signing identity" }
        return meta to info
    }
    fun write(root: File, metadata: JSONObject) {
        val atomic = AtomicFile(GuestStorage.child(root, "package.json"))
        val output = atomic.startWrite()
        try { output.write(metadata.toString().toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
