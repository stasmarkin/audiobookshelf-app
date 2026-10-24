package app.absplus.android.managers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Test-only document tree over a temporary directory (R29-N02). It speaks the DocumentsContract protocol the app uses
 * (document and child queries, create/rename/delete calls, file descriptors), served under the external storage
 * authority so SimpleStorage path helpers behave as on a device. Document ids are "primary:<relative path>". Rename and
 * delete can be made to fail, to exercise failed device-folder finalizations. Synthetic files only.
 */
class FakeDocumentTreeProvider : ContentProvider() {
  companion object {
    const val AUTHORITY = "com.android.externalstorage.documents"
    lateinit var base: File
    @Volatile var failRename = false
    @Volatile var failDelete = false
    /** Fails the next rename whose new name is this one, once (e.g. only the final rename of a finalization). */
    @Volatile var failNextRenameTo: String? = null
    val renames = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    var childQueryFailure: String? = null
    var createResultId: String? = null
    var alternateFinalName: String? = null
    var corruptOnRead = false
    /** Document ids whose deleteDocument fails (one file of several, for a partial delete). */
    val failDeleteOf = mutableSetOf<String>()

    fun reset(dir: File) {
      base = dir
      failRename = false
      failDelete = false
      failNextRenameTo = null
      renames.clear()
      deletes.clear()
      childQueryFailure = null
      createResultId = null
      alternateFinalName = null
      corruptOnRead = false
      failDeleteOf.clear()
    }
  }

  private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
          Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

  private fun fileOf(documentId: String): File = File(base, documentId.removePrefix("primary:")).also {
    if (!it.canonicalPath.startsWith(base.canonicalPath)) throw FileNotFoundException(documentId)
  }

  private fun idOf(file: File): String =
          "primary:" + file.canonicalFile.relativeTo(base.canonicalFile).path.replace(File.separatorChar, '/')

  private fun mimeOf(file: File): String =
          if (file.isDirectory) Document.MIME_TYPE_DIR
          else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"

  private fun row(cursor: MatrixCursor, file: File, projection: Array<out String>) {
    val flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
            (if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
    val values = mapOf<String, Any?>(Document.COLUMN_DOCUMENT_ID to idOf(file), Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to mimeOf(file), Document.COLUMN_SIZE to (if (file.isFile) file.length() else 0L),
            Document.COLUMN_LAST_MODIFIED to file.lastModified(), Document.COLUMN_FLAGS to flags)
    cursor.addRow(projection.map { values[it] }.toTypedArray())
  }

  /** The tree a request came through: results are returned as tree URIs, as the platform provider does. */
  private fun withTree(request: Uri, documentId: String): Uri =
          if (DocumentsContract.isTreeUri(request)) DocumentsContract.buildDocumentUriUsingTree(request, documentId)
          else DocumentsContract.buildDocumentUri(AUTHORITY, documentId)

  override fun onCreate() = true

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
    val wanted = projection ?: columns
    val cursor = MatrixCursor(wanted)
    val children = uri.pathSegments.lastOrNull() == "children"
    if (children) when (childQueryFailure) {
      "exception" -> throw SecurityException("synthetic query failure")
      "null" -> return null
      "loading" -> cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
      "error" -> cursor.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "synthetic provider failure") }
    }
    val documentId = DocumentsContract.getDocumentId(uri)
    val file = fileOf(documentId)
    if (children) file.listFiles()?.sortedBy { it.name }?.forEach { row(cursor, it, wanted) }
    else if (file.exists()) row(cursor, file, wanted)
    return cursor
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    @Suppress("DEPRECATION") val uri = extras?.getParcelable<Uri>("uri") ?: throw IllegalArgumentException("no uri")
    val documentId = DocumentsContract.getDocumentId(uri)
    return when (method) {
      "android:createDocument" -> {
        createResultId?.let { return Bundle().apply { putParcelable("uri", withTree(uri, it)) } }
        val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
        val file = File(fileOf(documentId), name)
        if (extras.getString(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        Bundle().apply { putParcelable("uri", withTree(uri, idOf(file))) }
      }
      "android:renameDocument" -> {
        val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
        renames += "$documentId -> $name"
        if (failRename) throw IllegalStateException("simulated rename failure")
        if (failNextRenameTo == name) { failNextRenameTo = null; throw IllegalStateException("simulated rename failure") }
        val file = fileOf(documentId)
        val target = File(file.parentFile, alternateFinalName ?: name)
        if (target.exists() || !file.renameTo(target)) throw IllegalStateException("rename failed")
        Bundle().apply { putParcelable("uri", withTree(uri, idOf(target))) }
      }
      "android:deleteDocument" -> {
        deletes += documentId
        if (failDelete || documentId in failDeleteOf) throw IllegalStateException("simulated delete failure")
        if (!fileOf(documentId).deleteRecursively()) throw IllegalStateException("delete failed")
        Bundle()
      }
      // As DocumentsProvider answers them for Android's storage provider (isChildDocument: Android 10+;
      // findDocumentPath: Android 8+, the path from the tree's root document when asked through a tree URI)
      "android:isChildDocument" -> {
        @Suppress("DEPRECATION") val target = extras.getParcelable<Uri>("android.content.extra.TARGET_URI")!!
        val child = DocumentsContract.getDocumentId(target)
        Bundle().apply { putBoolean("result", child != documentId && child.startsWith("$documentId/")) }
      }
      "android:findDocumentPath" -> {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        if (!fileOf(documentId).exists() || (documentId != treeId && !documentId.startsWith("$treeId/"))) throw java.io.FileNotFoundException(documentId)
        val path = mutableListOf(treeId)
        documentId.removePrefix(treeId).split("/").filter { it.isNotEmpty() }.forEach { path += path.last() + "/" + it }
        Bundle().apply { putParcelable("result", DocumentsContract.Path(null, path)) }
      }
      else -> throw UnsupportedOperationException(method)
    }
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val file = fileOf(DocumentsContract.getDocumentId(uri))
    if (mode == "r" && corruptOnRead && file.isFile) file.writeBytes(ByteArray(file.length().toInt()) { 42 })
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
  }

  override fun getType(uri: Uri): String = mimeOf(fileOf(DocumentsContract.getDocumentId(uri)))
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
