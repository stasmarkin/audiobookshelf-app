package app.absplus.android.managers

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import java.io.File
import java.security.MessageDigest

/**
 * Who a download destination belongs to (R29-N02). Different server paths can flatten to one local filename
 * ("Season1/episode.mp3" and "Season1_episode.mp3"), and the same item id can come from two servers, so a file is only
 * ever replaced by the download that already owns it. Ownership is read from the existing local item records and the
 * download queue; nothing new is persisted and existing downloads keep their names.
 */
object DownloadDestinationGuard {
  /** One downloadable resource: a server account (user id and/or saved config), a library item and one file of it. */
  data class Owner(val userId: String, val configId: String, val libraryItemId: String, val resource: String)

  /** Book records do not say which server file a local file came from: any file of the same book matches. */
  const val ANY_ITEM_FILE = "file:*"

  enum class Verdict {
    /** Nothing on disk or on record at the destination. */
    FREE,
    /** Only this download's own earlier copy is there (a legitimate retry or re-download). */
    SAME_OWNER,
    /** A file is there that no record or queued download accounts for (e.g. after a crash before registration). */
    UNKNOWN_FILE,
    /** Another episode, file, item or server owns the destination: never replace it. */
    OTHER_OWNER
  }

  /**
   * Same server account: the server's user ids when both are known (stable across address changes), else the saved
   * config ids when both are known. Nothing to compare means not provably the same, so never the same owner.
   */
  fun sameServer(userA: String?, configA: String?, userB: String?, configB: String?): Boolean = when {
    !userA.isNullOrBlank() && !userB.isNullOrBlank() -> userA == userB
    !configA.isNullOrBlank() && !configB.isNullOrBlank() -> configA == configB
    else -> false
  }

  fun ownerOf(item: DownloadItem, part: DownloadItemPart): Owner {
    val resource = when {
      part.serverPath.endsWith("/cover") -> "cover"
      part.episode != null -> "episode:${part.episode.id}"
      else -> "file:${part.serverPath}"
    }
    return Owner(item.serverUserId, item.serverConnectionConfigId, item.libraryItemId, resource)
  }

  fun sameOwner(a: Owner, b: Owner): Boolean =
          sameServer(a.userId, a.configId, b.userId, b.configId) && a.libraryItemId == b.libraryItemId &&
                  (a.resource == b.resource ||
                          (a.resource == ANY_ITEM_FILE && b.resource.startsWith("file:")) ||
                          (b.resource == ANY_ITEM_FILE && a.resource.startsWith("file:")))

  fun canonical(path: String): String = try { File(path).canonicalPath } catch (e: Exception) { File(path).absolutePath }

  /** Owners the local item records give the file at [path]; a podcast file no episode points to is an owner nobody can match. */
  fun recordedOwners(path: String, locals: List<LocalLibraryItem>): Set<Owner> {
    val target = canonical(path)
    val owners = mutableSetOf<Owner>()
    locals.forEach { local ->
      fun owner(resource: String) = Owner(local.serverUserId.orEmpty(), local.serverConnectionConfigId.orEmpty(), local.libraryItemId ?: local.id, resource)
      val isCover = local.coverAbsolutePath?.let { canonical(it) == target } == true
      if (isCover) owners += owner("cover")
      local.localFiles.filter { canonical(it.absolutePath) == target }.forEach { file ->
        val media = local.media
        if (media is Podcast) {
          val episodes = media.episodes.orEmpty().filter { it.audioTrack?.localFileId == file.id }
          episodes.forEach { owners += owner("episode:${it.serverEpisodeId}") }
          if (episodes.isEmpty() && !isCover) owners += owner("unreferenced:${file.id}")
        } else if (!isCover) owners += owner(ANY_ITEM_FILE)
      }
    }
    return owners
  }

  fun verdict(owner: Owner, others: Collection<Owner>, fileExists: Boolean): Verdict = when {
    others.any { !sameOwner(owner, it) } -> Verdict.OTHER_OWNER
    others.isNotEmpty() -> Verdict.SAME_OWNER
    fileExists -> Verdict.UNKNOWN_FILE
    else -> Verdict.FREE
  }

  /** A stable, owner-specific name: the same download always gets the same alternative, another one never does. */
  fun alternateFilename(filename: String, owner: Owner): String {
    // Keyed by the server user id when known, so the name stays the same across saved-config changes
    val server = owner.userId.ifBlank { "config:" + owner.configId }
    val digest = MessageDigest.getInstance("SHA-256").digest("$server|${owner.libraryItemId}|${owner.resource}".toByteArray())
    val tag = digest.joinToString("") { "%02x".format(it) }.take(10)
    val dot = filename.lastIndexOf('.')
    return if (dot > 0) "${filename.substring(0, dot)}.$tag${filename.substring(dot)}" else "$filename.$tag"
  }

  /** The [n]th variant of an alternative name ("name.tag-2.mp3"), for when that name is taken in the folder too. */
  fun numberedFilename(filename: String, n: Int): String {
    val dot = filename.lastIndexOf('.')
    return if (dot > 0) "${filename.substring(0, dot)}-$n${filename.substring(dot)}" else "$filename-$n"
  }

  /** The file a recorded podcast episode of this owner already uses in [folder], so a re-download keeps its name. */
  fun recordedEpisodeFile(owner: Owner, folder: String, locals: List<LocalLibraryItem>): String? {
    if (!owner.resource.startsWith("episode:")) return null
    val dir = canonical(folder)
    locals.forEach { local ->
      if (!sameServer(local.serverUserId, local.serverConnectionConfigId, owner.userId, owner.configId)) return@forEach
      if ((local.libraryItemId ?: local.id) != owner.libraryItemId) return@forEach
      val podcast = local.media as? Podcast ?: return@forEach
      podcast.episodes.orEmpty().filter { "episode:${it.serverEpisodeId}" == owner.resource }.forEach { episode ->
        val file = local.localFiles.find { it.id == episode.audioTrack?.localFileId } ?: return@forEach
        val onDisk = File(file.absolutePath)
        if (onDisk.parentFile?.let { canonical(it.path) } == dir && onDisk.isFile &&
                recordedOwners(onDisk.path, locals).all { sameOwner(owner, it) }) return onDisk.name
      }
    }
    return null
  }

  /** Renames [file] next to itself under a unique hidden name instead of deleting it; false if that is not possible. */
  fun preserve(file: File): Boolean {
    var n = 0
    var target: File
    do {
      target = File(file.parentFile, "${if (file.name.startsWith(".")) "" else "."}${file.name}.abs-preserved-${System.currentTimeMillis()}${if (n > 0) "-$n" else ""}")
      n++
    } while (target.exists() && n < 100)
    return !target.exists() && file.renameTo(target)
  }
}
