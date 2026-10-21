package app.absplus.android.device

import android.content.Context
import android.database.Cursor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import app.absplus.android.data.LocalFolder
import java.io.File

/** Registered regular files only. Never prunes directories or discovers temporary files by name. */
object FolderFileDeletion {
  enum class Kind { FILE, DIRECTORY, MISSING, UNKNOWN }
  enum class Result { DELETED, FAILED, UNAVAILABLE }

  /**
   * Why a delete did not happen, for the message the user gets. [Result] stays the outcome callers act on; this only
   * tells the unavailable and failed cases apart. Nothing was deleted in any of them, except [PARTIAL]: some registered
   * files went before one could not, and the records are kept for another try.
   */
  enum class Detail(val reason: String) {
    /** The app's access to the selected folder is gone (grant revoked). */
    FOLDER_ACCESS("folderAccess"),
    /** The folder, or the storage it is on, cannot be found although access is still granted. */
    FOLDER_MISSING("folderMissing"),
    /** The app's own SD card storage is not available. */
    STORAGE_UNAVAILABLE("storageUnavailable"),
    /** This Android version offers no way to confirm that a document is inside the selected folder. */
    UNSUPPORTED("unsupported"),
    /** A folder, or something other than a file, is where a registered file should be. */
    NOT_A_FILE("notAFile"),
    /** A registered file's state could not be established. */
    UNVERIFIED("unverified"),
    /** The storage did not delete a file. */
    DELETE_FAILED("deleteFailed"),
    /** Some files were deleted before one could not be. */
    PARTIAL("partial"),
    /** Some files were deleted, then the app's access to the folder was lost. */
    PARTIAL_ACCESS_LOST("partialFolderAccess"),
    /** Some files were deleted, then the folder (or its storage) could no longer be found. */
    PARTIAL_FOLDER_MISSING("partialFolderMissing")
  }

  interface Docs {
    fun folderReachable(): Boolean
    fun kind(uri: String): Kind
    fun deleteFile(uri: String): Boolean
    /** Why [folderReachable] is false. */
    fun unavailableDetail(): Detail = Detail.STORAGE_UNAVAILABLE
    /** Set when nothing in this folder can be deleted safely on this device. */
    fun unsupported(): Detail? = null
  }

  /** A partial operation keeps its records; the next pass can retry remaining registered files. */
  fun deleteFiles(uris: List<String>, docs: Docs, guarded: (() -> Boolean) -> Boolean = { it() }, alreadyMissingIsDeleted: Boolean = false,
                  detail: (Detail) -> Unit = {}): Result {
    if (!docs.folderReachable()) return Result.UNAVAILABLE.also { detail(docs.unavailableDetail()) }
    docs.unsupported()?.let { detail(it); return Result.FAILED }
    val targets = uris.distinct()
    val kinds = targets.map { docs.kind(it) }
    if (kinds.any { it == Kind.DIRECTORY }) return Result.FAILED.also { detail(Detail.NOT_A_FILE) }
    if (kinds.any { it == Kind.UNKNOWN }) return Result.FAILED.also { detail(Detail.UNVERIFIED) }
    if (kinds.none { it == Kind.FILE }) {
      return if (alreadyMissingIsDeleted && kinds.isNotEmpty()) Result.DELETED else Result.FAILED.also { detail(Detail.UNVERIFIED) }
    }
    var removedAny = false
    for ((i, uri) in targets.withIndex()) {
      if (kinds[i] == Kind.MISSING) continue
      val removed = guarded { docs.folderReachable() && docs.kind(uri) == Kind.FILE && docs.deleteFile(uri) }
      if (!removed) {
        if (!docs.folderReachable()) return Result.UNAVAILABLE.also { detail(unavailableAfter(docs, removedAny)) }
        return Result.FAILED.also { detail(if (removedAny) Detail.PARTIAL else Detail.DELETE_FAILED) }
      }
      removedAny = true
    }
    if (!docs.folderReachable()) return Result.UNAVAILABLE.also { detail(unavailableAfter(docs, removedAny)) }
    return if (targets.all { docs.kind(it) == Kind.MISSING }) Result.DELETED else Result.FAILED.also { detail(Detail.PARTIAL) }
  }

