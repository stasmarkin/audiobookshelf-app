package app.absplus.android.managers

import android.net.Uri
import androidx.core.net.toUri
import app.absplus.android.data.Book
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.Podcast
import app.absplus.android.device.AppStorage
import app.absplus.android.device.CopyCancelledException
import app.absplus.android.device.DownloadStorageSpace
import app.absplus.android.device.NodeKind
import app.absplus.android.device.SafeTree
import app.absplus.android.device.StorageIo
import app.absplus.android.device.VolumeProbe
import app.absplus.android.plugins.AbsLogger
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID

/** What [StorageMover] needs from the databases. */
interface MoverDb {
  fun getItem(id: String): LocalLibraryItem?
  /** Must take the item's [LocalItemLocks] lock and bump its version. */
  fun saveItem(item: LocalLibraryItem)
  fun hasQueuedDownload(libraryItemId: String): Boolean
  fun lastSession(): PlaybackSession?
  fun saveLastSession(s: PlaybackSession)
}

sealed class MoveResult {
  object Moved : MoveResult()
  data class Discarded(val reason: String) : MoveResult()
  data class Failed(val message: String) : MoveResult()
}

/** What one recovery pass did; for logs and tests. */
data class RecoverySummary(val rolledBack: Int, val committed: Int, val retired: Int, val reverted: Int, val waiting: Int, val attention: Int)

/**
 * Moves one app-storage download folder to another app storage (internal storage or the app's folder on an SD card)
 * without ever deleting the only good copy (H03). Every step is journaled in internal storage first ([MoveJournal]):
 *
 * 1. copy into an operation-unique temporary folder on the target, syncing every file, outside the item lock;
 * 2. re-read every copy and match its SHA-256 and length against the bytes read from the source;
 * 3. under the item lock, and only if the item did not change, is not playing and has no queued download: rename the
 *    temporary folder to the destination (never over an existing one), then point the item record at it;
 * 4. keep the source. After the device has restarted, recovery re-reads the destination (now from the storage itself,
 *    not from memory) and only when every file still matches deletes the source's manifest files, nothing else.
 *
 * A crash, kill, timeout or removed card at any point leaves the journal entry; [recover] finishes or undoes it. If the
 * destination fails its check after the restart, the item is pointed back at the untouched source; if that is not
 * possible both copies are kept. Sync barriers are used where the filesystem supports them, but no deletion depends
 * on them: removable FAT/exFAT cards behind Android's storage layer do not all guarantee directory sync.
 */
