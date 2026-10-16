package app.absplus.android.managers

import app.absplus.android.data.LocalLibraryItem
import io.paperdb.Paper

interface LocalItemBook {
  fun read(id: String): LocalLibraryItem?
  fun write(item: LocalLibraryItem)
  fun delete(id: String)
}

object PaperLocalItemBook : LocalItemBook {
  override fun read(id: String): LocalLibraryItem? = Paper.book("localLibraryItems").read(id)

  override fun write(item: LocalLibraryItem) {
    Paper.book("localLibraryItems").write(item.id, item)
  }

  override fun delete(id: String) {
    Paper.book("localLibraryItems").delete(id)
  }
}

sealed class ItemUpdate {
  // No write and no version bump, or every app start (database cleanup) would invalidate a running move
  object Keep : ItemUpdate()
  data class Save(val item: LocalLibraryItem) : ItemUpdate()
  object Delete : ItemUpdate()
}

/** Local library item writes, each under the item's [LocalItemLocks] lock and bumping its version. */
class LocalItemStore(private val book: LocalItemBook) {
  fun save(item: LocalLibraryItem) {
    LocalItemLocks.withItem(item.id) {
      book.write(item)
      LocalItemLocks.bump(item.id)
    }
  }

  fun remove(id: String) {
    LocalItemLocks.withItem(id) {
      book.delete(id)
      LocalItemLocks.bump(id)
    }
  }

  /** Reads, transforms and stores the item in one locked step; null when the item does not exist. */
  fun update(id: String, transform: (LocalLibraryItem) -> ItemUpdate): ItemUpdate? =
          LocalItemLocks.withItem(id) {
            val item = book.read(id) ?: return@withItem null
            val result = transform(item)
            when (result) {
              is ItemUpdate.Keep -> {}
              is ItemUpdate.Save -> {
                require(result.item.id == id) { "Cannot save item ${result.item.id} while updating $id" }
                book.write(result.item)
                LocalItemLocks.bump(id)
              }
              is ItemUpdate.Delete -> {
                book.delete(id)
                LocalItemLocks.bump(id)
              }
            }
            result
          }
}
