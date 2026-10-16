package org.parallelnest.app.engine.virtualization

import android.content.Context
import java.io.File
import java.util.UUID

/** Directory separation for trusted guests sharing the host UID, not a security sandbox. */
class GuestStorage(context: Context) {
    private val root = File(context.filesDir, "virtual/instances").apply { mkdirs() }.canonicalFile
    fun instance(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid instance UUID" }
        return child(root, id)
    }
    fun prepare(id: String): File = instance(id).apply {
        check(mkdirs()) { "Instance already exists" }
        listOf("apk", "files", "databases", "preferences", "cache", "code_cache").forEach { child(this, it).mkdirs() }
    }
    fun delete(id: String) {
        val directory = instance(id)
        if (directory.exists()) java.nio.file.Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if(error!=null)throw error
                java.nio.file.Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
            }
        }) // Deliberately do not FOLLOW_LINKS during deletion.
    }
    companion object {
        fun child(parent: File, name: String): File {
            require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid private file name" }
            val target = File(parent, name).canonicalFile
            require(target.parentFile == parent.canonicalFile) { "Path escapes instance directory" }
            return target
        }
    }
}
