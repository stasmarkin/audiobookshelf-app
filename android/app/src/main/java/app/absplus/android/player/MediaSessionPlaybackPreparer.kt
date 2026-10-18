package app.absplus.android.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector

class MediaSessionPlaybackPreparer(var playerNotificationService:PlayerNotificationService) : MediaSessionConnector.PlaybackPreparer {
  var tag = "MediaSessionPlaybackPreparer"

  override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
    Log.d(tag, "ON COMMAND $command")
    return false
  }

  override fun getSupportedPrepareActions(): Long {
    return PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
      PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
      PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
      PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
  }

  override fun onPrepare(playWhenReady: Boolean) {
    Log.d(tag, "ON PREPARE $playWhenReady")
    val mediaManager = playerNotificationService.mediaManager
    mediaManager.firstPlayable { li, episode ->
      li?.let { playerNotificationService.startWrapper(it, episode, playWhenReady, mediaManager.getSavedPlaybackRate()) }
    }
  }

  override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM MEDIA ID $mediaId $playWhenReady")
    val mediaManager = playerNotificationService.mediaManager
    mediaManager.resolveForPlay(mediaId) { libraryItemWrapper, podcastEpisode ->
      libraryItemWrapper?.let { li ->
        playerNotificationService.startWrapper(li, podcastEpisode, playWhenReady, mediaManager.getSavedPlaybackRate())
      }
    }
  }

  override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM SEARCH $query")
    val mediaManager = playerNotificationService.mediaManager
    mediaManager.findForVoice(query) { li, episode ->
      li?.let { playerNotificationService.startWrapper(it, episode, playWhenReady, mediaManager.getSavedPlaybackRate()) }
    }
  }

  override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM URI $uri")
  }
}
