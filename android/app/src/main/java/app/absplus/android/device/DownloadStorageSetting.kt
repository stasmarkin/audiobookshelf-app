package app.absplus.android.device

/** Where new downloads go: internal app storage or the app's folder on an SD card volume. */
sealed class StorageTarget {
  object Internal : StorageTarget()
  data class Sd(val uuid: String) : StorageTarget()
}

/** The persisted download storage setting: `"internal"` or `"sd:<volume uuid>"`. */
object DownloadStorageSetting {
  private const val INTERNAL = "internal"
  private const val SD_PREFIX = "sd:"

  /** Anything unrecognised falls back to internal storage. */
  fun parse(raw: String?): StorageTarget {
    if (raw != null && raw.startsWith(SD_PREFIX)) {
      val uuid = raw.removePrefix(SD_PREFIX)
      if (uuid.isNotBlank()) return StorageTarget.Sd(uuid)
    }
    return StorageTarget.Internal
  }

  fun format(t: StorageTarget): String =
          when (t) {
            is StorageTarget.Internal -> INTERNAL
            is StorageTarget.Sd -> SD_PREFIX + t.uuid
          }

  fun normalize(raw: String?): String = format(parse(raw))
}
