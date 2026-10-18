package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderAccess
import app.absplus.android.device.LocalItemDeleter
import app.absplus.android.device.VolumeProbe
import app.absplus.android.managers.AutoDeletePolicy.Candidate
import app.absplus.android.managers.AutoDeletePolicy.Identity
import app.absplus.android.managers.AutoDeletePolicy.Progress
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.User
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.plugins.AbsLogger
import com.anggrayudi.storage.file.DocumentFileCompat
import java.io.File

/** Collects downloaded books that may be removed, asks [AutoDeletePolicy] which, and deletes them. */
object AutoDeleteManager {
  private const val TAG = "AutoDeleteManager"
  private const val PREFS = "absplus_auto_delete"
  private const val KEY_SKIPPED_FOR_LIMIT = "skippedForLimit"

  /** Called after a pass deleted something, so the UI can reload its local items. */
  @Volatile var onDeleted: (() -> Unit)? = null

  /** The storage move journal; replaceable in tests. */
  @Volatile internal var journal: MoveJournal = PaperMoveJournal

  data class FreeResult(val enough: Boolean, val freedBytes: Long)

  private class Target(val candidate: Candidate, val lli: LocalLibraryItem, val protection: String?)

  private class Collected(val targets: List<Target>, val usedBytes: Long)

  fun getSkippedForLimit(ctx: Context): Int = prefs(ctx).getInt(KEY_SKIPPED_FOR_LIMIT, 0)

  fun setSkippedForLimit(ctx: Context, count: Int) {
    prefs(ctx).edit().putInt(KEY_SKIPPED_FOR_LIMIT, count).apply()
  }

  private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun settings(): DeviceSettings = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  /**
   * Whether a cleanup pass has anything to do. The limit alone does not: room is made only by deleting finished books,
   * and only with their toggle on.
   */
  fun isCleanupEnabled(settings: DeviceSettings): Boolean = settings.autoDeleteFinishedBooks

  /** Whether a download's storage can be used now: the registered SD card in the device, or a device folder the app can still write. */
  private class Storages(private val ctx: Context, val probe: VolumeProbe) {
    private val cache = HashMap<String, Boolean>()

    fun usable(folderId: String): Boolean = cache.getOrPut(folderId) {
      if (AppStorage.isAppStorage(folderId)) AppStorage.isVerified(folderId, probe)
      else DeviceManager.dbManager.getLocalFolder(folderId)?.let { FolderAccess.canWrite(ctx, it, probe) } == true
    }

    /** The storage is usable and, for a device folder, the saved links to the book's folder and files resolve. */
    fun usable(lli: LocalLibraryItem): Boolean =
            usable(lli.folderId) && (AppStorage.isAppStorage(lli.folderId) || linksResolve(lli))

    // Such an item would only be skipped by the deleter, so it is no candidate and takes no room in the total
    private fun linksResolve(lli: LocalLibraryItem): Boolean =
            DocumentFileCompat.fromUri(ctx, Uri.parse(lli.contentUrl)) != null &&
                    lli.localFiles.all { DocumentFileCompat.fromUri(ctx, Uri.parse(it.contentUrl)) != null }
  }

  /** What server downloads take on the usable storages (internal, the registered SD card, device folders), as the downloads limit counts it. */
  fun usedBytes(ctx: Context, probe: VolumeProbe = AndroidVolumeProbe(ctx)): Long {
    val storages = Storages(ctx, probe)
    return serverDownloads(storages).sumOf { lli -> lli.localFiles.sumOf { it.size } }
  }

  /**
   * Bytes the queued, not yet saved downloads are going to add; downloads into an unusable storage add nothing now. A
   * download whose files are all in place but not yet saved as a local item still counts, apart from the files the saved
   * item already lists (those are in [usedBytes]).
   */
  fun queuedBytes(ctx: Context, probe: VolumeProbe = AndroidVolumeProbe(ctx)): Long {
    val storages = Storages(ctx, probe)
    val locals = DeviceManager.dbManager.getLocalLibraryItems()
    return DeviceManager.dbManager.getDownloadItems().filter { storages.usable(it.localFolder.id) }.sumOf { item ->
      when {
        isActiveDownload(item) -> item.downloadItemParts.filter { !it.failed }.sumOf { it.fileSize }
        item.isDownloadFinished -> {
          val saved = locals.filter { it.libraryItemId == item.libraryItemId }.flatMap { l -> l.localFiles.mapNotNull { it.filename } }.toSet()
          item.downloadItemParts.filter { it.filename !in saved }.sumOf { it.fileSize }
        }
        else -> 0L
      }
    }
  }

