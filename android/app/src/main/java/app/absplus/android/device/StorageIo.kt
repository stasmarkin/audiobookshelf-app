package app.absplus.android.device

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** What a path is, read without following a symbolic link at its end. */
enum class NodeKind { MISSING, FILE, DIRECTORY, LINK, OTHER }

/**
 * The file operations that moving downloads between storages and deleting app-storage downloads use. Links are never
 * followed: a link or special file inside a download folder makes a copy fail and is only unlinked by a delete, so
 * neither can reach outside the folder (M01). Tests replace it to inject failures at any step.
 */
interface StorageIo {
  fun kind(file: File): NodeKind
  fun list(dir: File): List<String>?
  fun mkdirs(dir: File): Boolean
  /** Copies [from] to [to] and makes [to] durable; returns the SHA-256 of the bytes read from [from]. */
  fun copyAndSync(from: File, to: File, cancelled: () -> Boolean): String
  fun sha256(file: File, cancelled: () -> Boolean): String
  fun length(file: File): Long
  fun rename(from: File, to: File): Boolean
  fun delete(file: File): Boolean
  /** Flushes a directory's entries; false when the filesystem does not support it or it failed. */
  fun syncDir(dir: File): Boolean
  fun freeBytes(dir: File): Long
}

class CopyCancelledException : IOException("cancelled")

object AndroidStorageIo : StorageIo {
  override fun kind(file: File): NodeKind = if (Build.VERSION.SDK_INT >= 26) nioKind(file) else lstatKind(file)

  @androidx.annotation.RequiresApi(26)
  private fun nioKind(file: File): NodeKind =
          try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when {
              attrs.isSymbolicLink -> NodeKind.LINK
              attrs.isRegularFile -> NodeKind.FILE
              attrs.isDirectory -> NodeKind.DIRECTORY
              else -> NodeKind.OTHER
            }
          } catch (e: NoSuchFileException) {
            NodeKind.MISSING
          } catch (e: Exception) {
            NodeKind.OTHER
          }

  private fun lstatKind(file: File): NodeKind =
          try {
            val mode = Os.lstat(file.path).st_mode
            when {
              OsConstants.S_ISLNK(mode) -> NodeKind.LINK
              OsConstants.S_ISREG(mode) -> NodeKind.FILE
              OsConstants.S_ISDIR(mode) -> NodeKind.DIRECTORY
              else -> NodeKind.OTHER
            }
          } catch (e: Exception) {
            // ENOENT is the only "missing"; any other error is an unknown node, which every caller treats as unsafe
            if (e is android.system.ErrnoException && e.errno == OsConstants.ENOENT) NodeKind.MISSING else NodeKind.OTHER
          }

  override fun list(dir: File): List<String>? = dir.list()?.toList()

  override fun mkdirs(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

  override fun copyAndSync(from: File, to: File, cancelled: () -> Boolean): String = StorageHashing.copyAndSync(from, to, cancelled)

  override fun sha256(file: File, cancelled: () -> Boolean): String = StorageHashing.sha256(file, cancelled)

  override fun length(file: File): Long = file.length()

  override fun rename(from: File, to: File): Boolean = from.renameTo(to)

  override fun delete(file: File): Boolean = file.delete()

  override fun syncDir(dir: File): Boolean =
          try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
              Os.fsync(fd)
              true
            } finally {
              Os.close(fd)
            }
          } catch (e: Exception) {
            false
          }

  override fun freeBytes(dir: File): Long {
    var existing: File? = dir.absoluteFile
    while (existing != null && !existing.exists()) existing = existing.parentFile
    return if (existing == null) 0L else android.os.StatFs(existing.path).availableBytes
  }
}

/** Streams shared by the Android and test implementations. */
object StorageHashing {
  private const val BUFFER = 256 * 1024

  fun copyAndSync(from: File, to: File, cancelled: () -> Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(from).use { input ->
      FileOutputStream(to).use { output ->
        val buffer = ByteArray(BUFFER)
        while (true) {
          if (cancelled()) throw CopyCancelledException()
          val n = input.read(buffer)
          if (n < 0) break
          digest.update(buffer, 0, n)
          output.write(buffer, 0, n)
        }
        output.flush()
        output.fd.sync()
      }
    }
    return hex(digest.digest())
  }

  fun sha256(file: File, cancelled: () -> Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(BUFFER)
      while (true) {
        if (cancelled()) throw CopyCancelledException()
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return hex(digest.digest())
  }

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** A tree walk and delete that never follow links. */
object SafeTree {
  data class Entry(val relativePath: String, val file: File)

  /** Every regular file under [root] (a real directory); null when anything else is found or a listing fails. */
  fun regularFiles(io: StorageIo, root: File): List<Entry>? {
    if (io.kind(root) != NodeKind.DIRECTORY) return null
    val out = mutableListOf<Entry>()
    fun walk(dir: File, prefix: String): Boolean {
      val names = io.list(dir) ?: return false
      for (name in names.sorted()) {
        val child = File(dir, name)
        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
        when (io.kind(child)) {
          NodeKind.FILE -> out += Entry(rel, child)
          NodeKind.DIRECTORY -> if (!walk(child, rel)) return false
          else -> return false
        }
      }
      return true
    }
    return if (walk(root, "")) out else null
  }

  /** Deletes [root] and what is below it without following links (a link is removed, never its target). */
  fun delete(io: StorageIo, root: File): Boolean {
    return when (io.kind(root)) {
      NodeKind.MISSING -> true
      NodeKind.FILE, NodeKind.LINK -> io.delete(root)
      NodeKind.DIRECTORY -> {
        val names = io.list(root) ?: return false
        var ok = true
        for (name in names) if (!delete(io, File(root, name))) ok = false
        ok && io.delete(root)
      }
      NodeKind.OTHER -> false
    }
  }

  /** Removes [dir] and its empty parents up to (not including) [stopAt], stopping at the first one that is not empty. */
  fun pruneEmptyDirs(io: StorageIo, dir: File, stopAt: File) {
    var current: File? = dir
    while (current != null && AppStorage.isUnder(current, stopAt) && AppStorage.normalizedPath(current) != AppStorage.normalizedPath(stopAt)) {
      if (io.kind(current) != NodeKind.DIRECTORY || io.list(current)?.isEmpty() != true || !io.delete(current)) return
      current = current.parentFile
    }
  }
}
