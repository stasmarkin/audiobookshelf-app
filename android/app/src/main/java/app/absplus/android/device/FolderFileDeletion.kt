package app.absplus.android.device

import android.content.Context
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.absplus.android.data.LocalFolder
import app.absplus.android.plugins.AbsLogger
import java.io.File

/**
 * Deleting a download's files in a device folder the user picked (SAF `content://` or `file://`).
 *
 * The folder belongs to the user and may hold their own files, so only the files the app registered for the item are
 * deleted, one by one, and only while each is a regular file. Folders are never deleted: a document provider's
 * delete of a directory is recursive (as is DocumentFile.delete() for a `file://` directory), and no listing proves a
 * directory is empty (a provider may return a partial or still-loading listing, and another writer can add a file at
 * any moment). Empty book or author folders are left in place. Nothing is matched by name, so temporary or
 * look-alike files the app cannot prove it owns stay too.
 *
 * Unknown is never success: a folder that cannot be reached reports [Result.UNAVAILABLE] (an SD card that is out
 * looks exactly like "every file is missing"), a registered entry that is a folder or of unknown type stops the delete
 * before any file goes, and a file still present afterwards (or a folder lost meanwhile) means the item is not deleted,
 * so its record and progress are kept and the delete can be repeated.
 */
object FolderFileDeletion {
  enum class Kind { FILE, DIRECTORY, MISSING, UNKNOWN }

  enum class Result { DELETED, FAILED, UNAVAILABLE }

  /** The storage operations, behind an interface so adversarial providers can be simulated in tests. */
  interface Docs {
    /** The folder the item lives in can be reached (and written) right now. */
    fun folderReachable(): Boolean
    fun kind(uri: String): Kind
    /** Deletes one document already seen as a regular file; must not be recursive. */
    fun deleteFile(uri: String): Boolean
  }

  fun deleteFiles(uris: List<String>, docs: Docs): Result {
    if (!docs.folderReachable()) return Result.UNAVAILABLE
    val kinds = uris.map { docs.kind(it) }
    // Checked before the first delete: a folder in place of a registered file must not be taken down with its contents
    if (kinds.any { it == Kind.DIRECTORY || it == Kind.UNKNOWN }) {
      AbsLogger.error(TAG, "A registered file is a folder or could not be identified; nothing was deleted")
      return Result.FAILED
    }
    uris.forEachIndexed { i, uri ->
      // Asked again right before its delete: the entry may have been replaced since the first look
      if (kinds[i] == Kind.FILE && docs.kind(uri) == Kind.FILE) {
        if (!docs.deleteFile(uri)) AbsLogger.error(TAG, "Could not delete a registered file")
      }
    }
    // The outcome is what is there now, not what the deletes answered
    if (!docs.folderReachable()) return Result.UNAVAILABLE
    return if (uris.all { docs.kind(it) == Kind.MISSING }) Result.DELETED else Result.FAILED
  }

  /** The Android storage behind [Docs] for a device folder. */
  class AndroidDocs(private val ctx: Context, private val folder: LocalFolder?) : Docs {
    override fun folderReachable(): Boolean {
      val f = folder ?: return false
      // A file:// folder has no SAF grant to check: it must exist and be writable
      if (f.contentUrl.startsWith("file:")) return f.contentUrl.toUri().path?.let { File(it) }?.let { it.isDirectory && it.canWrite() } == true
      return FolderAccess.canWrite(ctx, f)
    }

    override fun kind(uri: String): Kind =
            try {
              val parsed = uri.toUri()
              if (parsed.scheme == "file") {
                val f = File(parsed.path ?: return Kind.UNKNOWN)
                when {
                  !f.exists() -> Kind.MISSING
                  f.isFile -> Kind.FILE
                  f.isDirectory -> Kind.DIRECTORY
                  else -> Kind.UNKNOWN
                }
              } else {
                val doc = DocumentFile.fromSingleUri(ctx, parsed) ?: return Kind.UNKNOWN
                when {
                  !doc.exists() -> Kind.MISSING
                  doc.isDirectory -> Kind.DIRECTORY
                  doc.isFile -> Kind.FILE
                  else -> Kind.UNKNOWN
                }
              }
            } catch (e: Exception) {
              AbsLogger.error(TAG, "Could not identify a registered file: ${e.message}")
              Kind.UNKNOWN
            }

    override fun deleteFile(uri: String): Boolean =
            try {
              val parsed = uri.toUri()
              if (parsed.scheme == "file") {
                // java.io.File.delete() is not recursive: a folder that took the file's place is not emptied
                parsed.path?.let { File(it) }?.let { it.isFile && it.delete() } == true
              } else {
                DocumentsContract.deleteDocument(ctx.contentResolver, parsed)
              }
            } catch (e: Exception) {
              AbsLogger.error(TAG, "Could not delete a registered file: ${e.message}")
              false
            }
  }

  private const val TAG = "FolderFileDeletion"
}
