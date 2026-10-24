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
import java.io.File
import java.io.FileNotFoundException

/** Read-only synthetic provider; faults simulate provider responses, not Android grant enforcement. */
class StartupPresenceProvider : ContentProvider() {
  companion object {
    const val AUTHORITY = "com.android.externalstorage.documents"
    lateinit var base: File
    var fault = "none"
    var queries = 0
    fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:root"), id)
  }
  private fun file(id: String) = if (id == "opaque-token") File(base, "root/show/episode.mp3")
          else File(base, id.removePrefix("primary:"))
  override fun onCreate() = true
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
    queries++
    if (fault == "exception") throw SecurityException("synthetic provider failure")
    if (fault == "gone") throw FileNotFoundException("synthetic card removed")
    if (fault == "null") return null
    val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE)
    val cursor = MatrixCursor(columns)
    cursor.extras = Bundle().apply {
      putBoolean(DocumentsContract.EXTRA_LOADING, fault == "loading")
      if (fault == "error") putString(DocumentsContract.EXTRA_ERROR, "synthetic incomplete query")
    }
    val id = DocumentsContract.getDocumentId(uri)
    val f = file(id)
    val files = if (uri.pathSegments.last() == "children") f.listFiles()?.toList() ?: emptyList() else listOf(f)
    files.filter { it.exists() }.forEach {
      val values = mapOf(Document.COLUMN_DOCUMENT_ID to if (it == f) id else "primary:root/show/${it.name}",
              Document.COLUMN_MIME_TYPE to if (it.isDirectory) Document.MIME_TYPE_DIR else "audio/mpeg")
      cursor.addRow(columns.map { values[it] }.toTypedArray())
    }
    return cursor
  }
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    check(mode == "r")
    if (fault == "unreadable") throw SecurityException("synthetic unreadable document")
    return ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
  }
  override fun getType(uri: Uri) = if (file(DocumentsContract.getDocumentId(uri)).isDirectory) Document.MIME_TYPE_DIR else "audio/mpeg"
  override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No writes permitted")
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No deletes permitted")
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No writes permitted")
  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = error("No document mutations permitted")
}
