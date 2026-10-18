package app.absplus.android.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.absplus.android.plugins.AbsLogger
import java.io.File
import java.io.FileNotFoundException

/**
 * Manual delete of a download in a device folder. DocumentFile.delete() is recursive and the folder holds
 * files of the user, so only the files the app registered go, then the directories if they are left empty.
 */
object FolderItemDeleter {
  private const val TAG = "FolderItemDeleter"

  /** A registered file: its document, or null when the saved link does not open ([deleteUnresolved] then tries the link itself). */
  class Target(val doc: DocumentFile?, val deleteUnresolved: () -> Boolean)

  /** The children of a directory, or null when they cannot be listed: unknown is not the same as empty. */
  fun interface Lister {
    fun children(dir: DocumentFile): List<DocumentFile>?
  }

  /**
   * A registered entry that exists but is not a regular file (the path was replaced by a folder, or the type is unknown):
   * deleting it would take whatever is inside, so nothing is deleted at all while one is found.
   */
  fun hasNonFile(files: List<Target>): Boolean = files.any { t -> t.doc?.let { it.exists() && !it.isFile } == true }

  /**
   * True when every registered file and temporary file of the app is gone; folders that still hold other files stay and are not an error.
   * Every file is tried even after a failure, so a repeated delete finishes the job. The book folder also loses the
   * app's own temporary files of an interrupted download ([registeredNames] tell which are the app's).
   */
  fun deleteRegistered(
          bookDir: DocumentFile?,
          files: List<Target>,
          registeredNames: List<String>,
          parentDir: () -> DocumentFile?,
          lister: Lister
  ): Boolean {
    // Checked before the first delete: a folder in place of a file must not be taken down with its contents
    if (hasNonFile(files)) {
      AbsLogger.error(TAG, "A registered file is not a regular file; nothing was deleted")
      return false
    }
    // The partials go first: they are what tells a repeated delete that the item is still unfinished, so if one stays,
    // the registered files stay too and the record is kept
    if (bookDir != null && bookDir.exists() && bookDir.isDirectory) {
      var partsGone = true
      // The name is checked before the type: the type of a document may cost a query
      lister.children(bookDir)?.filter { isInterruptedDownload(it.name, registeredNames) && it.isFile }?.forEach {
        if (!it.delete()) {
          partsGone = false
          AbsLogger.error(TAG, "Could not delete an interrupted download file ${it.name}")
        }
      }
      if (!partsGone) return false
    }
    val filesGone =
            files.map { t ->
              val d = t.doc
              if (d == null) t.deleteUnresolved() else !d.exists() || d.delete()
            }.all { it }
    val bookGone =
            if (bookDir == null || !bookDir.exists()) {
              true
            } else {
              filesGone && bookDir.isDirectory && isRemovableEmptyDir(true, lister.children(bookDir)?.size) && bookDir.delete()
            }
    if (bookGone) {
      val parent = parentDir()
      if (parent != null && isRemovableEmptyDir(parent.isDirectory, lister.children(parent)?.size)) parent.delete()
    }
    return filesGone
  }

  /** A folder may be deleted only when it is a directory known to have nothing left in it (null count: could not be listed). */
  internal fun isRemovableEmptyDir(isDirectory: Boolean, childCount: Int?): Boolean = isDirectory && childCount == 0

  /** The parent subfolder of a book, or null when it is the folder root, unknown, or another item still lives under it. */
  internal fun parentSubfolderToDelete(bookAbsolutePath: String, folderPath: String, otherItemPaths: List<String>): String? {
    val parts = bookAbsolutePath.split("/")
    val parent = parts.subList(0, parts.size - 1).joinToString("/").trimEnd('/')
    if (parent.isBlank() || parent == folderPath.trimEnd('/')) return null
    if (otherItemPaths.any { it == parent || it.startsWith("$parent/") }) return null
    return parent
  }

  private val interruptedName = Regex("^\\.(.+)\\.-?\\d+\\.part(\\.[A-Za-z0-9]+)?$", RegexOption.DOT_MATCHES_ALL)

  /** The temporary name the downloader gives a file while copying it into the folder, `.<filename>.<id hash>.part`; a provider may append an extension. */
  internal fun isInterruptedDownload(name: String?, registeredNames: Collection<String>): Boolean {
    if (name == null || !name.startsWith(".")) return false
    val filename = interruptedName.matchEntire(name)?.groupValues?.get(1) ?: return false
    return filename in registeredNames
  }

  /** Lists through the file API for file:// documents and through a ContentResolver query for SAF ones. */
  class DeviceLister(private val ctx: Context) : Lister {
    override fun children(dir: DocumentFile): List<DocumentFile>? {
      val uri = dir.uri
      if (uri.scheme == "file") {
        return uri.path?.let { File(it).listFiles() }?.map { DocumentFile.fromFile(it) }
      }
      return try {
        val docId = if (DocumentsContract.isDocumentUri(ctx, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        ctx.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { c ->
          buildList {
            while (c.moveToNext()) {
              DocumentFile.fromSingleUri(ctx, DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0)))?.let { add(it) }
            }
          }
        }
      } catch (e: Exception) {
        AbsLogger.error(TAG, "Could not list ${dir.name}: ${e.message}")
        null
      }
    }
  }

  /** A saved link that does not open as a document may still be deletable by its URI; a missing file counts as deleted. */
  fun deleteByUri(ctx: Context, contentUrl: String): Boolean =
          try {
            val uri = Uri.parse(contentUrl)
            val deleted =
                    if (uri.scheme == "file") {
                      uri.path?.let { File(it) }?.let { !it.exists() || (it.isFile && it.delete()) } ?: false
                    } else {
                      DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                    }
            if (!deleted) AbsLogger.error(TAG, "Could not delete a file whose saved link does not open")
            deleted
          } catch (e: FileNotFoundException) {
            true
          } catch (e: Exception) {
            AbsLogger.error(TAG, "Could not delete a file whose saved link does not open: ${e.message}")
            false
          }
}
