package app.absplus.android.player

import app.absplus.android.diagnostics.DLog
import android.util.Log
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.PlayerState
import app.absplus.android.device.DeviceManager
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

//const val PAUSE_LEN_BEFORE_RECHECK = 30000 // 30 seconds

class PlayerListener(var playerNotificationService:PlayerNotificationService) : Player.Listener {
  var tag = "PlayerListener"

  companion object {
    var lastPauseTime: Long = 0   //ms
    var lazyIsPlaying: Boolean = false
    private const val EARLY_END_SECONDS = 30.0
  }

  override fun onPlayerError(error: PlaybackException) {
    val errorMessage = error.message ?: "Unknown Error"
    val cause = error.cause
    DLog.e(tag, "onPlayerError $errorMessage | code=${error.errorCode} ${error.errorCodeName} | http=${httpStatusOf(error)} | cause=${cause?.javaClass?.name}: ${cause?.message}")
    // A lost server waits for it; otherwise direct play falls back to transcode
    playerNotificationService.handlePlayerPlaybackError(error)
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    // Pauses that bypass the service's pause() (focus loss, audio becoming noisy, a controller on the player itself)
    if (!playWhenReady) playerNotificationService.onPauseRequested()
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int
  ) {
    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
      // If playing set seeking flag
      DLog.d(tag, "onPositionDiscontinuity: oldPosition=${oldPosition.positionMs}/${oldPosition.mediaItemIndex}, newPosition=${newPosition.positionMs}/${newPosition.mediaItemIndex}, isPlaying=${playerNotificationService.currentPlayer.isPlaying} reason=SEEK")
      playerNotificationService.mediaProgressSyncer.seek()
      lastPauseTime = 0 // When seeking while paused reset the auto-rewind
    } else {
      DLog.v(tag, "onPositionDiscontinuity: oldPosition=${oldPosition.positionMs}/${oldPosition.mediaItemIndex}, newPosition=${newPosition.positionMs}/${newPosition.mediaItemIndex}, isPlaying=${playerNotificationService.currentPlayer.isPlaying}, reason=$reason")
    }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    DLog.d(tag, "onIsPlayingChanged to $isPlaying | ${playerNotificationService.getMediaPlayer()} | playbackState=${playerNotificationService.currentPlayer.playbackState}")

    val player = playerNotificationService.currentPlayer

    // Goal of these 2 if statements and the lazyIsPlaying is to ignore this event when it is triggered by a seek
    //  When a seek occurs the player is paused and buffering, then plays again right afterwards.
    if (!isPlaying && player.playbackState == Player.STATE_BUFFERING) {
      DLog.d(tag, "onIsPlayingChanged: Pause event when buffering is ignored")
      return
    }
    if (lazyIsPlaying == isPlaying) {
      DLog.d(tag, "onIsPlayingChanged: Lazy is playing $lazyIsPlaying is already set to this so ignoring")
      return
    }

    lazyIsPlaying = isPlaying

    // Update widget
    DeviceManager.widgetUpdater?.onPlayerChanged(playerNotificationService)

    if (isPlaying) {
      DLog.d(tag, "SeekBackTime: Player is playing")
      if (lastPauseTime > 0 && DeviceManager.deviceData.deviceSettings?.disableAutoRewind != true) {
        DLog.d(tag, "SeekBackTime: playing started now set seek back time $lastPauseTime")
        var seekBackTime = calcPauseSeekBackTime()
        if (seekBackTime > 0) {
          // Current chapter is used so that seek back does not go back to the previous chapter
          val currentChapter = playerNotificationService.getCurrentBookChapter()
          val minSeekBackTime = currentChapter?.startMs ?: 0

          val currentTime = playerNotificationService.getCurrentTime()
          val newTime = currentTime - seekBackTime
          if (newTime < minSeekBackTime) {
            seekBackTime = currentTime - minSeekBackTime
          }
          DLog.d(tag, "SeekBackTime $seekBackTime")
        }

        // TODO: this needs to be reworked so that the audio doesn't start playing before it checks for updated progress
        // Check if playback session still exists or sync media progress if updated
//        val pauseLength: Long = System.currentTimeMillis() - lastPauseTime
//        if (pauseLength > PAUSE_LEN_BEFORE_RECHECK) {
//          val shouldCarryOn = playerNotificationService.checkCurrentSessionProgress(seekBackTime)
//          if (!shouldCarryOn) return
//        }

        if (seekBackTime > 0L) {
          playerNotificationService.seekBackward(seekBackTime)
        }
      }
    } else {
      DLog.d(tag, "SeekBackTime: Player not playing set last pause time | playbackState=${player.playbackState}")
      lastPauseTime = System.currentTimeMillis()
    }

