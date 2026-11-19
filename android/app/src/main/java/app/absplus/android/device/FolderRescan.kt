package app.absplus.android.device

import app.absplus.android.data.LocalMediaProgress
import java.util.concurrent.ConcurrentHashMap

/**
 * Rescan Folder progress, sent to the folder page as the AbsFileSystem "onRescanProgress" event.
 *
 * [phase] is one of [RescanPhase]. [total] is the number of local author/title folders still to
 * check; it is -1 while the server catalog loads, before the total is known. [latestTitle] is the
 * title of the item just matched; that item is already saved, so it is final. [error] is one of
 * [RescanError], set only with [RescanPhase.FAILED].
 */
data class RescanProgress(
        val folderId: String,
        val phase: String,
        val checked: Int = 0,
        val total: Int = -1,
        val found: Int = 0,
        val unmatched: Int = 0,
        val latestTitle: String? = null,
        val error: String? = null
)

object RescanPhase {
  const val LOADING = "loading"
  const val CHECKING = "checking"
  const val COMPLETE = "complete"
  const val FAILED = "failed"
}

object RescanError {
  /** A library list or a page of a library's items could not be loaded. */
  const val CATALOG = "catalog"
  /** The local folder could not be opened or listed (e.g. permission lost). */
  const val FOLDER = "folder"
  /** A matched item could not be fetched from the server. */
  const val SERVER = "server"
  /** Anything else that went wrong mid-scan. */
  const val UNEXPECTED = "unexpected"
  /** Rescan supports book folders only. */
  const val UNSUPPORTED = "unsupported"
  /** A rescan of the same folder is already running. */
  const val RUNNING = "running"
}

/** At most one rescan per folder; rescans of different folders are independent. */
class RescanGuard {
  private val active = ConcurrentHashMap.newKeySet<String>()

  fun tryStart(folderId: String): Boolean = active.add(folderId)

  fun finish(folderId: String) {
    active.remove(folderId)
  }

  fun isRunning(folderId: String): Boolean = folderId in active
}

/**
 * Loads every library's items for matching. Fails (null) if the library list or any page of any
 * library fails: matching against an incomplete catalog would report real books as unmatched.
 */
fun <L, I> loadRescanCatalog(
        getLibraries: ((List<L>?) -> Unit) -> Unit,
        libraryId: (L) -> String,
        getAllItems: (String, (List<I>?) -> Unit) -> Unit,
        callback: (Map<String, List<I>>?) -> Unit
) {
  getLibraries { libraries ->
    if (libraries == null) {
      callback(null)
      return@getLibraries
    }
    val itemsByLibrary = mutableMapOf<String, List<I>>()
    fun fetchNext(index: Int) {
      if (index >= libraries.size) {
        callback(itemsByLibrary)
        return
      }
      val id = libraryId(libraries[index])
      getAllItems(id) { items ->
        if (items == null) {
          callback(null)
          return@getAllItems
        }
        itemsByLibrary[id] = items
        fetchNext(index + 1)
      }
    }
    fetchNext(0)
  }
}


/**
 * How one local author/title folder is matched to a server book (see [resolveRescanMatch]).
 * [Link.relink] is set when the folder's saved local item is linked to a different server item and
 * the folder's cover file proves which item it was downloaded from.
 */
sealed class RescanMatch<out M> {
  data class Link<M>(val item: M, val relink: Boolean) : RescanMatch<M>()
  /** The folder already has a saved local item and no cover-file proof that its link is wrong. */
  object Keep : RescanMatch<Nothing>()
  /** The book is in several libraries (or several times in the current one, or only in others). */
  object Ambiguous : RescanMatch<Nothing>()
  object Unmatched : RescanMatch<Nothing>()
}

private val COVER_FILE = Regex("^cover-(.+)\\.jpg$")

/**
 * Server item ids named by cover files in a folder. Downloads save the cover as
 * `cover-<server item id>.jpg` (AbsDownloader), so the name records the exact item, in whichever
 * library, the folder was downloaded from.
 */
fun coverItemIds(fileNames: List<String>): List<String> =
        fileNames.mapNotNull { COVER_FILE.matchEntire(it)?.groupValues?.get(1) }.distinct()

/**
 * The Rescan Folder matching policy. Books often exist in several libraries, so author/title alone
 * does not identify the copy a folder belongs to:
 * 1. A cover file naming exactly one item on the server ([byId]) is authoritative, whatever its
 *    library. A saved local item linked elsewhere is relinked to it.
 * 2. A saved local item without that proof is kept as it is.
 * 3. Otherwise author/title ([matchesByAuthorTitle], across all libraries) links only a single
 *    match inside [currentLibraryId] (the library selected in the app).
 * 4. Anything else is [RescanMatch.Ambiguous] when the book exists on the server, else
 *    [RescanMatch.Unmatched]; a copy in another library is never picked by guess.
 */