  /** What a retry of a failed download adds to the total: the files that are not downloaded yet and not counted by [queuedBytes]. */
  internal fun retryExtraBytes(item: DownloadItem): Long =
          item.downloadItemParts.filter { it.failed && !it.moved && !it.completed }.sumOf { it.fileSize }

  // Items on an unusable storage are neither candidates nor part of the total: they take no room on a usable volume
  private fun serverDownloads(storages: Storages) = allServerDownloads().filter { storages.usable(it) }

  private fun allServerDownloads() =
          DeviceManager.dbManager.getLocalLibraryItems().filter { it.libraryItemId != null && it.serverConnectionConfigId != null }

  /** Called when a download finished and was saved, before it leaves the download queue and its protection. */
  fun recordDownloaded(lli: LocalLibraryItem, now: Long = System.currentTimeMillis()) {
    if (lli.mediaType == "book") DeviceManager.dbManager.saveAutoDeleteDownloadedAt(lli.id, now)
  }

  private fun isActiveDownload(item: DownloadItem) =
          !item.isDownloadFinished && item.downloadItemParts.any { !it.failed }

  /** The cleanup pass: finished books past the delay, then whatever keeps the total over the limit. */
  fun runCleanup(ctx: Context, user: User?): Int {
    DbManager.initialize(ctx)
    val settings = settings()
    if (!isCleanupEnabled(settings)) return 0
    val now = System.currentTimeMillis()

    val first = collect(ctx, user, record = true)
    logSkipped(first, settings, now)
    val due = AutoDeletePolicy.selectDue(first.targets.map { it.candidate }, settings, now)
    var deleted = deleteAll(ctx, first, due, "finished", Basis.Due)

    val limit = AutoDeletePolicy.limitBytes(settings)
    if (limit != null) {
      val current = if (deleted.count > 0) collect(ctx, user, record = false) else first
      if (current.usedBytes > limit) {
        // The whole ordered list: a candidate that is skipped or fails is followed by the next, and the recheck stops at the limit
        val ordered = AutoDeletePolicy.orderedForSpace(current.targets.map { it.candidate }, settings)
        val result = deleteAll(ctx, current, ordered, "to make room", Basis.Space(0))
        deleted = Deleted(deleted.count + result.count, deleted.bytes + result.bytes)
        if (result.bytes < current.usedBytes - limit) AbsLogger.info(TAG, "Downloads are still over the limit: nothing more can be deleted")
      }
    }
    if (deleted.count > 0) onDeleted?.invoke()
    return deleted.count
  }

  /**
   * Deletes finished books, longest finished first, so that a download of [addBytes] fits under the limit next to what
   * downloads take now and what is queued. The deficit is worked out here, from fresh numbers.
   */
  fun freeSpaceFor(ctx: Context, addBytes: Long, user: User?): FreeResult {
    DbManager.initialize(ctx)
    val limit = AutoDeletePolicy.limitBytes(settings()) ?: return FreeResult(true, 0)
    val collected = collect(ctx, user, record = false)
    // What the download needs on top of everything already counted, so the deficit can be recomputed before each delete
    val extra = addBytes + queuedBytes(ctx)
    val deficit = collected.usedBytes + extra - limit
    if (deficit <= 0) return FreeResult(true, 0)
    val selection = AutoDeletePolicy.selectForSpace(collected.targets.map { it.candidate }, settings(), deficit)
    // Deleting some of it would lose downloads without making the auto download fit
    if (!selection.enough) return FreeResult(false, 0)
    val deleted = deleteAll(ctx, collected, AutoDeletePolicy.orderedForSpace(collected.targets.map { it.candidate }, settings()), "to make room", Basis.Space(extra, mustFit = true))
    if (deleted.count > 0) onDeleted?.invoke()
    // Whether the download fits now, however the pass ended (room freed here or elsewhere, the limit raised, items skipped)
    return FreeResult(!overLimitNow(ctx, extra), deleted.bytes)
  }

  private class Deleted(val count: Int, val bytes: Long)

  /** Why a candidate is deleted, checked again just before the delete. */
  private sealed class Basis {
    object Due : Basis()
    // extra: what the auto download adds to the total (0 for the periodic cleanup); mustFit: stop deleting once the
    // candidates left cannot cover the deficit, since a partial delete would lose books without making the download fit
    class Space(val extra: Long, val mustFit: Boolean = false) : Basis()
  }

