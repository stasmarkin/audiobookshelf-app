package app.absplus.android.device

import app.absplus.android.plugins.AbsLogger
import io.paperdb.Paper
import java.io.File
import java.util.UUID

/** Whether a mounted volume is the one the app's records were written to. */
enum class VolumeIdentity {
  /** Its marker matches the token recorded when the app first used it. */
  VERIFIED,
  /** The app has never used a volume with this UUID; nothing on the device refers to it yet. */
  UNREGISTERED,
  /** A volume with a known UUID but without the matching marker: another card, or one whose app folder was wiped. */
  MISMATCH
}

/**
 * Binds removable volumes to the app's records. The volume UUID alone (the path /storage/<uuid>) is not proof that a
 * card is the same one: the first time the app writes to a volume it stores a random token in a marker file there and
 * the same token in internal storage. A card that later shows up under that UUID without the marker is a different or
 * wiped card and is never treated as available, so no record is matched against, and nothing is deleted from, its
 * files. An exact clone of the card, marker included, can't be told apart; no physical card authentication is claimed.
 */
object VolumeRegistry {
  const val MARKER_NAME = ".absplus-volume"

  interface Tokens {
    fun get(uuid: String): String?
    fun put(uuid: String, token: String)
  }

  object PaperTokens : Tokens {
    override fun get(uuid: String): String? = Paper.book("storageVolumes").read<String>(uuid)
    override fun put(uuid: String, token: String) {
      Paper.book("storageVolumes").write(uuid, token)
    }
  }

  @Volatile var tokens: Tokens = PaperTokens

  fun identity(uuid: String?, filesDir: File): VolumeIdentity {
    if (uuid == null) return VolumeIdentity.MISMATCH
    val expected = try {
      tokens.get(uuid)
    } catch (e: Exception) {
      AbsLogger.error("VolumeRegistry", "Could not read the token of volume $uuid: ${e.message}")
      return VolumeIdentity.MISMATCH
    } ?: return VolumeIdentity.UNREGISTERED
    val marker = try {
      File(filesDir, MARKER_NAME).takeIf { it.isFile }?.readText()?.trim()
    } catch (e: Exception) {
      null
    }
    return if (marker == expected) VolumeIdentity.VERIFIED else VolumeIdentity.MISMATCH
  }

  /**
   * Makes sure [volume] is registered before anything is written to it. True for a verified volume and for a newly
   * registered one; false for a mismatching volume or when the marker could not be written.
   */
  @Synchronized
  fun ensureRegistered(volume: AppVolume): Boolean {
    val uuid = volume.uuid ?: return false
    return when (identity(uuid, volume.filesDir)) {
      VolumeIdentity.VERIFIED -> true
      VolumeIdentity.MISMATCH -> false
      VolumeIdentity.UNREGISTERED ->
              try {
                val token = UUID.randomUUID().toString()
                if (!volume.filesDir.isDirectory && !volume.filesDir.mkdirs()) return false
                File(volume.filesDir, MARKER_NAME).writeText(token)
                tokens.put(uuid, token)
                AbsLogger.info("VolumeRegistry", "Registered storage volume $uuid")
                identity(uuid, volume.filesDir) == VolumeIdentity.VERIFIED
              } catch (e: Exception) {
                AbsLogger.error("VolumeRegistry", "Could not register volume $uuid: ${e.message}")
                false
              }
    }
  }
}
