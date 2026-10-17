package app.absplus.android.player

import android.app.PendingIntent
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import app.absplus.android.R
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.*

class AbMediaDescriptionAdapter (private val controller: MediaControllerCompat, private val playerNotificationService: PlayerNotificationService) : PlayerNotificationManager.MediaDescriptionAdapter {
  private val tag = "MediaDescriptionAdapter"

  private var currentIconUri: Uri? = null
  private var currentBitmap: Bitmap? = null

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

  override fun createCurrentContentIntent(player: Player): PendingIntent? =
    controller.sessionActivity

  // The awaited item is read from the service: the controller's copy of the session metadata arrives asynchronously
  override fun getCurrentContentText(player: Player) =
    if (playerNotificationService.awaitingDisplay != null) playerNotificationService.getString(R.string.waiting_for_server)
    else controller.metadata.description.subtitle.toString()

  override fun getCurrentContentTitle(player: Player) =
    playerNotificationService.awaitingDisplay?.title ?: controller.metadata.description.title.toString()

  override fun getCurrentLargeIcon(
    player: Player,
    callback: PlayerNotificationManager.BitmapCallback
  ): Bitmap? {
    playerNotificationService.awaitingDisplay?.let { display ->
      return display.coverUri?.let { CoverArt.decodeLocal(playerNotificationService, CoverArt.localCoverUri(playerNotificationService, it), "notification") }
    }
    val albumArtUri = controller.metadata.description.iconUri
    val albumBitmap = controller.metadata.description.iconBitmap
      ?: controller.metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

    // Reuse bitmap from queue navigator (local) or PlaybackSession.resolveCoverBitmapAsync (streaming)
    // For local cover images, bitmap is set in PlayerNotificationService TimelineQueueNavigator.getMediaDescription
    if (albumBitmap != null) {
      return albumBitmap
    }

    return if (currentIconUri != albumArtUri || currentBitmap == null) {
      // Cache the bitmap for the current audiobook so that successive calls to
      // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
      currentIconUri = albumArtUri

      if (currentIconUri.toString().startsWith("content://")) {
        // A cover that can't be decoded shows no art here; the metadata then switches to the default artwork
        currentBitmap = CoverArt.decodeLocal(playerNotificationService, currentIconUri!!, "notification")
        currentBitmap
      } else {
        serviceScope.launch {
          currentBitmap = albumArtUri?.let {
            resolveUriAsBitmap(playerNotificationService, it)
          }
          currentBitmap?.let { callback.onBitmap(it) }
        }
        null
      }
    } else {
      currentBitmap
    }
  }
}
