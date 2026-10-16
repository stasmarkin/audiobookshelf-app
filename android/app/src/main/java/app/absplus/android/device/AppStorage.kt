package app.absplus.android.device

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import app.absplus.android.data.LocalFolder
import app.absplus.android.plugins.AbsLogger
import java.io.File

/** An external storage volume with the app's own files directory on it; `uuid == null` is the primary volume. */
data class AppVolume(
        val uuid: String?,
        val filesDir: File,
        val removable: Boolean,
        val mounted: Boolean,
        val writable: Boolean,
        /** Removable volumes only: whether this is the card the app's records were written to ([VolumeRegistry]). */
        val identity: VolumeIdentity = VolumeIdentity.VERIFIED
)

/** The Android storage lookups, behind an interface so the rules can be tested without devices. */
interface VolumeProbe {
  fun volumes(): List<AppVolume>
  fun internalFilesDir(): File
}

class AndroidVolumeProbe(private val context: Context) : VolumeProbe {
  override fun internalFilesDir(): File = context.filesDir

  override fun volumes(): List<AppVolume> {
    val storageManager = context.getSystemService(StorageManager::class.java)
    // Null entries are volumes that are not mounted right now.
    return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
      try {
        val sv = storageManager.getStorageVolume(dir) ?: return@mapNotNull null
        val mounted = sv.state == Environment.MEDIA_MOUNTED
        val writable = mounted && (dir.mkdirs() || dir.isDirectory) && dir.canWrite()
        val identity = if (sv.isRemovable && mounted) VolumeRegistry.identity(sv.uuid, dir) else VolumeIdentity.VERIFIED
        AppVolume(sv.uuid, dir, sv.isRemovable, mounted, writable, identity)
      } catch (e: Exception) {
        AbsLogger.error("AppStorage", "Could not read storage volume for ${dir.absolutePath}: ${e.message}")
        null
      }
    }
  }
}

/**
 * Folders the app owns and writes with the File API: internal app storage and the app's directory on
 * an SD card. Everything else is a user-picked SAF folder.
 */
object AppStorage {
  const val INTERNAL_PREFIX = "internal-"
  // SAF folder ids are URL-safe base64 of UTF-8 text; "appsd-" decodes to invalid UTF-8, so ids never collide.
  const val APPSD_PREFIX = "appsd-"
  const val INTERNAL_STORAGE_TYPE = "internal"
  const val APPSD_STORAGE_TYPE = "appsd"
  const val INTERNAL_NAME = "Internal App Storage"
  const val SD_NAME = "SD card"

  private const val DOWNLOADS_DIR = "downloads"
  private const val STAGING_DIR = "download-staging"

  fun isAppStorage(folderId: String): Boolean =
          folderId.startsWith(INTERNAL_PREFIX) || folderId.startsWith(APPSD_PREFIX)

  fun appsdFolderId(uuid: String, mediaType: String): String = "$APPSD_PREFIX$uuid-$mediaType"

  fun uuidOf(folderId: String): String? {
    if (!folderId.startsWith(APPSD_PREFIX)) return null
    val rest = folderId.removePrefix(APPSD_PREFIX)
    val dash = rest.lastIndexOf('-')
    if (dash <= 0) return null
    return rest.substring(0, dash)
  }

  fun downloadsRoot(folder: LocalFolder, probe: VolumeProbe): File? = downloadsRoot(folder.id, probe)

  fun downloadsRoot(folderId: String, probe: VolumeProbe): File? = rootDir(folderId, probe, DOWNLOADS_DIR)

  fun stagingRoot(folder: LocalFolder, probe: VolumeProbe): File? = rootDir(folder.id, probe, STAGING_DIR)

  private fun rootDir(folderId: String, probe: VolumeProbe, name: String): File? {
    if (folderId.startsWith(INTERNAL_PREFIX)) return File(probe.internalFilesDir(), name)
    val volume = mountedVolumeFor(folderId, probe) ?: return null
    return File(volume.filesDir, name)
  }

  private fun mountedVolumeFor(folderId: String, probe: VolumeProbe): AppVolume? {
    val uuid = uuidOf(folderId) ?: return null
    return probe.volumes().firstOrNull { it.uuid == uuid && it.mounted }
  }