  // The limit may have been switched off or raised, or space freed elsewhere, since the candidates were chosen
  private fun overLimitNow(ctx: Context, extra: Long): Boolean {
    val limit = AutoDeletePolicy.limitBytes(settings()) ?: return false
    return usedBytes(ctx) + extra > limit
  }

  private fun deleteAll(ctx: Context, collected: Collected, selected: List<Candidate>, reason: String, basis: Basis): Deleted {
    val byKey = collected.targets.associateBy { it.candidate.key }
    var count = 0
    var bytes = 0L
    for ((index, candidate) in selected.withIndex()) {
      val target = byKey[candidate.key] ?: continue
      if (basis is Basis.Space && basis.mustFit) {
        val limit = AutoDeletePolicy.limitBytes(settings()) ?: break
        val deficit = usedBytes(ctx) + basis.extra - limit
        if (selected.drop(index).sumOf { it.sizeBytes } < deficit) break
      }
      // Under the item's lock nothing else writes the item (a move commit, a finishing download) between the
      // re-check and the delete; playback, downloads and settings may have changed since the candidates were collected
      val step = LocalItemLocks.withItem(target.lli.id) {
        when (recheck(ctx, target, basis)) {
          Recheck.STOP -> Step.STOP
          Recheck.SKIP -> Step.SKIP
          Recheck.OK -> {
            afterRecheck()
            delete(ctx, target)
          }
        }
      }
      when (step) {
        Step.STOP -> break
        Step.SKIP -> continue
        Step.DELETED -> {
          count++
          bytes += candidate.sizeBytes
          AbsLogger.info(TAG, "Auto delete ($reason): removed \"${target.lli.title}\", freed ${candidate.sizeBytes} bytes")
        }
        // The deleter logged why; not a failure, not freed space, and the pass goes on
        Step.NOT_DELETED -> continue
        Step.FAILED -> AbsLogger.error(TAG, "Auto delete ($reason): could not remove \"${target.lli.title}\"")
      }
    }
    return Deleted(count, bytes)
  }

  private enum class Recheck { OK, SKIP, STOP }

  private enum class Step { STOP, SKIP, DELETED, FAILED, NOT_DELETED }

  // Called under the item's lock, so the item read here is the one the delete will see
  private fun recheck(ctx: Context, target: Target, basis: Basis): Recheck {
    val settings = settings()
    if (!isCleanupEnabled(settings)) return Recheck.STOP
    when (basis) {
      is Basis.Due -> {
        val c = target.candidate
        val state = AutoDeletePolicy.finishedState(c.local, c.server, c.serverKnown)
        if (!state.finished || state.finishedAt == null || !AutoDeletePolicy.isDue(state.finishedAt, AutoDeletePolicy.delayDays(settings), System.currentTimeMillis())) return Recheck.SKIP
      }
      // Counted afresh each time: space may have been freed elsewhere, the limit raised, or a download queued meanwhile
      is Basis.Space -> if (!overLimitNow(ctx, basis.extra)) return Recheck.STOP
    }
    val fresh = DeviceManager.dbManager.getLocalLibraryItem(target.lli.id) ?: return Recheck.SKIP
    // A card or folder can go away between two deletes of one pass, so nothing is cached across candidates
    if (!Storages(ctx, AndroidVolumeProbe(ctx)).usable(fresh)) return Recheck.SKIP
    val guards = Guards(ctx)
    if (guards.protection(fresh) != null || guards.hasPending(fresh)) return Recheck.SKIP
    val progress = DeviceManager.dbManager.getLocalMediaProgress(target.candidate.key)
    if (progress?.lastUpdate != target.candidate.local?.lastUpdate) return Recheck.SKIP
    // A download that finished again since the pass began resets what counts as finished
    if (DeviceManager.dbManager.getAutoDeleteDownloadedAt(target.candidate.key) != target.candidate.downloadedAt) return Recheck.SKIP
    // Server progress counted only for the owner of the download; the connected account must still be that owner
    if (target.candidate.serverKnown && !ownerIsConnected(fresh, progress?.serverUserId)) return Recheck.SKIP
    // A relink or an account switch since the pass collected it changes what the server progress and confirmations mean
    if (identityOf(fresh, progress?.serverUserId) != target.candidate.identity) return Recheck.SKIP
    return Recheck.OK
  }

  /** The connected account is the one the download and its progress belong to. */
  private fun ownerIsConnected(lli: LocalLibraryItem, progressUserId: String?): Boolean {
    val config = DeviceManager.serverConnectionConfig ?: return false
    // The item's own owner, else the one its progress was recorded for; both, when set, must be the connected account
    val owner = lli.serverUserId ?: progressUserId ?: return false
    return config.id == lli.serverConnectionConfigId && config.userId == owner &&
            (lli.serverUserId == null || progressUserId == null || progressUserId == lli.serverUserId)
  }

