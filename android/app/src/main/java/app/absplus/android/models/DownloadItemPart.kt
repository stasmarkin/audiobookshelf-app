package app.absplus.android.models

import android.net.Uri
import android.util.Log
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.EBookFile
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.File

data class DownloadItemPart(
  val id: String,
  val downloadItemId: String,
  val filename: String,
  var fileSize: Long,
  @JsonIgnore val destinationPath: String,
  val finalDestinationPath:String,
  val serverPath: String,
  val localFolderName: String,
  val localFolderUrl: String,
  val localFolderId: String,
  val ebookFile: EBookFile?,
  val audioTrack: AudioTrack?,
  val episode: PodcastEpisode?,
  var completed:Boolean,
  var moved:Boolean,
  var isMoving:Boolean,
  var failed:Boolean,
  var failureReason: String? = null,
  var permissionLost: Boolean = false,
  var storageUnavailable: Boolean = false,
  @JsonIgnore val uri: Uri,
  @JsonIgnore val destinationUri: Uri,
  @JsonIgnore val finalDestinationUri: Uri,
  @JsonIgnore var completedDestinationUri: String?,
  val finalDestinationSubfolder: String,
  var downloadId: Long?,
  @JsonIgnore var lastUpdateTime: Long?,
  var progress: Long,
  var bytesDownloaded: Long,
  @JsonIgnore var retryCount: Int = 0,
  @JsonIgnore var authRetryCount: Int = 0,
  var waitingForSpace: Boolean = false,
  @JsonIgnore var reusedExistingFile: Boolean = false,
  /** ETag or Last-Modified of the response the staged bytes came from; only with it are they resumed after a restart */
  @JsonIgnore var resumeValidator: String? = null,
  /** Held: the saved server no longer matches the one this download was started for; nothing transfers or is deleted */
  var authorizationHold: Boolean = false
) {
  companion object {
    fun make(downloadItemId:String, filename:String, fileSize: Long, destinationFile: File, finalDestinationFile: File, subfolder:String, serverPath:String, localFolder: LocalFolder, ebookFile: EBookFile?, audioTrack: AudioTrack?, episode: PodcastEpisode?) :DownloadItemPart {
      val destinationUri = Uri.fromFile(destinationFile)
      val finalDestinationUri = Uri.fromFile(finalDestinationFile)
      val rawCover = if (serverPath.endsWith("/cover")) "?raw=1" else ""
      val downloadUri = Uri.parse("${DeviceManager.serverAddress}${serverPath}$rawCover")

      Log.d("DownloadItemPart", "Audio File Destination Uri: $destinationUri | Final Destination Uri: $finalDestinationUri | Server Path $serverPath")
      return DownloadItemPart(
        id = DeviceManager.getBase64Id(finalDestinationFile.absolutePath),
        downloadItemId,
        filename = filename,
        fileSize = fileSize,
        destinationPath = destinationFile.absolutePath,
        finalDestinationPath = finalDestinationFile.absolutePath,
        serverPath = serverPath,
        localFolderName = localFolder.name,
        localFolderUrl = localFolder.contentUrl,
        localFolderId = localFolder.id,
        ebookFile = ebookFile,
        audioTrack = audioTrack,
        episode = episode,
        completed = false,
        moved = false,
        isMoving = false,
        failed = false,
        uri = downloadUri,
        destinationUri = destinationUri,
        finalDestinationUri = finalDestinationUri,
        completedDestinationUri = null,
        finalDestinationSubfolder = subfolder,
        downloadId = null,
        lastUpdateTime = null,
        progress = 0,
        bytesDownloaded = 0,
        reusedExistingFile = false
      )
    }
  }

  @get:JsonIgnore
  val isAppStorage get() = AppStorage.isAppStorage(localFolderId)

}