  /** Why the folder is unreachable; once a file has already been deleted, the reason says so too (the records are still kept). */
  private fun unavailableAfter(docs: Docs, removedAny: Boolean): Detail {
    val why = docs.unavailableDetail()
    if (!removedAny) return why
    return when (why) {
      Detail.FOLDER_ACCESS -> Detail.PARTIAL_ACCESS_LOST
      Detail.FOLDER_MISSING -> Detail.PARTIAL_FOLDER_MISSING
      else -> Detail.PARTIAL
    }
  }

  /** Queries preserve errors. Unsupported child/type/delete capabilities fail closed. */
  class AndroidDocs(private val ctx: Context, private val folder: LocalFolder?,
                    private val itemFolderUrl: String? = folder?.contentUrl) : Docs {
    override fun folderReachable(): Boolean = try {
      val f = folder
      if (f == null) false
      else if (f.contentUrl.startsWith("file:")) {
        val root = File(f.contentUrl.toUri().path ?: "")
        AndroidStorageIo.kind(root) == NodeKind.DIRECTORY && root.canWrite()
      } else FolderAccess.canWrite(ctx, f)
    } catch (_: Exception) { false }

    override fun unavailableDetail(): Detail = try {
      val f = folder
      if (f == null || f.contentUrl.startsWith("file:")) Detail.FOLDER_MISSING
      else {
        val tree = f.contentUrl.toUri()
        val treeRoot = DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getTreeDocumentId(tree))
        // With its grant still persisted, the folder itself (or the storage it is on) is what is gone
        if (ctx.contentResolver.persistedUriPermissions.any { it.isWritePermission && it.uri == treeRoot }) Detail.FOLDER_MISSING
        else Detail.FOLDER_ACCESS
      }
    } catch (_: Exception) { Detail.FOLDER_ACCESS }

    // Android 7.x has no public way to ask whether a document is inside the selected tree
    override fun unsupported(): Detail? =
            if (folder?.contentUrl?.startsWith("content:") == true && android.os.Build.VERSION.SDK_INT < 26) Detail.UNSUPPORTED else null

    /**
     * The provider's own answer to whether [doc] is inside the tree below [rootDoc]: isChildDocument from Android 10,
     * and on Android 8 and 9 the document's path from the tree's root (findDocumentPath, which Android's storage
     * provider implements). Older versions, or a provider that cannot answer, never count as inside.
     */
    private fun inTree(rootDoc: android.net.Uri, doc: android.net.Uri): Boolean = when {
      android.os.Build.VERSION.SDK_INT >= 29 -> DocumentsContract.isChildDocument(ctx.contentResolver, rootDoc, doc)
      android.os.Build.VERSION.SDK_INT >= 26 -> DocumentsContract.findDocumentPath(ctx.contentResolver, doc)?.path?.let { path ->
        path.size >= 2 && path.first() == DocumentsContract.getDocumentId(rootDoc) && path.last() == DocumentsContract.getDocumentId(doc)
      } == true
      else -> false
    }

    private fun within(uri: String): Boolean {
      val root = folder?.contentUrl?.toUri() ?: return false
      val doc = uri.toUri()
      if (root.scheme == "file" && doc.scheme == "file") {
        val base = File(root.path ?: return false).canonicalFile
        val file = File(doc.path ?: return false).canonicalFile
        return file != base && AppStorage.isUnder(file, base)
      }
      if (root.scheme != "content" || doc.scheme != "content" || root.authority != doc.authority ||
          !DocumentsContract.isTreeUri(root) || !DocumentsContract.isTreeUri(doc) ||
          DocumentsContract.getTreeDocumentId(root) != DocumentsContract.getTreeDocumentId(doc)) return false
      val rootDoc = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
      return DocumentsContract.getDocumentId(doc) != DocumentsContract.getDocumentId(rootDoc) && inTree(rootDoc, doc)
    }