    // Start/stop progress sync interval
    if (isPlaying) {
      // Prefer PNS's session: preparePlayer() always sets it fresh, so it reflects a queue
      // advance (e.g. continuous series) immediately. The syncer's copy only updates once play()
      // below runs, so falling back to it first would keep re-confirming the stale prior item.
      val playbackSession: PlaybackSession? = playerNotificationService.currentPlaybackSession ?: playerNotificationService.mediaProgressSyncer.currentPlaybackSession
      playbackSession?.let {
        // Handles auto-starting sleep timer and resetting sleep timer
        playerNotificationService.sleepTimerManager.handleMediaPlayEvent(it.id)

        player.volume = 1F // Volume on sleep timer might have decreased this

        playerNotificationService.mediaProgressSyncer.play(it)
      }
    } else {
      playerNotificationService.mediaProgressSyncer.pause {
        DLog.d(tag, "Media Progress Syncer paused and synced")
      }
    }

    playerNotificationService.clientEventEmitter?.onPlayingUpdate(isPlaying)
  }

  override fun onEvents(player: Player, events: Player.Events) {
    DLog.v(tag, "onEvents ${playerNotificationService.getMediaPlayer()} | ${events.size()}")

    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
      DLog.v(tag, "EVENT_POSITION_DISCONTINUITY")
    }

    if (events.contains(Player.EVENT_IS_LOADING_CHANGED)) {
      DLog.v(tag, "EVENT_IS_LOADING_CHANGED : " + playerNotificationService.currentPlayer.isLoading)
    }

    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
      DLog.v(tag, "EVENT_PLAYBACK_STATE_CHANGED MediaPlayer = ${playerNotificationService.getMediaPlayer()}")

      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_READY) {
        DLog.d(tag, "STATE_READY : " + playerNotificationService.currentPlayer.duration)

        if (lastPauseTime == 0L) {
          lastPauseTime = -1
        }
        playerNotificationService.onPlaybackReady()
        playerNotificationService.sendClientMetadata(PlayerState.READY)
      }
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_BUFFERING) {
        DLog.d(tag, "STATE_BUFFERING : " + playerNotificationService.currentPlayer.currentPosition)
        playerNotificationService.sendClientMetadata(PlayerState.BUFFERING)
      }
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_ENDED) {
        DLog.d(tag, "STATE_ENDED | playlistQueueSize=${playerNotificationService.playlistQueue.size} | playlistQueueIndex=${playerNotificationService.playlistQueueIndex}")
        logEarlyStreamEnd()
        playerNotificationService.sendClientMetadata(PlayerState.ENDED)

        playerNotificationService.handlePlaybackEnded()
        playerNotificationService.advancePlaylistQueue()
      }
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_IDLE) {
        DLog.d(tag, "STATE_IDLE")
        playerNotificationService.sendClientMetadata(PlayerState.IDLE)
      }
    }

    if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
      DLog.v(tag, "EVENT_MEDIA_METADATA_CHANGED ${playerNotificationService.getMediaPlayer()}")
    }
    if (events.contains(Player.EVENT_PLAYLIST_METADATA_CHANGED)) {
      DLog.v(tag, "EVENT_PLAYLIST_METADATA_CHANGED ${playerNotificationService.getMediaPlayer()}")
    }
  }

  // A stream that "ends" well before its duration would mean a cut connection read as end of file (the queue would skip
  // ahead). Not expected with the platform HTTP stack, which throws on a short body; logged to catch it on devices.
  private fun logEarlyStreamEnd() {
    val session = playerNotificationService.currentPlaybackSession ?: return
    if (session.isLocal) return
    val position = playerNotificationService.getCurrentTimeSeconds()
    val duration = session.getTotalDuration()
    if (duration - position > EARLY_END_SECONDS) {
      DLog.w(tag, "STATE_ENDED of a stream ${duration - position}s before its end | position=$position duration=$duration item=${session.libraryItemId}")
    }
  }

  private fun calcPauseSeekBackTime() : Long {
    if (lastPauseTime <= 0) return 0
    val time: Long = System.currentTimeMillis() - lastPauseTime
    val seekback: Long
    if (time < 10000) seekback = 0 // 10s or less = no seekback
    else if (time < 60000) seekback = 3000 // 10s to 1m = jump back 3s
    else if (time < 300000) seekback = 10000 // 1m to 5m = jump back 10s
    else if (time < 1800000) seekback = 20000 // 5m to 30m = jump back 20s
    else seekback = 29500 // 30m and up = jump back 30s
    return seekback
  }
}