class StorageMover(
        private val probe: VolumeProbe,
        private val db: MoverDb,
        private val journal: MoveJournal,
        private val io: StorageIo,
        private val epoch: BootEpoch,
        private val cancelled: () -> Boolean = { false },
        /** Test hook: called at each named step; throwing there simulates the process dying at that point. */
        private val step: (String) -> Unit = {}
) {
  private class Snapshot(val item: LocalLibraryItem, val version: Long)

  /** Never concurrently with [recover]: its temporary-folder sweep must see this move's journal entry. */
  fun moveOne(id: String, toFolder: LocalFolder): MoveResult = synchronized(operationLock) { moveOneLocked(id, toFolder) }

  private fun moveOneLocked(id: String, toFolder: LocalFolder): MoveResult {
    val toRoot = AppStorage.downloadsRoot(toFolder, probe)
    if (toRoot == null || !AppStorage.isVerified(toFolder.id, probe)) return MoveResult.Discarded("unavailable")
    if (journal.get(id) != null) return MoveResult.Discarded("previous move not finished")

    // Snapshot, version and checks under one lock, so no write can slip in between them
    var notStarted: MoveResult? = null
    val locked =
            LocalItemLocks.withItem(id) {
              val item = db.getItem(id)
              notStarted =
                      when {
                        item == null -> MoveResult.Discarded("missing")
                        item.libraryItemId?.let { db.hasQueuedDownload(it) } == true -> MoveResult.Discarded("downloading")
                        LocalItemLocks.isPinned(id) -> MoveResult.Discarded("playing")
                        else -> null
                      }
              if (notStarted == null) Snapshot(item!!, LocalItemLocks.version(id)) else null
            }
    if (locked == null) return notStarted!!
    val snapshot = locked.item
    val serverId = snapshot.libraryItemId
    val v = locked.version

    if (!AppStorage.isAppStorage(snapshot.folderId)) return failed("$id is not in app storage")
    if (snapshot.folderId == toFolder.id) return MoveResult.Discarded("already on target")
    val fromRoot = AppStorage.downloadsRoot(snapshot.folderId, probe)
    if (fromRoot == null || !AppStorage.isVerified(snapshot.folderId, probe)) return MoveResult.Discarded("unavailable")

    val src = File(snapshot.absolutePath)
    if (snapshot.absolutePath.isEmpty() || src.parentFile?.let { AppStorage.normalizedPath(it) } != AppStorage.normalizedPath(fromRoot)) {
      return failed("$id folder ${snapshot.absolutePath} is not directly in ${fromRoot.absolutePath}")
    }
    val name = src.name
    // Folders are named by server id; anything else (e.g. ".." saved before ids were validated) escapes the roots
    if (!DownloadIdentifiers.isSafe(name)) return failed("$id folder name is not a safe id")
    // Only real folders and regular files are copied; a link or special file anywhere refuses the whole move (M01)
    val files = SafeTree.regularFiles(io, src) ?: return failed("$id folder ${src.absolutePath} has a link, special file or unreadable folder")
    val dest = File(toRoot, name)
    // An existing destination is never replaced or merged into: it may be another item's or the user's files
    if (io.kind(dest) != NodeKind.MISSING) return failed("${dest.absolutePath} already exists")
    val total = files.sumOf { io.length(it.file) }
    if (io.freeBytes(toRoot) < total + DownloadStorageSpace.MOVE_SPACE_MARGIN_BYTES) return MoveResult.Discarded("no space")

    val opId = UUID.randomUUID().toString()
    val tmp = File(toRoot, "$TMP_PREFIX$opId")
    val entry =
            StorageMoveJournalEntry(
                    opId = opId,
                    itemId = id,
                    libraryItemId = serverId,
                    fromFolderId = snapshot.folderId,
                    toFolderId = toFolder.id,
                    sourceDir = src.absolutePath,
                    destDir = dest.absolutePath,
                    tempDir = tmp.absolutePath,
                    files = files.map { ManifestFile(it.relativePath, io.length(it.file), "") }.toMutableList(),
                    phase = MovePhase.COPYING,
                    createdAt = System.currentTimeMillis())
    journal.put(entry)
    step("journaled")

    // 1-2. Copy and verify outside the lock
    val copyError: String? =
            try {
              copyAndVerify(src, tmp, entry)
            } catch (e: CopyCancelledException) {
              // Left for recovery: the journal entry says COPYING, so the next pass removes the temporary folder
              return MoveResult.Discarded("cancelled")
            } catch (e: Exception) {
              "copy of $id failed: ${e.javaClass.simpleName}: ${e.message}"
            }
    if (copyError != null) {
      rollbackCopy(entry)
      return failed(copyError)
    }
    entry.phase = MovePhase.VERIFIED
    journal.put(entry)
    step("verified")

    // 3. Install and commit under the lock, only for an unchanged, idle item
    val result =
            LocalItemLocks.withItem(id) {
              val discard =
                      when {
                        LocalItemLocks.version(id) != v -> "changed during copy"
                        LocalItemLocks.isPinned(id) -> "playing"
                        serverId != null && db.hasQueuedDownload(serverId) -> "downloading"
                        !AppStorage.isVerified(toFolder.id, probe) || !AppStorage.isVerified(snapshot.folderId, probe) -> "unavailable"
                        io.kind(dest) != NodeKind.MISSING -> "destination appeared"
                        else -> null
                      }
              if (discard != null) {
                rollbackCopy(entry)
                return@withItem MoveResult.Discarded(discard)
              }
              if (!io.rename(tmp, dest)) {
                rollbackCopy(entry)
                return@withItem failed("could not rename ${tmp.absolutePath} to ${dest.absolutePath}")
              }
              val barrier = io.syncDir(toRoot)
              entry.phase = MovePhase.INSTALLED
              entry.note = if (barrier) null else "directory sync not supported on target"
              journal.put(entry)
              step("installed")
              try {
                db.saveItem(relocate(snapshot, fromRoot, toRoot, toFolder))
              } catch (e: Exception) {
                // The record still names the source; the copy is removed and the source was never touched
                undoInstall(entry)
                return@withItem failed("could not save moved $id: ${e.message}")
              }
              step("saved")
              entry.phase = MovePhase.COMMITTED
              entry.commitEpoch = epoch.current()
              journal.put(entry)
              step("committed")
              MoveResult.Moved
            }
    if (result is MoveResult.Moved) healLastSession(db)
    return result
  }

  private fun copyAndVerify(src: File, tmp: File, entry: StorageMoveJournalEntry): String? {
    if (!io.mkdirs(tmp)) return "could not create ${tmp.absolutePath}"
    val dirs = linkedSetOf(tmp)
    for (f in entry.files) {
      val from = File(src, f.path)
      val to = File(tmp, f.path)
      to.parentFile?.let { parent ->
        if (!io.mkdirs(parent)) return "could not create ${parent.absolutePath}"
        dirs += parent
      }
      if (io.kind(from) != NodeKind.FILE) return "${from.absolutePath} is no longer a regular file"
      f.sha256 = io.copyAndSync(from, to, cancelled)
      step("copied:${f.path}")
      if (io.length(from) != f.size) return "${from.absolutePath} changed during the copy"
    }
    // Entries of every copied directory, deepest first; best effort (see the class comment)
    // asReversed: LinkedHashSet.reversed() is the Java 21 SequencedCollection method, missing below API 35
    dirs.toList().asReversed().forEach { io.syncDir(it) }
    for (f in entry.files) {
      val to = File(tmp, f.path)
      if (io.kind(to) != NodeKind.FILE || io.length(to) != f.size) return "copy of ${f.path} is incomplete (${io.length(to)} of ${f.size} bytes)"
      val sha = io.sha256(to, cancelled)
      if (sha != f.sha256) return "copy of ${f.path} does not match its source (SHA-256)"
    }
    return null
  }

  private fun rollbackCopy(entry: StorageMoveJournalEntry) {
    val tmp = File(entry.tempDir)
    if (tmp.name.startsWith(TMP_PREFIX) && !SafeTree.delete(io, tmp)) {
      AbsLogger.error(TAG, "Could not remove ${tmp.absolutePath}; removed when its storage is next checked")
    }
    journal.remove(entry.itemId)
  }

  /** The destination copy is removed only while the record does not use it and its volume is verified. */
  private fun undoInstall(entry: StorageMoveJournalEntry): Boolean {
    if (!AppStorage.isVerified(entry.toFolderId, probe)) return false
    if (!SafeTree.delete(io, File(entry.destDir))) {
      AbsLogger.error(TAG, "Could not remove the unused copy ${entry.destDir}; kept in the journal")
      return false
    }
    journal.remove(entry.itemId)
    return true
  }

  /**
   * Finishes or undoes every journaled move. Safe to call at any time and any number of times; it never deletes a
   * file the item record uses, never deletes a source whose destination was not matched after a restart, and never
   * deletes anything on a volume that is missing or is not the registered card.
   */
  fun recover(): RecoverySummary = synchronized(operationLock) { recoverLocked() }

  private fun recoverLocked(): RecoverySummary {
    var rolledBack = 0
    var committed = 0
    var retired = 0
    var reverted = 0
    var waiting = 0
    var attention = 0
    for (entry in journal.all()) {
      try {
        when (recoverOne(entry)) {
          Outcome.ROLLED_BACK -> rolledBack++
          Outcome.COMMITTED -> committed++
          Outcome.RETIRED -> retired++
          Outcome.REVERTED -> reverted++
          Outcome.WAITING -> waiting++
          Outcome.ATTENTION -> attention++
        }
      } catch (e: CopyCancelledException) {
        waiting++
      } catch (e: Exception) {
        AbsLogger.error(TAG, "Recovery of the move of ${entry.itemId} failed (${e.javaClass.simpleName}: ${e.message}); kept for the next pass")
        waiting++
      }
    }
    sweepTemporaryFolders()
    return RecoverySummary(rolledBack, committed, retired, reverted, waiting, attention)
  }

  private enum class Outcome { ROLLED_BACK, COMMITTED, RETIRED, REVERTED, WAITING, ATTENTION }

  private fun recoverOne(entry: StorageMoveJournalEntry): Outcome =
          when (entry.phase) {
            MovePhase.COPYING, MovePhase.VERIFIED -> {
              // The record never pointed at the copy
              rollbackCopy(entry)
              Outcome.ROLLED_BACK
            }
            // Reading the record and undoing the install must not interleave with the commit, which runs under the lock
            MovePhase.INSTALLED -> LocalItemLocks.withItem(entry.itemId) {
                val item = db.getItem(entry.itemId)
                if (item != null && samePath(item.absolutePath, entry.destDir)) {
                  // The record was saved before the crash: committed, but this boot's reads prove nothing
                  entry.phase = MovePhase.COMMITTED
                  entry.commitEpoch = epoch.current()
                  journal.put(entry)
                  Outcome.COMMITTED
                } else if (undoInstall(entry)) {
                  Outcome.ROLLED_BACK
                } else {
                  Outcome.WAITING
                }
            }
            // Hashing runs outside the item lock, so playback of the item is never held up by it
            MovePhase.COMMITTED -> retireOrRevert(entry)
            // The source is no record's folder any more (the record points at the destination)
            MovePhase.RETIRING -> if (retireSource(entry)) Outcome.RETIRED else Outcome.WAITING
            MovePhase.NEEDS_ATTENTION -> Outcome.ATTENTION
          }

  private fun retireOrRevert(entry: StorageMoveJournalEntry): Outcome {
    val now = epoch.current()
    if (entry.commitEpoch == null || now == null || now == entry.commitEpoch) return Outcome.WAITING
    // Deleting needs the source's card, and checking the copy needs the destination's: both verified
    if (!AppStorage.isVerified(entry.fromFolderId, probe) || !AppStorage.isVerified(entry.toFolderId, probe)) return Outcome.WAITING
    val version = LocalItemLocks.version(entry.itemId)
    val item = LocalItemLocks.withItem(entry.itemId) { db.getItem(entry.itemId) }
    val dest = File(entry.destDir)
    if (item == null) {
      // The user deleted the item (its folder went with it): the old source is no longer anyone's copy
      if (io.kind(dest) != NodeKind.MISSING) return Outcome.WAITING
      entry.phase = MovePhase.RETIRING
      journal.put(entry)
      return if (retireSource(entry)) Outcome.RETIRED else Outcome.WAITING
    }
    if (!samePath(item.absolutePath, entry.destDir)) return attention(entry, "the item no longer uses the moved folder")
    if (destinationMatches(entry)) {
      entry.phase = MovePhase.RETIRING
      journal.put(entry)
      step("retiring")
      return if (retireSource(entry)) Outcome.RETIRED else Outcome.WAITING
    }
    // The copy did not survive (power loss, a card pulled during writeback): go back to the untouched source
    return revert(entry, version)
  }

  private fun destinationMatches(entry: StorageMoveJournalEntry): Boolean =
          entry.files.all { f ->
            val file = File(entry.destDir, f.path)
            io.kind(file) == NodeKind.FILE && io.length(file) == f.size && io.sha256(file, cancelled) == f.sha256
          }

  private fun sourceMatches(entry: StorageMoveJournalEntry): Boolean =
          entry.files.all { f ->
            val file = File(entry.sourceDir, f.path)
            io.kind(file) == NodeKind.FILE && io.length(file) == f.size && io.sha256(file, cancelled) == f.sha256
          }

  private fun revert(entry: StorageMoveJournalEntry, version: Long): Outcome {
    val destRoot = File(entry.destDir).parentFile ?: return attention(entry, "no destination root")
    val sourceRoot = File(entry.sourceDir).parentFile ?: return attention(entry, "no source root")
    if (!sourceMatches(entry)) return attention(entry, "the moved copy and the original both failed their check; both are kept")
    val manifestPaths = entry.files.map { AppStorage.normalizedPath(File(entry.destDir, it.path)) }.toSet()
    val outcome =
            LocalItemLocks.withItem(entry.itemId) {
              // Changed while the source was being checked: decide again on the next pass
              if (LocalItemLocks.version(entry.itemId) != version) return@withItem Outcome.WAITING
              val item = db.getItem(entry.itemId) ?: return@withItem Outcome.WAITING
              // Files downloaded into the destination after the move have no copy in the source: going back would lose them
              if (!item.localFiles.all { AppStorage.normalizedPath(File(it.absolutePath)) in manifestPaths }) {
                return@withItem attention(entry, "the moved copy failed its check after a restart and has newer files; both copies are kept")
              }
              val folder = folderFor(entry.fromFolderId, item.mediaType) ?: return@withItem attention(entry, "unknown source folder")
              db.saveItem(relocate(item, destRoot, sourceRoot, folder))
              Outcome.REVERTED
            }
    if (outcome != Outcome.REVERTED) return outcome
    AbsLogger.error(TAG, "The moved copy of ${entry.itemId} failed its check after a restart; the item uses its original files again")
    healLastSession(db)
    // The failed copy is no record's folder now; a delete that fails leaves it for a later pass
    if (SafeTree.delete(io, File(entry.destDir))) journal.remove(entry.itemId)
    else attention(entry, "the item went back to its original files; the failed copy could not be removed")
    return Outcome.REVERTED
  }

  private fun folderFor(folderId: String, mediaType: String): LocalFolder? {
    if (folderId.startsWith(AppStorage.INTERNAL_PREFIX)) return AppStorage.folderFor(app.absplus.android.device.StorageTarget.Internal, mediaType)
    val uuid = AppStorage.uuidOf(folderId) ?: return null
    return AppStorage.folderFor(app.absplus.android.device.StorageTarget.Sd(uuid), mediaType)
  }

  private fun attention(entry: StorageMoveJournalEntry, why: String): Outcome {
    AbsLogger.error(TAG, "Move of ${entry.itemId} needs attention: $why")
    entry.phase = MovePhase.NEEDS_ATTENTION
    entry.note = why
    journal.put(entry)
    return Outcome.ATTENTION
  }

  /** Deletes the source's manifest files (only those) and then its emptied folders; true once none is left. */
  private fun retireSource(entry: StorageMoveJournalEntry): Boolean {
    if (!AppStorage.isVerified(entry.fromFolderId, probe)) return false
    val src = File(entry.sourceDir)
    val root = src.parentFile ?: return false
    var left = 0
    for (f in entry.files) {
      val file = File(src, f.path)
      when (io.kind(file)) {
        NodeKind.MISSING -> {}
        NodeKind.FILE -> if (!io.delete(file)) left++
        // Never there when the manifest was made; not ours to delete
        else -> AbsLogger.error(TAG, "Left ${file.absolutePath}: no longer a regular file")
      }
      step("retired:${f.path}")
      file.parentFile?.let { SafeTree.pruneEmptyDirs(io, it, root) }
    }
    if (left > 0) return false
    SafeTree.pruneEmptyDirs(io, src, root)
    journal.remove(entry.itemId)
    AbsLogger.info(TAG, "Removed the old copy of ${entry.itemId} at ${entry.sourceDir}")
    return true
  }

  /** Temporary copies no journal entry refers to (an undo whose delete failed) are only ever leftovers. */
  private fun sweepTemporaryFolders() {
    val active = journal.all().map { AppStorage.normalizedPath(File(it.tempDir)) }.toSet()
    val roots = mutableListOf(File(probe.internalFilesDir(), "downloads"))
    probe.volumes().forEach { v ->
      val uuid = v.uuid
      if (v.removable && uuid != null && AppStorage.isVerified(AppStorage.appsdFolderId(uuid, "podcast"), probe)) {
        AppStorage.downloadsRoot(AppStorage.appsdFolderId(uuid, "podcast"), probe)?.let { roots += it }
      }
    }
    for (root in roots) {
      if (io.kind(root) != NodeKind.DIRECTORY) continue
      io.list(root)?.filter { it.startsWith(TMP_PREFIX) }?.forEach { name ->
        val dir = File(root, name)
        if (AppStorage.normalizedPath(dir) !in active) {
          AbsLogger.info(TAG, "Removing leftover temporary copy ${dir.absolutePath}")
          SafeTree.delete(io, dir)
        }
      }
    }
  }

  private fun samePath(a: String, b: String): Boolean =
          a.isNotEmpty() && AppStorage.normalizedPath(File(a)) == AppStorage.normalizedPath(File(b))

  private fun failed(message: String): MoveResult.Failed {
    AbsLogger.error(TAG, message)
    return MoveResult.Failed(message)
  }

  companion object {
    private const val TAG = "StorageMover"
    private val operationLock = Any()
    const val TMP_PREFIX = ".moving-"

    private val mapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

    /** Whether a recovery pass has something it can do now (anything but a move waiting for the next restart). */
    fun recoveryPending(journal: MoveJournal, epoch: BootEpoch): Boolean {
      val now = epoch.current()
      return journal.all().any {
        when (it.phase) {
          MovePhase.COMMITTED -> it.commitEpoch != null && now != null && now != it.commitEpoch
          MovePhase.NEEDS_ATTENTION -> false
          else -> true
        }
      }
    }

    /** A copy of [item] with every path under [fromRoot] re-pointed to [toRoot]; [item] is not changed. */
    fun relocate(item: LocalLibraryItem, fromRoot: File, toRoot: File, toFolder: LocalFolder): LocalLibraryItem {
      val copy = mapper.readValue(mapper.writeValueAsString(item), LocalLibraryItem::class.java)
      val outside = mutableListOf<String>()

      fun path(p: String?): String? {
        if (p.isNullOrEmpty()) return p
        val moved = movedFile(File(p), fromRoot, toRoot)
        if (moved == null) outside.add(p)
        return moved?.absolutePath ?: p
      }

      fun url(u: String?): String? {
        if (u.isNullOrEmpty()) return u
        val filePath = if (u.startsWith("file:")) u.toUri().path else null
        val moved = filePath?.let { movedFile(File(it), fromRoot, toRoot) }
        if (moved == null) outside.add(u)
        return moved?.let { Uri.fromFile(it).toString() } ?: u
      }

      copy.folderId = toFolder.id
      copy.absolutePath = path(copy.absolutePath)!!
      copy.basePath = path(copy.basePath)!!
      copy.contentUrl = url(copy.contentUrl)!!
      copy.coverAbsolutePath = path(copy.coverAbsolutePath)
      copy.coverContentUrl = url(copy.coverContentUrl)
      copy.localFiles.forEach {
        it.absolutePath = path(it.absolutePath)!!
        it.contentUrl = url(it.contentUrl)!!
      }
      val media = copy.media
      val tracks =
              when (media) {
                is Book -> media.tracks.orEmpty()
                is Podcast -> media.episodes.orEmpty().mapNotNull { it.audioTrack }
                else -> emptyList()
              }
      tracks.forEach { track ->
        track.contentUrl = url(track.contentUrl)!!
        track.metadata?.let { it.path = path(it.path)!! }
      }
      if (media is Book) media.ebookFile?.let { it.contentUrl = url(it.contentUrl) }

      if (outside.isNotEmpty()) {
        AbsLogger.error(TAG, "relocate ${item.id}: ${outside.size} paths outside ${fromRoot.absolutePath} left as they were: $outside")
      }
      return copy
    }

    private fun movedFile(file: File, fromRoot: File, toRoot: File): File? {
      val rel = AppStorage.relativePath(file, fromRoot) ?: return null
      return if (rel.isEmpty()) toRoot else File(toRoot, rel)
    }

    /** Re-points the saved last session to the item's current files, so a restored session does not play the old paths. */
    fun healLastSession(db: MoverDb) {
      val s = db.lastSession() ?: return
      val lli = s.localLibraryItem ?: return
      val cur = db.getItem(lli.id) ?: return
      val curTracks = cur.media.getAudioTracks().filter { it.localFileId != null }.associateBy { it.localFileId }
      val stale =
              lli.absolutePath != cur.absolutePath ||
                      lli.folderId != cur.folderId ||
                      s.audioTracks.any { t -> curTracks[t.localFileId]?.let { it.contentUrl != t.contentUrl } == true }
      if (!stale) return
      s.localLibraryItem = cur
      s.audioTracks.forEach { t ->
        val match = curTracks[t.localFileId] ?: return@forEach
        t.contentUrl = match.contentUrl
        t.metadata = match.metadata?.copy()
      }
      db.saveLastSession(s)
    }
  }
}