    override fun kind(uri: String): Kind = try {
      val contained = try { within(uri) } catch (_: Exception) { false }
      if (!contained) {
        // Android's local provider cannot isChild/query a document after deleting it.
        // Verify the surviving parent inside the tree and its complete child listing instead.
        if (uri.toUri().authority == "com.android.externalstorage.documents" && absentFromItemFolder(uri)) Kind.MISSING else Kind.UNKNOWN
      }
      else if (uri.toUri().scheme == "file") when (AndroidStorageIo.kind(File(uri.toUri().path!!))) {
        NodeKind.FILE -> Kind.FILE
        NodeKind.DIRECTORY -> Kind.DIRECTORY
        NodeKind.MISSING -> Kind.MISSING
        else -> Kind.UNKNOWN
      } else {
        var mime: String? = null
        var answered = false
        try {
          ctx.contentResolver.query(uri.toUri(), arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            if (!complete(c)) return Kind.UNKNOWN
            answered = true
            if (c.moveToFirst()) mime = c.getString(0)
          }
        } catch (_: java.io.FileNotFoundException) { answered = true }
        when {
          mime == DocumentsContract.Document.MIME_TYPE_DIR -> Kind.DIRECTORY
          !mime.isNullOrBlank() -> Kind.FILE
          answered && absentFromItemFolder(uri) -> Kind.MISSING
          else -> Kind.UNKNOWN
        }
      }
    } catch (_: Exception) { Kind.UNKNOWN }

    private fun absentFromItemFolder(uri: String): Boolean {
      val parent = itemFolderUrl?.toUri() ?: return false
      val doc = uri.toUri()
      val selected = folder?.contentUrl?.toUri() ?: return false
      if (android.os.Build.VERSION.SDK_INT < 26 || selected.scheme != "content" ||
          parent.authority != doc.authority || selected.authority != doc.authority ||
          !DocumentsContract.isTreeUri(parent) || !DocumentsContract.isTreeUri(doc) ||
          DocumentsContract.getTreeDocumentId(parent) != DocumentsContract.getTreeDocumentId(selected) ||
          DocumentsContract.getTreeDocumentId(doc) != DocumentsContract.getTreeDocumentId(selected)) return false
      val rootId = DocumentsContract.getTreeDocumentId(selected)
      val rootDoc = DocumentsContract.buildDocumentUriUsingTree(selected, rootId)
      val parentId = DocumentsContract.getDocumentId(parent)
      val docId = DocumentsContract.getDocumentId(doc)
      if (parentId != rootId && !inTree(rootDoc, parent)) return false
      // Local-provider direct children can be established; opaque/nested ambiguity is deferred.
      if (docId.substringBeforeLast('/', "") != parentId) return false
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
      val c = ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null) ?: return false
      return c.use {
        if (!complete(it)) return false
        while (it.moveToNext()) if (it.getString(0) == docId) return false
        true
      }
    }

    private fun complete(c: Cursor) = !c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) &&
            c.extras.getString(DocumentsContract.EXTRA_ERROR) == null

    override fun deleteFile(uri: String): Boolean = try {
      if (!folderReachable() || kind(uri) != Kind.FILE) false
      else if (uri.toUri().scheme == "file") File(uri.toUri().path!!).delete()
      else {
        val c = ctx.contentResolver.query(uri.toUri(), arrayOf(DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
        val supported = c?.use { complete(it) && it.moveToFirst() &&
                it.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR && !it.getString(1).isNullOrBlank() &&
                it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0 } == true
        supported && within(uri) && DocumentsContract.deleteDocument(ctx.contentResolver, uri.toUri())
      }
    } catch (_: Exception) { false }
  }
}
