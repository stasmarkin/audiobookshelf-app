package app.absplus.android.player

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.device.AppStorage
import app.absplus.android.device.VolumeProbe

enum class LocalAvailability { OK, STORAGE_UNAVAILABLE, NO_FILES }

object LocalPlaybackAvailability {
  /** The volume is checked first: files on a removed SD card look missing, but must not be reported as deleted. */
  fun check(item: LocalLibraryItem, probe: VolumeProbe, hasTracks: () -> Boolean): LocalAvailability {
    if (AppStorage.isAppStorage(item.folderId) && !AppStorage.isAvailable(item.folderId, probe)) {
      return LocalAvailability.STORAGE_UNAVAILABLE
    }
    if (!hasTracks()) return LocalAvailability.NO_FILES
    return LocalAvailability.OK
  }
}