fun <M> resolveRescanMatch(
        coverIds: List<String>,
        hasSavedItem: Boolean,
        savedLinkId: String?,
        authorTitle: String,
        currentLibraryId: String?,
        byId: (String) -> M?,
        matchesByAuthorTitle: (String) -> List<M>,
        idOf: (M) -> String,
        libraryOf: (M) -> String
): RescanMatch<M> {
  val proven = coverIds.mapNotNull(byId).distinctBy(idOf)
  if (proven.size == 1) {
    val item = proven.single()
    return when {
      !hasSavedItem -> RescanMatch.Link(item, relink = false)
      savedLinkId == idOf(item) -> RescanMatch.Keep
      // Only relink saved items that are linked to a server item; an unlinked local item stays as it is
      savedLinkId != null -> RescanMatch.Link(item, relink = true)
      else -> RescanMatch.Keep
    }
  }
  if (hasSavedItem) return RescanMatch.Keep
  if (proven.size > 1) return RescanMatch.Ambiguous
  val all = matchesByAuthorTitle(authorTitle)
  val inCurrent = if (currentLibraryId == null) emptyList() else all.filter { libraryOf(it) == currentLibraryId }
  return when {
    inCurrent.size == 1 -> RescanMatch.Link(inCurrent.single(), relink = false)
    all.isNotEmpty() -> RescanMatch.Ambiguous
    else -> RescanMatch.Unmatched
  }
}

/**
 * The outcome of matching. [relinked] are the [matched] items that were relinked. [error] (a
 * [RescanError]) is set when the run stopped early.
 */
data class RescanOutcome<R>(
        val matched: List<R>,
        val unmatched: List<String>,
        val error: String?,
        val ambiguous: List<String> = emptyList(),
        val relinked: List<R> = emptyList()
)

/**
 * Checks [folders] one at a time: [match] decides each folder ([resolveRescanMatch]), [fetchFull]
 * loads a linked item, and [scan] builds (or relinks) and saves the local item (null when the
 * folder's files don't match). [onChecked] runs after every folder with the count checked, the
 * items matched so far, the count of folders not matched (unmatched or ambiguous) and the item just
 * matched, if any. [done] runs exactly once, also when a step throws.
 */
fun <F, M, R> runRescan(
        folders: List<Pair<F, String>>,
        match: (F, String) -> RescanMatch<M>,
        fetchFull: (M, (M?) -> Unit) -> Unit,
        scan: (F, M, Boolean, (R?) -> Unit) -> Unit,
        onChecked: (checked: Int, matched: List<R>, notMatched: Int, latest: R?) -> Unit,
        done: (RescanOutcome<R>) -> Unit
) {
  val matched = mutableListOf<R>()
  val relinked = mutableListOf<R>()
  val unmatched = mutableListOf<String>()
  val ambiguous = mutableListOf<String>()
  var finished = false
  fun finish(error: String?) {
    if (finished) return
    finished = true
    done(RescanOutcome(matched.toList(), unmatched.toList(), error, ambiguous.toList(), relinked.toList()))
  }

  fun processFrom(start: Int) {
    var index = start
    try {
      // Folders that need no server request resolve synchronously: loop instead of recursing per folder
      while (index < folders.size) {
        val (folder, relPath) = folders[index]
        val decision = match(folder, relPath)
        if (decision is RescanMatch.Link) {
          val next = index + 1
          fetchFull(decision.item) { full ->
            if (full == null) {
              finish(RescanError.SERVER)
              return@fetchFull
            }
            try {
              scan(folder, full, decision.relink) { result ->
                if (result != null) {
                  matched.add(result)
                  if (decision.relink) relinked.add(result)
                } else unmatched.add(relPath)
                try {
                  onChecked(next, matched, unmatched.size + ambiguous.size, result)
                } catch (e: Exception) {
                  finish(RescanError.UNEXPECTED)
                  return@scan
                }
                processFrom(next)
              }
            } catch (e: Exception) {
              finish(RescanError.UNEXPECTED)
            }
          }
          return
        }
        when (decision) {
          is RescanMatch.Ambiguous -> ambiguous.add(relPath)
          is RescanMatch.Unmatched -> unmatched.add(relPath)
          else -> {}
        }
        index++
        onChecked(index, matched, unmatched.size + ambiguous.size, null)
      }
      finish(null)
    } catch (e: Exception) {
      finish(RescanError.UNEXPECTED)
    }
  }
  processFrom(0)
}

/**
 * Re-points a relinked item's local progress to the corrected server item. Only the server link
 * changes: position, finished state and timestamps stay, so nothing listened offline is lost; the
 * scanner then imports it into userdata.db, and the next reconcile sends it if it is newer than the
 * corrected item's server progress.
 */
fun repointLocalProgress(
        progress: LocalMediaProgress,
        libraryItemId: String,
        serverConnectionConfigId: String?,
        serverAddress: String?,
        serverUserId: String?
): LocalMediaProgress {
  progress.libraryItemId = libraryItemId
  progress.serverConnectionConfigId = serverConnectionConfigId
  progress.serverAddress = serverAddress
  progress.serverUserId = serverUserId
  return progress
}
