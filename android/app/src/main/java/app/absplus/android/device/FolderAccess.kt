package app.absplus.android.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.absplus.android.data.LocalFolder
import app.absplus.android.plugins.AbsLogger
import com.anggrayudi.storage.SimpleStorage

/**
 * Whether the app can still write to a local (device) folder. The one definition used by the
 * downloader and by the Local Folders permission check.
 *
 * SimpleStorage.hasStorageAccess takes the folder's file path (`/storage/emulated/0/...`), never its
 * `content://` URL: given the URL it always answered false, which failed every device-folder download
 * at once as "Lost access" and made Local Folders report "Folder permissions failed" for working
 * folders. The persisted tree grant covers folders without a resolvable path, and the folder itself
 * must still exist and be writable (a grant outlives a deleted folder).
 *
 * App storage needs no grant: internal storage is always writable, an SD card folder while its volume
 * is mounted and writable.
 */
object FolderAccess {
  /** The Android checks, behind an interface so the rules can be tested without storage. */
  interface Probe {
    /** SimpleStorage access for a file path. */
    fun pathAccess(path: String): Boolean
    /** A persisted write grant for the folder's tree. */
    fun treeGrant(contentUrl: String): Boolean
    /** The folder exists and can be written. */
    fun folderWritable(contentUrl: String): Boolean
  }

  fun canWrite(folder: LocalFolder, probe: Probe, volumes: VolumeProbe): Boolean {
    if (AppStorage.isAppStorage(folder.id)) return AppStorage.isAvailable(folder, volumes)
    if (folder.contentUrl.isEmpty()) return false
    val granted =
            (folder.absolutePath.isNotEmpty() && probe.pathAccess(folder.absolutePath)) ||
                    probe.treeGrant(folder.contentUrl)
    return granted && probe.folderWritable(folder.contentUrl)
  }

  /**
   * The saved local folder for a folder URL (it carries the file path), or a path-less stand-in for an
   * unsaved one, which is then judged by its tree grant alone.
   */
  fun folderForUrl(folderUrl: String, savedFolders: List<LocalFolder>): LocalFolder =
          savedFolders.firstOrNull { it.contentUrl == folderUrl }
                  ?: LocalFolder("unsaved", "", folderUrl, "", "", "", "")

  fun canWrite(context: Context, folder: LocalFolder, volumes: VolumeProbe = AndroidVolumeProbe(context)): Boolean =
          try {
            canWrite(folder, AndroidProbe(context), volumes)
          } catch (e: Exception) {
            AbsLogger.error("FolderAccess", "Could not check access to \"${folder.name}\": ${e.message}")
            false
          }

  private class AndroidProbe(private val context: Context) : Probe {
    override fun pathAccess(path: String) = SimpleStorage.hasStorageAccess(context, path, true)

    override fun treeGrant(contentUrl: String): Boolean {
      val uri = Uri.parse(contentUrl)
      val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
      return context.contentResolver.persistedUriPermissions.any { it.isWritePermission && it.uri == tree }
    }

    override fun folderWritable(contentUrl: String) =
            DocumentFile.fromTreeUri(context, Uri.parse(contentUrl))?.let { it.exists() && it.canWrite() } == true
  }
}