  /** SAF folders are judged by [FolderAccess], not here. */
  fun isAvailable(folder: LocalFolder, probe: VolumeProbe): Boolean = isAvailable(folder.id, probe)

  /**
   * Whether the folder can be read and written now. An SD card counts only while it is mounted, writable and not a
   * different card under the same UUID; anything uncertain is unavailable, which every caller treats as "keep".
   */
  fun isAvailable(folderId: String, probe: VolumeProbe): Boolean {
    if (folderId.startsWith(INTERNAL_PREFIX)) return true
    val volume = mountedVolumeFor(folderId, probe) ?: return false
    return volume.writable && volume.identity != VolumeIdentity.MISMATCH
  }

  /**
   * Stronger than [isAvailable], for anything that deletes or reconciles existing files: the card must be the very one
   * the records were written to (H02).
   */
  fun isVerified(folderId: String, probe: VolumeProbe): Boolean {
    if (folderId.startsWith(INTERNAL_PREFIX)) return true
    val volume = mountedVolumeFor(folderId, probe) ?: return false
    return volume.writable && volume.identity == VolumeIdentity.VERIFIED
  }

  /** Registers the folder's card before the first write to it; internal storage needs nothing. */
  fun ensureRegistered(folderId: String, probe: VolumeProbe): Boolean {
    if (folderId.startsWith(INTERNAL_PREFIX)) return true
    val volume = mountedVolumeFor(folderId, probe) ?: return false
    return volume.writable && VolumeRegistry.ensureRegistered(volume)
  }

  fun removableVolume(probe: VolumeProbe): AppVolume? =
          probe.volumes().firstOrNull { it.removable && it.mounted && it.writable && it.identity != VolumeIdentity.MISMATCH }

  /**
   * The SD card the settings page describes: the configured card, else any inserted one (choosing it makes it the
   * new target). A configured card that is missing is reported as an unmounted volume with no files dir.
   */
  fun settingsSdCard(volumes: List<AppVolume>, target: StorageTarget): AppVolume? {
    val removable = volumes.filter { it.removable }
    val configuredUuid = (target as? StorageTarget.Sd)?.uuid
    return removable.firstOrNull { it.uuid == configuredUuid }
            ?: removable.firstOrNull()
            ?: configuredUuid?.let { AppVolume(it, File(""), removable = true, mounted = false, writable = false) }
  }

  /** Absolute path with `.` and `..` resolved lexically (symlinks are not followed); java.nio Path needs API 26. */
  fun normalizedPath(file: File): String = "/" + components(file).joinToString("/")

  private fun components(file: File): List<String> {
    val out = ArrayList<String>()
    for (part in file.absolutePath.split(File.separatorChar)) {
      when (part) {
        "", "." -> {}
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
        else -> out.add(part)
      }
    }
    return out
  }

  /** Component-wise, so a sibling like `files2` is not under `files`. */
  fun isUnder(file: File, root: File): Boolean = relativePath(file, root) != null

  /** [file]'s path below [root] without a leading separator ("" for [root] itself), or null when it is not under it. */
  fun relativePath(file: File, root: File): String? {
    val f = components(file)
    val r = components(root)
    if (f.size < r.size || f.subList(0, r.size) != r) return null
    return f.subList(r.size, f.size).joinToString("/")
  }

  /** Identifies the storage a file lives on, so space is reserved per volume. */
  fun storageKey(file: File, probe: VolumeProbe): String {
    if (isUnder(file, probe.internalFilesDir())) return "internal"
    val volume = probe.volumes().firstOrNull { isUnder(file, it.filesDir) } ?: return "other"
    return "volume:" + (volume.uuid ?: "primary")
  }

  fun appOwnedRoots(probe: VolumeProbe): List<File> = listOf(probe.internalFilesDir()) + probe.volumes().map { it.filesDir }

  /** A folder object for the target; not saved to the database. */
  fun folderFor(target: StorageTarget, mediaType: String): LocalFolder =
          when (target) {
            is StorageTarget.Internal ->
                    LocalFolder("$INTERNAL_PREFIX$mediaType", INTERNAL_NAME, "", "", "", INTERNAL_STORAGE_TYPE, mediaType)
            is StorageTarget.Sd ->
                    LocalFolder(appsdFolderId(target.uuid, mediaType), SD_NAME, "", "", "", APPSD_STORAGE_TYPE, mediaType)
          }
}
