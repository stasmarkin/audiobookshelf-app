package app.absplus.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.view.View
import android.widget.RemoteViews
import androidx.media.session.MediaButtonReceiver
import app.absplus.android.BuildConfig
import app.absplus.android.MainActivity
import app.absplus.android.MediaPlayerWidget
import app.absplus.android.R
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.diagnostics.DLog
import app.absplus.android.managers.DbManager
import app.absplus.android.media.CoverCache
import app.absplus.android.managers.PlaybackRestoreStore
import app.absplus.android.player.AwaitingDisplay
import app.absplus.android.player.CoverArt
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Builds the media widget's RemoteViews for every size bucket and pushes them to the launcher.
 *
 * Rendering only reads state; it never touches the player, playback service or restoration. The widget's
 * actions are unchanged: play/pause, rewind and fast-forward go through the existing media-button path,
 * and tapping the widget opens the app.
 */
object WidgetRenderer {
  private const val TAG = "MediaPlayerWidget"
  private const val ARTWORK_PX = 300
  /** Intrinsic size of a loaded cover: above any FULL bounds, so the bounds (not the bitmap) decide its size. */
  internal const val ARTWORK_INTRINSIC_DP = 1200
  internal const val ARTWORK_DENSITY = ARTWORK_PX * DisplayMetrics.DENSITY_MEDIUM / ARTWORK_INTRINSIC_DP

  /**
   * What the widget shows; positions are milliseconds from the start of the item. [awaiting]: the item a waiting queue
   * is on, shown over [session], with its cover resolved beforehand ([awaitingCoverUri]; null: the app icon).
   */
  data class State(
    val session: PlaybackSession?,
    val isPlaying: Boolean,
    val showControls: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
    val awaiting: AwaitingDisplay? = null,
    val awaitingCoverUri: Uri? = null
  ) {
    val title: String? get() = awaiting?.title ?: session?.displayTitle
    val author: String? get() = if (awaiting != null) awaiting.author else session?.displayAuthor
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val artworkExecutor = Executors.newSingleThreadExecutor()

  // Main-thread state
  private var lastState: State? = null
  private var artworkKey: String? = null
  private var artworkBitmap: Bitmap? = null
  private var loadingArtworkKey: String? = null
  private var failedArtworkKey: String? = null
  private val loggedFullPlans = mutableMapOf<Int, FullArtwork.Plan?>()

  /** A playback event for one placed widget (called on the main thread by the widget updater). */
  fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, state: State) {
    lastState = state
    render(context, appWidgetManager, appWidgetId, state)
  }

