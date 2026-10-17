package app.absplus.android.player

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import app.absplus.android.data.PlaybackSession
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import com.fasterxml.jackson.databind.JsonNode
import com.getcapacitor.JSObject
import org.json.JSONObject

/**
 * The book (or episode) a queue waits on while the server is away, as the replica knows it: what the player, the
 * notification, the lock screen and the widget show instead of a session. Display only: it has no audio and is never a
 * PlaybackSession (that one is saved, restored and synced).
 */
data class AwaitingDisplay(
        val libraryItemId: String,
        val episodeId: String?,
        val serverConfigId: String,
        val title: String,
        val author: String?,
        /** The cover of a copy on this device (its coverContentUrl); null: a placeholder. */
        val coverUri: String?,
        val duration: Double,
        val currentTime: Double
) {
  fun toJson(): JSObject = JSObject()
          .put("libraryItemId", libraryItemId)
          .put("episodeId", episodeId ?: JSONObject.NULL)
          .put("serverConfigId", serverConfigId)
          .put("title", title)
          .put("author", author ?: JSONObject.NULL)
          .put("coverUri", coverUri ?: JSONObject.NULL)
          .put("duration", duration)
          .put("currentTime", currentTime)
}

/**
 * The display of a replica row ([itemJson], the minified item) and, for a podcast, its episode row; the position is
 * [progress]'s. Null when the replica has no row (the app then says it waits with a toast).
 */
fun awaitingDisplayOf(itemJson: JsonNode?, episodeJson: JsonNode?, progress: MediaProgressEntity?, coverUri: String?, serverConfigId: String): AwaitingDisplay? {
  if (itemJson == null) return null
  val libraryItemId = itemJson.text("id") ?: return null
  val metadata = itemJson.path("media").path("metadata")
  val title = (if (episodeJson != null) episodeJson.text("title") else metadata.text("title")) ?: return null
  val duration = if (episodeJson != null) {
    episodeJson.number("duration") ?: episodeJson.path("audioFile").number("duration") ?: 0.0
  } else {
    itemJson.path("media").number("duration") ?: 0.0
  }
  // A finished book starts again from the beginning (preparePlayer does the same)
  val currentTime = progress?.takeIf { !it.isFinished }?.currentTime ?: 0.0
  return AwaitingDisplay(libraryItemId, episodeJson?.text("id"), serverConfigId, title,
          metadata.text("authorName") ?: metadata.text("author"), coverUri, duration, currentTime)
}

private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

private fun JsonNode.number(field: String): Double? = get(field)?.takeIf { it.isNumber }?.asDouble()

/**
 * What the app, the notification and the widget show for a wait, and when to tell them: [onChange] runs once per
 * change. A queue's wait shows the awaited item's display; a lost stream shows none (the open session is that item).
 * Main thread only, like [AwaitingServer].
 */
class AwaitingPresentation(private val onChange: (awaiting: Boolean, display: AwaitingDisplay?) -> Unit) {
  private var awaiting = false
  var display: AwaitingDisplay? = null
    private set
  private var displayItem: PlaylistQueueItem? = null

  /**
   * A wait began on [item] (null: a lost stream). Without a [read] display, the one already read for the same item
   * stays (a failed resume waits on it again).
   */
  fun begin(item: PlaylistQueueItem?, read: AwaitingDisplay?) {
    val shown = when {
      item == null -> null
      read != null -> read
      item == displayItem -> display
      else -> null
    }
    displayItem = item.takeIf { shown != null }
    set(true, shown)
  }

  /** The server is back and the awaited item starts: it stays on screen until its session replaces it. */
  fun resuming() = set(false, display)

  fun end() {
    displayItem = null
    set(false, null)
  }

  /** A display read after the wait on [item] began; shown only while the wait is still on it ([blocked]). */
  fun onRead(item: PlaylistQueueItem, read: AwaitingDisplay?, blocked: PlaylistQueueItem?): Boolean {
    if (read == null || blocked != item) return false
    displayItem = item
    return set(true, read)
  }

  private fun set(awaiting: Boolean, display: AwaitingDisplay?): Boolean {
    if (this.awaiting == awaiting && this.display == display) return false
    this.awaiting = awaiting
    this.display = display
    onChange(awaiting, display)
    return true
  }
}

/** The answer of a queue start that waits: the display it showed, if the replica had one. */
data class AwaitingStart(val display: AwaitingDisplay?)

/**
 * A queue start on an item that can only wait: the display is read before [block] begins the wait, so the start's
 * answer and the wait's first event carry it. Null when [block] found the start replaced meanwhile.
 */
suspend fun startAwaiting(read: suspend () -> AwaitingDisplay?, block: (AwaitingDisplay?) -> Boolean): AwaitingStart? {
  val display = read()
  return if (block(display)) AwaitingStart(display) else null
}

/** The media session's metadata: the awaited item while a queue waits on it, else the open session. */
fun nowPlayingMetadata(ctx: Context, session: PlaybackSession?, display: AwaitingDisplay?, waitingLabel: String): MediaMetadataCompat {
  if (display == null) return session?.getMediaMetadataCompat(ctx) ?: MediaMetadataCompat.Builder().build()
  val cover = display.coverUri?.let { CoverArt.localCoverUri(ctx, it) }?.takeIf { !CoverArt.isUnavailable(it) } ?: CoverArt.defaultUri()
  val author = display.author.orEmpty()
  return MediaMetadataCompat.Builder()
          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, display.title)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, display.title)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, waitingLabel)
          .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, author)
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
          .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, author)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, author)
          .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, display.episodeId ?: display.libraryItemId)
          .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (display.duration * 1000).toLong())
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, cover.toString())
          .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, cover.toString())
          .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, cover.toString())
          .build()
}
