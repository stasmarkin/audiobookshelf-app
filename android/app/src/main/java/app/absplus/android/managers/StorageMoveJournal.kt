package app.absplus.android.managers

import android.content.Context
import android.provider.Settings
import io.paperdb.Paper
import java.io.File

/**
 * Phases of moving one item's download folder to another storage. The journal entry is written (internal storage)
 * before each step it describes, so a step interrupted by a crash, a kill or a removed card is finished or undone
 * by recovery. The source folder is never deleted before [COMMITTED] and the reboot check described there.
 */
enum class MovePhase {
  /** Copying into the temporary folder on the target; the item still uses the source. */
  COPYING,
  /** Every file is in the temporary folder, synced and its SHA-256 re-read and matched. */
  VERIFIED,
  /** The temporary folder was renamed to the destination; the item record may or may not point at it yet. */
  INSTALLED,
  /**
   * The item record points at the destination. The source stays until the destination has been read back and matched
   * after the device restarted ([StorageMoveJournalEntry.commitEpoch] differs), so its bytes came from the storage
   * itself and not from memory; only then is the source retired.
   */
  COMMITTED,
  /** Deleting the source's manifest files; repeated until done. */
  RETIRING,
  /** The destination failed its check after a restart and the item could not be pointed back; nothing is deleted. */
  NEEDS_ATTENTION
}

data class ManifestFile(var path: String = "", var size: Long = 0L, var sha256: String = "")

/** One item's move. Holds no credentials: identities, paths, sizes and hashes only. */
data class StorageMoveJournalEntry(
        var opId: String = "",
        var itemId: String = "",
        var libraryItemId: String? = null,
        var fromFolderId: String = "",
        var toFolderId: String = "",
        var sourceDir: String = "",
        var destDir: String = "",
        var tempDir: String = "",
        var files: MutableList<ManifestFile> = mutableListOf(),
        var phase: MovePhase = MovePhase.COPYING,
        var createdAt: Long = 0L,
        var commitEpoch: String? = null,
        var note: String? = null
)

/** The journal's storage; one entry per local item at most. */
interface MoveJournal {
  fun all(): List<StorageMoveJournalEntry>
  fun get(itemId: String): StorageMoveJournalEntry?
  /** Durable on return (Paper writes the record file before returning). */
  fun put(entry: StorageMoveJournalEntry)
  fun remove(itemId: String)
}

object PaperMoveJournal : MoveJournal {
  private const val BOOK = "storageMoveJournal"

  override fun all(): List<StorageMoveJournalEntry> = Paper.book(BOOK).allKeys.mapNotNull { get(it) }

  override fun get(itemId: String): StorageMoveJournalEntry? = Paper.book(BOOK).read<StorageMoveJournalEntry>(itemId)

  override fun put(entry: StorageMoveJournalEntry) {
    Paper.book(BOOK).write(entry.itemId, entry)
  }

  override fun remove(itemId: String) {
    Paper.book(BOOK).delete(itemId)
  }
}

/** Identifies the current boot of the device; null when it can't be read (then no source is ever retired). */
fun interface BootEpoch {
  fun current(): String?
}

class AndroidBootEpoch(private val context: Context) : BootEpoch {
  override fun current(): String? {
    try {
      val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
      if (count >= 0) return "count:$count"
    } catch (e: Exception) {
      // fall through to the kernel boot id
    }
    return try {
      File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }?.let { "id:$it" }
    } catch (e: Exception) {
      null
    }
  }
}