  /**
   * Redraws placed widgets from the last known state without touching the player: used for launcher
   * updates, size changes and theme changes. With no state in this process yet (e.g. the process was
   * started by the launcher), falls back to the last saved session shown as not playing, which is accurate
   * because no player can be running in that case.
   */
  fun renderAll(context: Context, appWidgetIds: IntArray? = null) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { renderAll(context, appWidgetIds) }
      return
    }
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val ids = appWidgetIds ?: appWidgetManager.getAppWidgetIds(ComponentName(context, MediaPlayerWidget::class.java))
    if (ids.isEmpty()) return
    val state = lastState ?: fallbackState(context)
    for (id in ids) render(context, appWidgetManager, id, state)
  }

  internal fun fallbackState(context: Context): State {
    DbManager.initialize(context)
    // A launcher-started process has no sync runtime; the cover cache is what shows a streamed book's cover offline
    CoverCache.init(context)
    val session = DeviceManager.deviceData.lastPlaybackSession
    return State(
      session = session,
      isPlaying = false,
      showControls = session != null && PlaybackRestoreStore.isResumable(context),
      positionMs = session?.let { (it.currentTime * 1000).toLong() },
      durationMs = session?.let { (it.duration * 1000).toLong() }
    )
  }

  private fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, state: State) {
    val key = artworkUri(context, state).toString()
    val bitmap = if (key == artworkKey) artworkBitmap else null
    val actions = Actions.create(context)
    val look = WidgetTheme.look(context)
    val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
    val full = fullPlan(context, appWidgetId, options)
    val views =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+: the launcher picks the layout for the current size, including while resizing
        RemoteViews(WidgetSize.RESPONSIVE_IDEAL_SIZES.associate { (idealSize, size) -> idealSize to build(context, size, look, state, bitmap, actions, full) })
      } else {
        // API 24-30: pick the layout from the size the launcher reported (redrawn on onAppWidgetOptionsChanged)
        build(context, sizeFromOptions(context, options), look, state, bitmap, actions, full)
      }
    appWidgetManager.updateAppWidget(appWidgetId, views)
    if (bitmap == null && key != failedArtworkKey) loadArtwork(context, key)
  }

  /**
   * FULL presentation (side-by-side, expanded or large) and cover bounds for this widget from its reported size (redrawn
   * on onAppWidgetOptionsChanged), or null before the launcher has reported one: then FULL is side-by-side with
   * the layouts' widget_full_artwork_max.
   */
  private fun fullPlan(context: Context, appWidgetId: Int, options: Bundle?): FullArtwork.Plan? {
    val size = FullArtwork.sizeDp(options)
    val plan = size?.let { FullArtwork.plan(it.width, it.height, context.resources.configuration.fontScale) }
    if (!loggedFullPlans.containsKey(appWidgetId) || loggedFullPlans[appWidgetId] != plan) {
      loggedFullPlans[appWidgetId] = plan
      DLog.d(TAG, "Widget $appWidgetId size ${size?.width}x${size?.height}dp, FULL ${plan?.presentation} artwork max ${plan?.bounds?.maxWidthDp}x${plan?.bounds?.maxHeightDp}dp")
    }
    return plan
  }

  /** API 24-30 size: portrait uses min width x max height, landscape max width x min height (platform convention). */
  fun sizeFromOptions(context: Context, options: Bundle?): WidgetSize {
    if (options == null) return WidgetSize.COMPACT
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    return WidgetSize.classify(width.toFloat(), height.toFloat())
  }

  /**
   * Layout per size; LLAMA has paint-only variants with the same ids, Dark/Black/Light keep the existing layouts.
   * [full] selects FULL's presentation (same ids in every one); it doesn't affect COMPACT or WIDE.
   */
  fun layoutFor(size: WidgetSize, theme: WidgetTheme, full: FullArtwork.Presentation = FullArtwork.Presentation.NORMAL): Int =
    when (theme) {
      WidgetTheme.STANDARD ->
        when (size) {
          WidgetSize.COMPACT -> R.layout.media_player_widget
          WidgetSize.WIDE -> R.layout.media_player_widget_wide
          WidgetSize.FULL ->
            when (full) {
              FullArtwork.Presentation.NORMAL -> R.layout.media_player_widget_full
              FullArtwork.Presentation.EXPANDED -> R.layout.media_player_widget_full_expanded
              FullArtwork.Presentation.LARGE -> R.layout.media_player_widget_full_expanded_large
            }
        }
      WidgetTheme.LLAMA ->
        when (size) {
          WidgetSize.COMPACT -> R.layout.media_player_widget_llama
          WidgetSize.WIDE -> R.layout.media_player_widget_wide_llama
          WidgetSize.FULL ->
            when (full) {
              FullArtwork.Presentation.NORMAL -> R.layout.media_player_widget_full_llama
              FullArtwork.Presentation.EXPANDED -> R.layout.media_player_widget_full_expanded_llama
              FullArtwork.Presentation.LARGE -> R.layout.media_player_widget_full_expanded_large_llama
            }
        }
    }

  private fun build(context: Context, size: WidgetSize, look: WidgetLook, state: State, bitmap: Bitmap?, actions: Actions, full: FullArtwork.Plan?): RemoteViews {
    val views = RemoteViews(context.packageName, layoutFor(size, look.theme, full?.presentation ?: FullArtwork.Presentation.NORMAL))
    views.setOnClickPendingIntent(R.id.widgetPlayPauseButton, actions.playPause)
    views.setOnClickPendingIntent(R.id.widgetFastForwardButton, actions.fastForward)
    views.setOnClickPendingIntent(R.id.widgetRewindButton, actions.rewind)
    views.setOnClickPendingIntent(R.id.widgetBackground, actions.open)
    views.setViewVisibility(R.id.widgetButtonContainer, if (state.showControls) View.VISIBLE else View.GONE)

    views.setTextViewText(R.id.widgetArtistText, state.author ?: "Unknown")
    views.setTextViewText(R.id.widgetMediaTitle, state.title ?: "Unknown")

    if (bitmap != null) views.setImageViewBitmap(R.id.widgetAlbumArt, bitmap) else views.setImageViewResource(R.id.widgetAlbumArt, R.drawable.icon)
    val playPauseResource = if (state.isPlaying) androidx.mediarouter.R.drawable.ic_media_pause_dark else androidx.mediarouter.R.drawable.ic_media_play_dark
    views.setImageViewResource(R.id.widgetPlayPauseButton, playPauseResource)

    if (size == WidgetSize.FULL) {
      if (full != null) {
        val density = context.resources.displayMetrics.density
        applyArtworkBounds(views, R.id.widgetAlbumArt, (full.bounds.maxWidthDp * density).toInt(), (full.bounds.maxHeightDp * density).toInt())
        // The floating brand icon shows only where it clears the cover and the text (FullArtwork.Plan.icon)
        views.setViewVisibility(R.id.tinyCornerIcon, if (full.icon == FullArtwork.IconSlot.CORNER) View.VISIBLE else View.GONE)
        if (full.presentation == FullArtwork.Presentation.NORMAL) {
          views.setViewVisibility(R.id.tinyCornerIconPanel, if (full.icon == FullArtwork.IconSlot.PANEL) View.VISIBLE else View.GONE)
        }
      }
      // Snapshot only: fixed at this update, never advanced by a timer or scheduled work
      val snapshot = WidgetText.snapshot(state.positionMs, state.durationMs)
      views.setViewVisibility(R.id.widgetTimeRow, if (snapshot != null) View.VISIBLE else View.INVISIBLE)
      views.setProgressBar(R.id.widgetProgress, WidgetText.PROGRESS_MAX, snapshot?.progress ?: 0, false)
      if (snapshot != null) {
        views.setTextViewText(R.id.widgetElapsedText, snapshot.elapsed)
        views.setTextViewText(R.id.widgetRemainingText, snapshot.remaining)
      }
    }
    WidgetPaint.apply(views, size, look)
    return views
  }

  /**
   * Responsive cover bounds (FullArtwork). ImageView.setMaxWidth/setMaxHeight are RemoteViews-callable but don't
   * request a layout, and a launcher reapplies an update to the views it already has, after it has laid out the
   * new size: on its own, a widget that grew would keep the old bounds. Hiding the artwork and showing it again
   * in the same update requests a layout (both are applied before the next frame, so nothing flickers).
   */
  internal fun applyArtworkBounds(views: RemoteViews, viewId: Int, maxWidthPx: Int, maxHeightPx: Int) {
    views.setInt(viewId, "setMaxWidth", maxWidthPx)
    views.setInt(viewId, "setMaxHeight", maxHeightPx)
    views.setViewVisibility(viewId, View.GONE)
    views.setViewVisibility(viewId, View.VISIBLE)
  }

  internal fun artworkUri(context: Context, state: State): Uri {
    if (state.awaiting == null) return state.session?.getCoverUri(context) ?: CoverArt.defaultUri()
    return state.awaitingCoverUri ?: CoverArt.defaultUri()
  }

  // Loads the cover off the main thread (same Glide source as before), then redraws with it. A responsive
  // RemoteViews can't be patched after the fact, so the whole widget is redrawn once the bitmap is ready.
  private fun loadArtwork(context: Context, key: String) {
    if (loadingArtworkKey == key) return
    loadingArtworkKey = key
    val appContext = context.applicationContext
    artworkExecutor.execute {
      val future = Glide.with(appContext).asBitmap().load(Uri.parse(key)).apply(RequestOptions().override(ARTWORK_PX, ARTWORK_PX)).submit()
      val bitmap =
        try {
          future.get(20, TimeUnit.SECONDS)?.let { widgetArtwork(it) }
        } catch (e: Exception) {
          DLog.w(TAG, "Widget artwork unavailable: ${e.javaClass.simpleName}")
          null
        }
      mainHandler.post {
        Glide.with(appContext).clear(future)
        if (loadingArtworkKey == key) loadingArtworkKey = null
        if (bitmap != null) {
          artworkKey = key
          artworkBitmap = bitmap
          failedArtworkKey = null
        } else {
          failedArtworkKey = key
        }
        val current = lastState ?: fallbackState(appContext)
        if (artworkUri(appContext, current).toString() == key) renderAll(appContext)
      }
    }
  }

  /**
   * The widget's own copy of a loaded cover. Its density is fixed so the 300px bitmap has a [ARTWORK_INTRINSIC_DP]
   * intrinsic size on every device: the FULL artwork bounds (FullArtwork, up to an expanded cover's full width)
   * then decide the displayed size, never the bitmap or the phone's screen density. COMPACT and WIDE size the
   * artwork from the row height, so this doesn't affect them.
   */
  internal fun widgetArtwork(source: Bitmap): Bitmap =
    source.copy(source.config ?: Bitmap.Config.ARGB_8888, false).apply { density = ARTWORK_DENSITY }

  /** The widget's existing actions, shared by every size (unchanged intents and flags). */
  private class Actions(val playPause: PendingIntent, val fastForward: PendingIntent, val rewind: PendingIntent, val open: PendingIntent) {
    companion object {
      fun create(context: Context): Actions {
        val openIntent = Intent(context, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        val open = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Actions(
          playPause = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE),
          fastForward = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_FAST_FORWARD),
          rewind = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_REWIND),
          open = open
        )
      }
    }
  }
}
