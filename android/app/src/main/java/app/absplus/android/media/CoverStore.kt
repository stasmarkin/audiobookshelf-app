package app.absplus.android.media

import app.absplus.android.managers.DownloadIdentifiers
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

const val MAX_COVER_CACHE_BYTES = 150L * 1024 * 1024
const val MAX_COVER_FILE_BYTES = 5L * 1024 * 1024
const val COVER_TOUCH_EVERY_MS = 24 * 60 * 60 * 1000L
const val COVER_CACHE_DIR = "cover-cache"

private val COVER_EXTENSIONS = setOf("jpg", "png", "webp", "gif", "avif", "bmp")
private const val TMP_EXTENSION = "tmp"

data class CoverEntry(val itemId: String, val updatedAt: Long, val file: File, val bytes: Long)

/**
 * Disk cache of item covers named `<itemId>_<updatedAt>.<ext>`: the file name is the whole index,
 * one version per item, least recently used evicted beyond [maxBytes].
 */
class CoverStore(
  private val dir: File,
  private val maxBytes: Long = MAX_COVER_CACHE_BYTES,
  private val maxFileBytes: Long = MAX_COVER_FILE_BYTES,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  // Insertion order is the LRU order: the first entry is the least recently used.
  private val entries = LinkedHashMap<String, CoverEntry>()
  private var total = 0L
  private var loaded = false

  /** Builds the index once; every public method does it first, so callers never see a half-built cache. */
  fun load() = ensureLoaded()

  private fun ensureLoaded() {
    synchronized(lock) {
      if (loaded) return
      dir.mkdirs()
      val found = dir.listFiles().orEmpty().filter { it.isFile }.mapNotNull { file ->
        val entry = parse(file)
        if (entry == null) file.delete()
        entry?.let { it to file.lastModified() }
      }
      found.groupBy { it.first.itemId }.values.forEach { versions ->
        versions.sortedByDescending { it.first.updatedAt }.drop(1).forEach { it.first.file.delete() }
      }
      found.filter { it.first.file.exists() }.sortedBy { it.second }.forEach { (entry, _) ->
        entries[entry.itemId] = entry
        total += entry.bytes
      }
      evict(keep = null)
      loaded = true
    }
  }

  fun find(itemId: String, updatedAt: Long): CoverEntry? = newest(itemId)?.takeIf { it.updatedAt == updatedAt }

  fun newest(itemId: String): CoverEntry? {
    if (!DownloadIdentifiers.isSafe(itemId)) return null
    synchronized(lock) {
      ensureLoaded()
      val entry = entries[itemId] ?: return null
      if (entry.file.exists()) return entry
      entries.remove(itemId)
      total -= entry.bytes
      return null
    }
  }

  /** Index only, no file check: a vanished file is caught by whoever decodes it. */
  fun peekNewest(itemId: String): CoverEntry? {
    if (!DownloadIdentifiers.isSafe(itemId)) return null
    synchronized(lock) {
      ensureLoaded()
      return entries[itemId]
    }
  }

  /** Stores a new version, replacing older versions of [itemId]; null when nothing was stored. */
  fun put(itemId: String, updatedAt: Long, ext: String, write: (OutputStream) -> Unit): CoverEntry? {
    if (!DownloadIdentifiers.isSafe(itemId) || updatedAt < 0 || ext !in COVER_EXTENSIONS) return null
    if (isOlderThanCached(itemId, updatedAt)) return null
    val base = "${itemId}_$updatedAt"
    val tmp = File(dir, "$base.$TMP_EXTENSION")
    try {
      dir.mkdirs()
      FileOutputStream(tmp).use { write(LimitedOutputStream(it, maxFileBytes)) }
      val bytes = tmp.length()
      if (bytes == 0L) throw IOException("empty cover")
      val file = File(dir, "$base.$ext")
      synchronized(lock) {
        // A newer version may have landed while this one was being written.
        if (isOlderThanCached(itemId, updatedAt)) throw IOException("a newer cover is cached")
        if (!tmp.renameTo(file)) throw IOException("rename failed: $file")
        file.setLastModified(clock())
        entries.remove(itemId)?.let { old ->
          total -= old.bytes
          if (old.file != file) old.file.delete()
        }
        val entry = CoverEntry(itemId, updatedAt, file, bytes)
        entries[itemId] = entry
        total += bytes
        evict(keep = itemId)
        return entry
      }
    } catch (e: Exception) {
      tmp.delete()
      return null
    }
  }

  fun touch(entry: CoverEntry) {
    val file = synchronized(lock) {
      ensureLoaded()
      val current = entries.remove(entry.itemId) ?: return
      entries[entry.itemId] = current
      current.file
    }
    // Writing lastModified on every read would turn each shown cover into a disk write.
    val now = clock()
    if (now - file.lastModified() >= COVER_TOUCH_EVERY_MS) file.setLastModified(now)
  }

  fun totalBytes(): Long = synchronized(lock) {
    ensureLoaded()
    total
  }

  private fun isOlderThanCached(itemId: String, updatedAt: Long): Boolean = synchronized(lock) {
    ensureLoaded()
    (entries[itemId]?.updatedAt ?: Long.MIN_VALUE) > updatedAt
  }

  private fun evict(keep: String?) {
    val iterator = entries.values.iterator()
    while (total > maxBytes && iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.itemId == keep) continue
      iterator.remove()
      total -= entry.bytes
      entry.file.delete()
    }
  }

  private fun parse(file: File): CoverEntry? {
    val name = file.name
    val ext = name.substringAfterLast('.', "")
    if (ext !in COVER_EXTENSIONS) return null
    // Ids may contain '_', so the version is after the last one.
    val base = name.substringBeforeLast('.')
    val separator = base.lastIndexOf('_')
    if (separator <= 0) return null
    val itemId = base.substring(0, separator)
    val updatedAt = base.substring(separator + 1).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    if (!DownloadIdentifiers.isSafe(itemId)) return null
    val bytes = file.length()
    if (bytes <= 0L) return null
    return CoverEntry(itemId, updatedAt, file, bytes)
  }

  private class LimitedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
    private var written = 0L

    override fun write(b: Int) {
      grow(1)
      out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      grow(len)
      out.write(b, off, len)
    }

    private fun grow(len: Int) {
      written += len
      if (written > limit) throw IOException("cover is larger than $limit bytes")
    }
  }
}
