package app.absplus.android.managers

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Staged download bytes kept across a restart of the app (see DownloadItemManager.resetPartForRestart). */
object StagedResume {
  /**
   * The end of a staged file is dropped before resuming: after a power loss the last written blocks of a file may be
   * missing or zero-filled although its length says otherwise, and re-fetching them costs at most this much.
   */
  const val ROLLBACK_BYTES = 1024L * 1024L

  /**
   * Shortens [staging] by [ROLLBACK_BYTES] and returns the length kept for the resume; 0 when nothing is worth keeping
   * (no regular file, too short, or it could not be shortened), and the caller then restarts from byte zero.
   */
  fun keepForResume(staging: File): Long {
    if (!staging.isFile) return 0L
    val keep = staging.length() - ROLLBACK_BYTES
    if (keep <= 0L) return 0L
    return try {
      RandomAccessFile(staging, "rw").use { it.setLength(keep) }
      if (staging.length() == keep) keep else 0L
    } catch (e: IOException) {
      0L
    }
  }
}
