package me.laumss.mosaic

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


class MosaicArchiveModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName(): String = "MosaicArchive"

    private companion object {
        const val STAGING = ".mosaic-restore.tmp"
        const val PREVIOUS = ".mosaic-restore.old"
    }

    private fun names(entries: ReadableArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until entries.size()) {
            val name = entries.getString(i) ?: continue
            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != "..") { "invalid entry $name" }
            out.add(name)
        }
        return out
    }

    
    @ReactMethod
    fun saveArchive(sourceDir: String, archivePath: String, entries: ReadableArray, promise: Promise) = runAsync(promise) {
        val source = File(sourceDir).canonicalFile
        val archive = File(archivePath).canonicalFile
        archive.parentFile?.mkdirs()
        val temp = File(archive.parentFile, "${archive.name}.tmp")
        var count = 0
        ZipOutputStream(FileOutputStream(temp)).use { zip ->
            for (name in names(entries)) {
                val root = File(source, name)
                if (!root.exists()) continue
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        if (count == 0) { temp.delete(); throw IllegalStateException("nothing to back up") }
        if (archive.exists() && !archive.delete()) throw IllegalStateException("cannot replace old archive")
        if (!temp.renameTo(archive)) throw IllegalStateException("archive rename failed")
        archive.absolutePath
    }

    
    @ReactMethod
    fun restoreArchive(sourceDir: String, archivePath: String, requiredFile: String, entries: ReadableArray, promise: Promise) = runAsync(promise) {
        val target = File(sourceDir).canonicalFile
        val archive = File(archivePath).canonicalFile
        if (!archive.exists()) throw IllegalArgumentException("archive not found")
        val allowed = names(entries)
        val staged = File(target, STAGING).canonicalFile
        val previous = File(target, PREVIOUS).canonicalFile
        if (staged.exists()) staged.deleteRecursively()
        staged.mkdirs()
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val top = entry.name.trimStart('/').substringBefore('/')
                    if (top in allowed) {
                        val out = File(staged, entry.name).canonicalFile
                        if (!out.path.startsWith(staged.path + File.separator)) throw SecurityException("invalid archive path")
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val required = File(staged, requiredFile)
            if (!required.isFile || required.length() == 0L) throw IllegalArgumentException("archive has no $requiredFile")
        } catch (e: Throwable) {
            staged.deleteRecursively()
            throw e
        }
        if (previous.exists()) previous.deleteRecursively()
        previous.mkdirs()
        val movedOut = ArrayList<String>()
        val movedIn = ArrayList<String>()
        try {
            for (name in allowed) {
                val current = File(target, name)
                if (current.exists()) {
                    if (!current.renameTo(File(previous, name))) throw IllegalStateException("cannot move $name")
                    movedOut.add(name)
                }
                val incoming = File(staged, name)
                if (incoming.exists()) {
                    if (!incoming.renameTo(File(target, name))) throw IllegalStateException("cannot move in $name")
                    movedIn.add(name)
                }
            }
        } catch (e: Throwable) {
            for (name in movedIn) File(target, name).deleteRecursively()
            for (name in movedOut) File(previous, name).renameTo(File(target, name))
            staged.deleteRecursively()
            previous.deleteRecursively()
            throw e
        }
        staged.deleteRecursively()
        previous.deleteRecursively()
        
        CardImageCache.clear()
        true
    }

    private fun runAsync(promise: Promise, work: () -> Any?) {
        Thread {
            try { promise.resolve(work()) } catch (e: Throwable) { promise.reject("ARCHIVE_FAILED", e.message, e) }
        }.start()
    }
}
