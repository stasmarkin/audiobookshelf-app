package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Bounded SAF publication. No provider delete, replacement or backup restoration. */
internal class SafDownloadPublisher(private val context: Context) {
  private data class Child(val id: String, val name: String, val mime: String)

  // DocumentFile.listFiles swallows query failures. An incomplete listing cannot authorize a write/rename.
  private fun children(parent: DocumentFile): List<Child> {
    val query = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, DocumentsContract.getDocumentId(parent.uri))
    return context.contentResolver.query(query, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
      check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) &&
              cursor.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "Storage listing unavailable" }
      val result = mutableListOf<Child>()
      while (cursor.moveToNext()) {
        val id = cursor.getString(0)
        val name = cursor.getString(1)
        val mime = cursor.getString(2)
        check(!id.isNullOrBlank() && name != null && !mime.isNullOrBlank()) { "Ambiguous storage listing" }
        result.add(Child(id, name, mime))
      }
      check(result.map { it.id }.toSet().size == result.size) { "Ambiguous document identity" }
      result
    } ?: error("Storage listing unavailable")
  }

  private fun child(parent: DocumentFile, entry: Child): DocumentFile =
          parent.listFiles().singleOrNull { DocumentsContract.getDocumentId(it.uri) == entry.id }
                  ?: error("Could not resolve document")

  private fun checkName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid destination name" }
  }

  private fun newlyCreated(parent: DocumentFile, document: DocumentFile, before: List<Child>, directory: Boolean): DocumentFile {
    check(document.uri.authority == parent.uri.authority && DocumentsContract.isTreeUri(document.uri) &&
            DocumentsContract.getTreeDocumentId(document.uri) == DocumentsContract.getTreeDocumentId(parent.uri)) { "Document outside selected folder" }
    val id = DocumentsContract.getDocumentId(document.uri)
    check(before.none { it.id == id }) { "Provider returned an existing document" }
    val entry = children(parent).singleOrNull { it.id == id } ?: error("Document is not a destination child")
    check((entry.mime == Document.MIME_TYPE_DIR) == directory) { "Unexpected document type" }
    return document
  }

  private fun digest(file: File): ByteArray = file.inputStream().use { input ->
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    hash.digest()
  }

  /**
   * Publishes [staging] as [filename] in [relativePath] below [root] or, when that name is taken (by a file or a
   * folder, the user's or an earlier download's), as the first free name of [alternates]. Nothing that exists is ever
   * replaced, renamed or deleted. The returned URI is the provider's, whatever name the provider finally gave it.
   */
  fun publish(root: DocumentFile, relativePath: String, filename: String, mime: String, staging: File,
              alternates: List<String> = emptyList()): Uri {
    (listOf(filename) + alternates).forEach(::checkName)
    check(root.isDirectory && staging.isFile && staging.length() > 0L) { "Download staging or destination unavailable" }
    var folder = root
    relativePath.split('/').filter { it.isNotEmpty() }.forEach { segment ->
      checkName(segment)
      val before = children(folder)
      val matches = before.filter { it.name == segment }
      check(matches.size <= 1) { "Ambiguous destination folder" }
      folder = if (matches.isNotEmpty()) {
        check(matches.single().mime == Document.MIME_TYPE_DIR) { "Destination folder is occupied by a file" }
        child(folder, matches.single())
      } else {
        newlyCreated(folder, folder.createDirectory(segment) ?: error("Could not create destination folder"), before, true)
      }
    }
    val before = children(folder)
    val name = (listOf(filename) + alternates).firstOrNull { candidate -> before.none { it.name == candidate } }
            ?: error("Download location and its alternative names are used by other files or directories; nothing was replaced")
    val temporary = newlyCreated(folder,
            folder.createFile(mime, ".abs-${UUID.randomUUID()}.part") ?: error("Could not create temporary file"), before, false)
    val expectedHash = digest(staging)
    context.contentResolver.openOutputStream(temporary.uri, "w")?.use { output -> staging.inputStream().use { it.copyTo(output) } }
            ?: error("Could not open download output")
    // Check again immediately before rename. A provider that races external changes still controls rename semantics.
    check(children(folder).none { it.name == name }) { "Download destination changed during copy" }
    check(temporary.isFile && temporary.renameTo(name)) { "Could not finalize download" }
    // DocumentFile retains the URI returned by rename; never look up the expected filename afterwards.
    newlyCreated(folder, temporary, before, false)
    val actual = context.contentResolver.openInputStream(temporary.uri)?.use { input ->
      val hash = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(64 * 1024)
      var size = 0L
      while (true) { val count = input.read(buffer); if (count < 0) break; size += count; hash.update(buffer, 0, count) }
      check(size == staging.length()) { "Download copy size mismatch" }
      hash.digest()
    } ?: error("Could not verify download copy")
    check(expectedHash.contentEquals(actual) && expectedHash.contentEquals(digest(staging))) { "Download copy integrity mismatch" }
    return temporary.uri
  }
}