  private fun identityOf(lli: LocalLibraryItem, serverUserId: String?) =
          Identity(lli.serverConnectionConfigId, serverUserId, lli.libraryItemId)

  /** Runs between the last re-check and the delete; lets a test change the world there. */
  @Volatile internal var afterRecheck: () -> Unit = {}

  // The deleter asks once more under the same lock, right before the first file goes, whether the progress is still the one decided on
  private fun delete(ctx: Context, target: Target): Step =
          when (LocalItemDeleter.deleteItem(ctx, target.lli.id, auto = true) { progressUnchanged(target) }) {
            LocalItemDeleter.Outcome.DELETED -> Step.DELETED
            LocalItemDeleter.Outcome.FAILED -> Step.FAILED
            LocalItemDeleter.Outcome.SKIPPED, LocalItemDeleter.Outcome.STORAGE_UNAVAILABLE -> Step.NOT_DELETED
          }

  private fun progressUnchanged(target: Target): Boolean {
    val c = target.candidate
    val progress = DeviceManager.dbManager.getLocalMediaProgress(c.key)
    return progress?.lastUpdate == c.local?.lastUpdate && progress?.isFinished == c.local?.isFinished
  }

  private fun logSkipped(collected: Collected, settings: DeviceSettings, now: Long) {
    var inPlayer = 0
    var downloading = 0
    var moving = 0
    var notOnServer = 0
    val delay = AutoDeletePolicy.delayDays(settings)
    for (target in collected.targets) {
      val c = target.candidate
      val state = AutoDeletePolicy.finishedState(c.local, c.server, c.serverKnown)
      if (!state.finished || state.finishedAt == null || !AutoDeletePolicy.isDue(state.finishedAt, delay, now)) continue
      when {
        target.protection == PROTECTED_PLAYER -> inPlayer++
        target.protection == PROTECTED_DOWNLOADING -> downloading++
        target.protection == PROTECTED_MOVING -> moving++
        !AutoDeletePolicy.isSyncSafe(c) -> notOnServer++
      }
    }
    if (inPlayer + downloading + moving + notOnServer > 0) {
      AbsLogger.info(TAG, "Auto delete skipped: in the player $inPlayer, downloading $downloading, being moved to another storage $moving, progress not on the server yet $notOnServer")
    }
  }

  private const val PROTECTED_PLAYER = "in the player"
  private const val PROTECTED_DOWNLOADING = "downloading"
  private const val PROTECTED_MOVING = "being moved"

  private class Guards(ctx: Context) {
    private val db = DeviceManager.dbManager
    private val playerRefs = protectedRefs(ctx)
    private val pendingRefs = db.getPlaybackSessions().flatMap { sessionRefs(it) }
    // Includes finished downloads still being turned into a local item: their files are in place but the item is not saved yet
    private val downloadingItemIds = db.getDownloadItems().filter { item -> item.downloadItemParts.any { !it.failed } }.map { it.libraryItemId }.toSet()

    fun protection(lli: LocalLibraryItem): String? = when {
      playerRefs.any { matches(it, lli) } || LocalItemLocks.isPinned(lli.id) -> PROTECTED_PLAYER
      lli.libraryItemId in downloadingItemIds -> PROTECTED_DOWNLOADING
      // Any phase of the mover's journal: the item is being copied, or its old copy is not retired yet
      journal.get(lli.id) != null -> PROTECTED_MOVING
      else -> null
    }

    fun hasPending(lli: LocalLibraryItem): Boolean = pendingRefs.any { matches(it, lli) }
  }

  /** The user's server progress counts only while the connected account is still the one it was fetched for. */
  fun isUserCurrent(user: User?): Boolean {
    val config = DeviceManager.serverConnectionConfig ?: return false
    return user != null && config.userId == user.id
  }

  private fun collect(ctx: Context, user: User?, record: Boolean): Collected {
    val db = DeviceManager.dbManager
    val storages = Storages(ctx, AndroidVolumeProbe(ctx))
    val everything = allServerDownloads()
    val items = everything.filter { storages.usable(it) }
    if (record && items.size < everything.size) {
      AbsLogger.info(TAG, "Auto delete: ${everything.size - items.size} items on an unavailable storage are left alone and not counted")
    }
    val used = items.sumOf { lli -> lli.localFiles.sumOf { it.size } }
    val guards = Guards(ctx)
    val currentServerId = DeviceManager.serverConnectionConfig?.id
    val knownUser = if (isUserCurrent(user)) user else null

    val targets = mutableListOf<Target>()
    for (lli in items) {
      // A podcast is managed by its own auto download settings; a book with only an ebook is never deleted
      if (lli.mediaType != "book" || lli.media.getAudioTracks().isEmpty()) continue
      val progressId = lli.id
      val localProgress = db.getLocalMediaProgress(progressId)
      val local = localProgress?.let { Progress(it.isFinished, it.finishedAt, it.lastUpdate, it.ebookLocation) }
      // Server progress is the connected account's: only for a download that account owns
      val serverKnown = knownUser != null && currentServerId == lli.serverConnectionConfigId &&
              ownerIsConnected(lli, localProgress?.serverUserId)
      val server = if (serverKnown) {
        knownUser!!.mediaProgress.firstOrNull { it.libraryItemId == lli.libraryItemId && it.episodeId == null }
                ?.let { Progress(it.isFinished, it.finishedAt, it.lastUpdate, it.ebookLocation) }
      } else null
      val protection = guards.protection(lli)
      val candidate = Candidate(
              progressId, lli.localFiles.sumOf { it.size }, local, server, serverKnown,
              db.getAutoDeleteConfirmed(progressId), guards.hasPending(lli), protection != null,
              identityOf(lli, localProgress?.serverUserId), downloadedAtOf(ctx, lli)
      )
      if (record && AutoDeletePolicy.shouldConfirm(candidate)) {
        val confirmation = AutoDeletePolicy.confirmationFor(candidate)
        if (candidate.confirmed != confirmation) db.saveAutoDeleteConfirmed(progressId, confirmation)
      } else if (record && candidate.serverKnown && candidate.confirmed != null) {
        // A trustworthy answer from the owner no longer backs the confirmation (the server record is gone or older)
        db.removeAutoDeleteConfirmed(progressId)
      }
      targets.add(Target(candidate, lli, protection))
    }

    if (record) {
      // Keys are listed before the items are read again: one written meanwhile (a download that just finished) is left alone
      val storedConfirmed = db.getAutoDeleteConfirmedKeys()
      val storedDownloadedAt = db.getAutoDeleteDownloadedAtKeys()
      val keys = allServerDownloads().map { it.id }.toSet()
      storedConfirmed.filter { it !in keys }.forEach { db.removeAutoDeleteConfirmed(it) }
      storedDownloadedAt.filter { it !in keys }.forEach { db.removeAutoDeleteDownloadedAt(it) }
    }
    return Collected(targets, used)
  }

  /** When the download finished; for one from before the time was recorded, the newest change of its files (kept from then on). */
  private fun downloadedAtOf(ctx: Context, lli: LocalLibraryItem): Long? {
    val db = DeviceManager.dbManager
    db.getAutoDeleteDownloadedAt(lli.id)?.let { return it }
    val newest = lli.localFiles.maxOfOrNull { fileModifiedAt(ctx, lli, it) } ?: 0L
    if (newest <= 0L) return null
    db.saveAutoDeleteDownloadedAt(lli.id, newest)
    return newest
  }

  private fun fileModifiedAt(ctx: Context, lli: LocalLibraryItem, file: LocalFile): Long =
          try {
            if (AppStorage.isAppStorage(lli.folderId)) File(file.absolutePath).lastModified()
            else DocumentFileCompat.fromUri(ctx, Uri.parse(file.contentUrl))?.lastModified() ?: 0L
          } catch (e: Exception) {
            0L
          }

  private fun matches(ref: String, lli: LocalLibraryItem): Boolean = ref == lli.id || ref == lli.libraryItemId

  private fun sessionRefs(session: PlaybackSession): List<String> =
          listOfNotNull(session.libraryItemId, session.localLibraryItem?.id)

  // The live service knows the current session and queue; the saved ones are what a widget or headset Play resumes, and the
  // live service may be up with nothing loaded yet, so both count
  private fun protectedRefs(ctx: Context): List<String> {
    val refs = mutableListOf<String>()
    val live = PlayerNotificationService.live
    if (live != null) {
      live.currentPlaybackSession?.let { refs.addAll(sessionRefs(it)) }
      live.playlistQueue.forEach { refs.add(it.libraryItemId) }
    }
    if (PlaybackRestoreStore.isResumable(ctx)) {
      PlaybackRestoreStore.loadQueue(ctx).items.forEach { refs.add(it.libraryItemId) }
      DeviceManager.deviceData.lastPlaybackSession?.let { refs.addAll(sessionRefs(it)) }
    }
    return refs
  }
}
