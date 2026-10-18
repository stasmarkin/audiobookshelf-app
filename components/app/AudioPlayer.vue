<template>
  <!-- Main container - only shows when playback session exists, applies fullscreen/platform classes -->
  <div v-if="playbackSession" id="streamContainer" class="fixed top-0 left-0 layout-wrapper right-0 z-50 pointer-events-none" :class="[{ fullscreen: showFullscreen, 'ios-player': $platform === 'ios', 'web-player': $platform === 'web' }, faceplateHooks.classes]" :style="faceplateHooks.vars">
    <!-- Fullscreen overlays: colored background and menu overlays -->
    <div v-if="showFullscreen" class="w-full h-full z-10 absolute top-0 left-0 pointer-events-auto" :style="{ backgroundColor: coverChrome.backdrop }">
      <!-- Background gradient for player -->
      <div class="w-full h-full absolute top-0 left-0 pointer-events-none" style="background: var(--gradient-audio-player)" />
      <!-- Faceplate top chrome plate (theme/playerLayout.js): decorative, never a touch target -->
      <div v-if="faceplateHooks.plates.top" class="player-plate player-plate-top" aria-hidden="true" />

      <!-- Collapse button - minimizes player -->
      <div class="top-4 left-4 absolute cursor-pointer">
        <span class="chrome-key collapse-key material-symbols text-5xl" :class="{ 'text-black text-opacity-75': coverChrome.darkForeground }" @click="collapseFullscreen">keyboard_arrow_down</span>
      </div>

      <!-- Cast button - Chromecast toggle -->
      <div v-show="showCastBtn" class="top-6 right-16 absolute cursor-pointer">
        <span class="chrome-key cast-key material-symbols text-3xl" :class="coverChrome.darkForeground ? 'text-black' : ''" @click="castClick">{{ isCasting ? 'cast_connected' : 'cast' }}</span>
      </div>

      <!-- Player options menu button -->
      <div class="top-6 right-4 absolute cursor-pointer">
        <span class="chrome-key menu-key material-symbols text-3xl" :class="{ 'text-black text-opacity-75': coverChrome.darkForeground }" @click="showMoreMenuDialog = true">more_vert</span>
      </div>

      <!-- Playback method indicator (Direct/Local/Transcode) -->
      <p class="playback-method top-4 absolute left-0 right-0 mx-auto text-center uppercase tracking-widest text-opacity-75 z-50" :class="{ 'text-black text-opacity-75': coverChrome.darkForeground }" style="font-size: 10px">{{ isDirectPlayMethod ? $strings.LabelPlaybackDirect : isLocalPlayMethod ? $strings.LabelPlaybackLocal : $strings.LabelPlaybackTranscode }}</p>
    </div>

    <!-- Overall book progress bar -->
    <div v-if="playerSettings.useChapterTrack && playerSettings.useTotalTrack && showFullscreen" class="absolute total-track w-full z-30 px-6">
      <div class="flex">
        <p class="font-mono text-fg" style="font-size: 0.8rem">{{ currentTimePretty }}</p>
        <div class="flex-grow" />
        <p class="font-mono text-fg" style="font-size: 0.8rem">{{ totalTimeRemainingPretty }}</p>
      </div>
      <div class="w-full">
        <div class="h-1 w-full bg-track/50 relative rounded-full overflow-hidden">
          <div ref="totalReadyTrack" class="h-full bg-track-buffered absolute top-0 left-0 pointer-events-none rounded-full" />
          <div ref="totalBufferedTrack" class="h-full bg-track absolute top-0 left-0 pointer-events-none rounded-full" />
          <div ref="totalPlayedTrack" class="h-full bg-track-cursor absolute top-0 left-0 pointer-events-none rounded-full" />
        </div>
      </div>
    </div>

    <!-- Book cover wrapper  -->
    <div class="cover-wrapper absolute z-30 pointer-events-auto" @click="clickContainer">
      <div class="w-full h-full flex justify-center">
        <covers-book-cover v-if="libraryItem || localLibraryItemCoverSrc" ref="cover" :library-item="libraryItem" :download-cover="localLibraryItemCoverSrc" :width="bookCoverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" raw @imageLoaded="coverImageLoaded" />
      </div>

      <!-- Sync failed indicator - shows error icon if progress sync failed -->
      <div v-if="syncStatus === $constants.SyncStatus.FAILED" class="absolute top-0 left-0 w-full h-full flex items-center justify-center z-30" @click.stop="showSyncsFailedDialog">
        <span class="material-symbols text-error text-3xl">error</span>
      </div>
    </div>

    <!-- Title and author text -->
    <div class="title-author-texts absolute z-30 left-0 right-0 overflow-hidden" @click="clickTitleAndAuthor">
      <div ref="titlewrapper" class="overflow-hidden relative">
        <p class="title-text whitespace-nowrap"></p>
      </div>
      <p class="author-text text-fg text-opacity-75 truncate">{{ authorName }}</p>
    </div>

    <div id="playerContent" class="playerContainer w-full z-20 absolute bottom-0 left-0 right-0 p-2 pointer-events-auto transition-all" :style="{ backgroundColor: showFullscreen ? '' : coverChrome.backdrop }" @click="clickContainer">
      <!-- Faceplate console plate around the transport and utility banks: decorative, never a touch target -->
      <div v-if="faceplateHooks.plates.console" class="player-plate player-plate-console" aria-hidden="true" />
      <!-- Top controls bar - fullscreen only: bookmarks, speed, sleep timer, chapters -->
      <div v-if="showFullscreen" class="utility-row absolute bottom-4 left-0 right-0 w-full pb-4 pt-2 mx-auto px-6" style="max-width: 414px">
        <div class="flex items-center justify-between pointer-events-auto">
          <!-- Optional queue icon: list glyph with a bordered "Q" badge, matches the chapters icon style -->
          <div v-if="playerSettings.showQueueIcon" class="player-key utility-key queue-key relative cursor-pointer" @click="$emit('showQueue')">
            <span class="material-symbols text-3xl text-fg-muted">format_list_bulleted</span>
            <span class="absolute -bottom-1 -right-1 flex items-center justify-center rounded-full border border-fg-muted bg-bg text-fg-muted" style="width: 14px; height: 14px; font-size: 9px; line-height: 1">Q</span>
          </div>
          <!-- Bookmark key: shown for every server book, online or not (the list is the cached user data; a write that
               cannot reach the server reports its own error), so it never drops out of the utility row -->
          <span v-if="!isPodcast && serverLibraryItemId" class="player-key utility-key bookmark-key material-symbols text-3xl text-fg-muted cursor-pointer" :class="{ fill: bookmarks.length }" @click="$emit('showBookmarks')">bookmark</span>
          <!-- hidden for podcasts but still using this as a placeholder -->
          <span v-else class="material-symbols text-3xl text-white text-opacity-0">bookmark</span>

          <span class="speed-readout font-mono text-fg-muted cursor-pointer" style="font-size: 1.35rem" @click="$emit('selectPlaybackSpeed')">{{ playbackRateLabel }}</span>
          <!-- Sleep key: the wrapper is the key (flex, so it is exactly the icon's box); the icon is its legend -->
          <span v-if="!sleepTimerRunning" class="player-key utility-key sleep-key flex cursor-pointer" @click.stop="$emit('showSleepTimer')">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-fg-muted" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
            </svg>
          </span>
          <div v-else class="sleep-display h-7 w-7 flex items-center justify-around cursor-pointer" @click.stop="$emit('showSleepTimer')">
            <p class="sleep-readout text-xl font-mono text-success">{{ sleepTimeRemainingPretty }}</p>
          </div>

          <span class="player-key utility-key chapters-key material-symbols text-3xl text-fg cursor-pointer" :class="chapters.length ? 'text-opacity-75' : 'text-opacity-10 key-disabled'" @click="clickChaptersBtn">format_list_bulleted</span>
        </div>
      </div>
      <div v-else class="w-full h-full absolute top-0 left-0 pointer-events-none" style="background: var(--gradient-minimized-audio-player)" />

      <!-- Playback controls - jump buttons, play/pause, chapter navigation -->
      <div id="playerControls" class="absolute right-0 bottom-0 mx-auto" style="max-width: 414px">
        <div class="flex items-center max-w-full" :class="[playerSettings.lockUi ? 'justify-center' : 'justify-between', { 'long-jumps': showLongJumpBackwards || showLongJumpForward }]">
          <span v-show="showFullscreen && !playerSettings.lockUi" class="player-key material-symbols next-icon text-fg cursor-pointer" :class="showLoadingState ? 'text-opacity-10 key-disabled' : 'text-opacity-75'" @click.stop="jumpChapterStart">first_page</span>
          <div v-if="showLongJumpBackwards" class="player-key jump-icon text-fg cursor-pointer flex flex-col items-center" :class="showLoadingState ? 'text-opacity-10 key-disabled' : 'text-opacity-75'" @click.stop="longJumpBackwards">
            <span class="material-symbols text-3xl leading-none">keyboard_double_arrow_left</span>
            <span class="jump-label text-[10px] font-semibold leading-tight">{{ longJumpBackwardsLabel }}</span>
          </div>
          <div v-show="!playerSettings.lockUi" class="player-key jump-icon text-fg cursor-pointer flex flex-col items-center" :class="showLoadingState ? 'text-opacity-10 key-disabled' : 'text-opacity-75'" @click.stop="jumpBackwards">
            <span class="material-symbols text-3xl leading-none">{{ showFullscreen ? 'chevron_left' : 'replay' }}</span>
            <span v-if="showFullscreen" class="jump-label text-[10px] font-semibold leading-tight">{{ jumpBackwardsLabel }}</span>
          </div>
          <div class="play-btn cursor-pointer shadow-sm flex items-center justify-center rounded-full text-primary mx-4 relative overflow-hidden" :style="{ backgroundColor: coverChrome.control }" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
            <div v-if="coverChrome.controlWash" class="absolute top-0 left-0 w-full h-full bg-white bg-opacity-20 pointer-events-none" />

            <span v-if="!showLoadingState" class="material-symbols fill" :class="{ 'text-white': coverChrome.control && !coverChrome.isLight, 'text-black': coverChrome.usesTheme && coverChrome.isLight, 'animate-spin': seekLoading }">{{ seekLoading ? 'autorenew' : !isPlaying ? 'play_arrow' : 'pause' }}</span>
            <widgets-spinner-icon v-else class="h-8 w-8" />
          </div>
          <div v-show="!playerSettings.lockUi" class="player-key jump-icon text-fg cursor-pointer flex flex-col items-center" :class="showLoadingState ? 'text-opacity-10 key-disabled' : 'text-opacity-75'" @click.stop="jumpForward">
            <span class="material-symbols text-3xl leading-none">{{ showFullscreen ? 'chevron_right' : 'forward_media' }}</span>
            <span v-if="showFullscreen" class="jump-label text-[10px] font-semibold leading-tight">{{ jumpForwardLabel }}</span>
          </div>
          <div v-if="showLongJumpForward" class="player-key jump-icon text-fg cursor-pointer flex flex-col items-center" :class="showLoadingState ? 'text-opacity-10 key-disabled' : 'text-opacity-75'" @click.stop="longJumpForward">
            <span class="material-symbols text-3xl leading-none">keyboard_double_arrow_right</span>
            <span class="jump-label text-[10px] font-semibold leading-tight">{{ longJumpForwardLabel }}</span>
          </div>
          <span v-show="showFullscreen && !playerSettings.lockUi" class="player-key material-symbols next-icon text-fg cursor-pointer" :class="nextChapter && !showLoadingState ? 'text-opacity-75' : 'text-opacity-10 key-disabled'" @click.stop="jumpNextChapter">last_page</span>
        </div>
      </div>

      <!-- Chapter progress bar -->
      <div id="playerTrack" class="absolute left-0 w-full px-6">
        <!-- Faceplate seek/time module plate: decorative, drawn behind the recessed well, never a touch target -->
        <div v-if="faceplateHooks.plates.seek" class="player-plate player-plate-seek" aria-hidden="true" />
        <div class="flex pointer-events-none">
          <p class="track-time font-mono text-fg" ref="currentTimestamp">0:00</p>
          <div class="flex-grow" />
          <p class="track-time font-mono text-fg">{{ timeRemainingPretty }}</p>
        </div>
        <div class="relative">
          <div ref="track" class="h-1.5 w-full bg-track/50 relative rounded-full overflow-hidden" :class="{ 'animate-pulse': showLoadingState, 'seek-dragging': isDraggingCursor }" @click.stop>
            <div ref="readyTrack" class="h-full bg-track-buffered absolute top-0 left-0 rounded-full pointer-events-none" />
            <div ref="bufferedTrack" class="h-full bg-track absolute top-0 left-0 rounded-full pointer-events-none" />
            <div ref="playedTrack" class="h-full bg-track-cursor absolute top-0 left-0 rounded-full pointer-events-none" />
            <div ref="trackCursor" class="h-7 w-7 rounded-full absolute pointer-events-auto flex items-center justify-center" :style="{ top: '-11px' }" :class="{ 'opacity-0': playerSettings.lockUi || !showFullscreen }" @touchstart="touchstartCursor">
              <div class="bg-track-cursor rounded-full w-3.5 h-3.5 pointer-events-none" />
            </div>
          </div>
          <!-- Invisible seek touch target over the thumb. The track clips the thumb's own box to its 6px height, so this sits outside the clip; it follows the thumb (updateTrack) and starts the same drag -->
          <div ref="trackCursorHit" class="h-8 w-7 absolute" :class="playerSettings.lockUi || !showFullscreen ? 'pointer-events-none' : 'pointer-events-auto'" :style="{ top: '-13px' }" @touchstart="touchstartCursor" @click.stop />
        </div>
      </div>
    </div>

    <!-- Chapters modal - lists chapters for navigation -->
    <modals-chapters-modal v-model="showChapterModal" :current-chapter="currentChapter" :chapters="chapters" :playback-rate="currentPlaybackRate" @select="selectChapter" />
    <!-- More menu dialog - player settings and options -->
    <modals-dialog v-model="showMoreMenuDialog" :items="menuItems" width="80vw" @action="clickMenuAction" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
import { getAverageColorFromCoverUrl } from '@/utils/coverAverageColor'
import { normalizePlaybackRate, formatPlaybackRate } from '@/utils/playbackRate'
import coverPresentation from '@/theme/coverPresentation'
import playerLayout from '@/theme/playerLayout'
import WrappingMarquee from '@/assets/WrappingMarquee.js'
import { COARSE_STEP_S, coarseTime, coarseClock, sleepRemainingLabel, createPainter } from '@/utils/playerClock'
import jumpLabelMixin from '@/mixins/jumpLabel'

const setText = (el, value) => (el.innerText = value)
const setWidth = (el, value) => (el.style.width = value)
const setLeft = (el, value) => (el.style.left = value)

export default {
  props: {
    bookmarks: {
      type: Array,
      default: () => []
    },
    sleepTimerRunning: Boolean,
    sleepTimeRemaining: Number,
    serverLibraryItemId: String
  },
  mixins: [jumpLabelMixin],
  data() {
    return {
      windowHeight: 0,
      windowWidth: 0,
      safeAreaInsets: null,
      coverImageRatio: 0,
      playbackSession: null,
      showChapterModal: false,
      showFullscreen: false,
      totalDuration: 0,
      currentPlaybackRate: 1,
      currentTime: 0,
      bufferedTime: 0,
      playInterval: null,
      trackWidth: 0,
      isPlaying: false,
      isEnded: false,
      volume: 0.5,
      readyTrackWidth: 0,
      seekedTime: 0,
      seekLoading: false,
      touchStartY: 0,
      touchStartTime: 0,
      playerSettings: {
        useChapterTrack: false,
        useTotalTrack: true,
        scaleElapsedTimeBySpeed: true,
        lockUi: false,
        showQueueIcon: false,
        updateEveryMinute: false
      },
      isLoading: false,
      isCheckingServerProgress: false,
      isDraggingCursor: false,
      draggingTouchStartX: 0,
      draggingTouchStartTime: 0,
      draggingCurrentTime: 0,
      syncStatus: 0,
      showMoreMenuDialog: false,
      coverRgb: 'rgb(55, 56, 56)',
      coverBgIsLight: false,
      titleMarquee: null,
      isRefreshingUI: false
    }
  },
  watch: {
    coverIdentity() {
      this.coverImageRatio = 0
    },
    showFullscreen(val) {
      this.updateScreenSize()
      this.$store.commit('setPlayerFullscreen', !!val)
      document.querySelector('body').style.backgroundColor = this.showFullscreen ? this.coverChrome.backdrop : ''
    },
    // Only a cover-color *policy* change (theme switch) re-syncs the fullscreen body background here, so a
    // stale backdrop from the previous policy can't remain; legacy cover updates behave exactly as before.
    'coverChrome.usesTheme'() {
      if (this.showFullscreen) document.querySelector('body').style.backgroundColor = this.coverChrome.backdrop
    },
    bookCoverAspectRatio() {
      this.updateScreenSize()
    },
    // A theme switch or a Chapter Track change can move the artwork (theme/playerLayout.js): re-size it. With a
    // non-equipment theme the width never changes this way, so this never fires there
    fullscreenBookCoverWidth() {
      this.updateScreenSize()
    },
    title(val) {
      if (this.titleMarquee) this.titleMarquee.init(val)
    },
    // The page content above the collapsed player is sized from its plate height (assets/app.css #content.playerOpen)
    'miniLayout.height': {
      handler(height) {
        document.documentElement.style.setProperty('--mini-player-height', height + 'px')
      },
      immediate: true
    }
  },
  computed: {
    // The validated theme, read once: its presentation tokens drive the cover-color and layout projections below
    presentationTheme() {
      return this.$theme.theme
    },
    // Chrome colors for the theme's presentation.cover-color policy (theme/coverPresentation.js):
    // legacy = tinted from the sampled cover (previous behavior), theme = the theme's own surfaces
    coverChrome() {
      return coverPresentation.coverColorPresentation(this.presentationTheme, { rgb: this.coverRgb, isLight: this.coverBgIsLight })
    },
    menuItems() {
      const items = []
      // TODO: Implement on iOS
      if (this.$platform !== 'ios' && !this.isPodcast && this.mediaId) {
        items.push({
          text: this.$strings.ButtonHistory,
          value: 'history',
          icon: 'history'
        })
      }

      if (this.$store.state.playbackQueue) {
        items.push({
          text: 'View Queue',
          value: 'queue',
          icon: 'queue_music'
        })
      }

      items.push(
        ...[
          {
            text: this.playerSettings.showQueueIcon ? 'Hide Queue Icon' : 'Show Queue Icon',
            value: 'show_queue_icon',
            icon: this.playerSettings.showQueueIcon ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.$strings.LabelTotalTrack,
            value: 'total_track',
            icon: this.playerSettings.useTotalTrack ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.$strings.LabelChapterTrack,
            value: 'chapter_track',
            icon: this.playerSettings.useChapterTrack ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.$strings.LabelScaleElapsedTimeBySpeed,
            value: 'scale_elapsed_time',
            icon: this.playerSettings.scaleElapsedTimeBySpeed ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.$strings.LabelUpdatePlayerEveryMinute,
            value: 'update_every_minute',
            icon: this.playerSettings.updateEveryMinute ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.playerSettings.lockUi ? this.$strings.LabelUnlockPlayer : this.$strings.LabelLockPlayer,
            value: 'lock',
            icon: this.playerSettings.lockUi ? 'lock' : 'lock_open'
          },
          {
            text: this.$strings.LabelClosePlayer,
            value: 'close',
            icon: 'close'
          }
        ]
      )

      return items
    },
    jumpForwardLabel() {
      return this.getJumpLabel(this.jumpForwardTime)
    },
    jumpBackwardsLabel() {
      return this.getJumpLabel(this.jumpBackwardsTime)
    },
    jumpForwardTime() {
      return this.$store.getters['getJumpForwardTime']
    },
    jumpBackwardsTime() {
      return this.$store.getters['getJumpBackwardsTime']
    },
    longJumpForwardTime() {
      return this.$store.getters['getLongJumpForwardTime']
    },
    longJumpBackwardsTime() {
      return this.$store.getters['getLongJumpBackwardsTime']
    },
    longJumpForwardLabel() {
      return this.getJumpLabel(this.longJumpForwardTime)
    },
    longJumpBackwardsLabel() {
      return this.getJumpLabel(this.longJumpBackwardsTime)
    },
    // Long jump keys exist only in the full player, and only for a direction whose long jump is on
    showLongJumpBackwards() {
      return this.showFullscreen && !this.playerSettings.lockUi && this.longJumpBackwardsTime > 0
    },
    showLongJumpForward() {
      return this.showFullscreen && !this.playerSettings.lockUi && this.longJumpForwardTime > 0
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookCoverWidth() {
      if (this.showFullscreen) return this.fullscreenBookCoverWidth
      return 46 / this.bookCoverAspectRatio
    },
    // The full player's layout for the theme's presentation (theme/playerLayout.js): the previous layout for every
    // theme but the equipment finish, which presents the portrait player as a faceplate of stacked modules
    fullscreenLayout() {
      return playerLayout.fullscreenLayout(this.presentationTheme, {
        width: this.windowWidth,
        height: this.windowHeight,
        aspectRatio: this.bookCoverAspectRatio,
        twoRail: !!(this.playerSettings.useChapterTrack && this.playerSettings.useTotalTrack),
        imageRatio: this.coverImageRatio,
        insets: this.safeAreaInsets
      })
    },
    fullscreenBookCoverWidth() {
      return this.fullscreenLayout.coverWidth
    },
    // The collapsed player's layout for the theme's presentation (theme/playerLayout.js): the previous 120px plate, or the
    // equipment finish's compact plate without the empty band under the seek strip
    miniLayout() {
      return playerLayout.miniLayout(this.presentationTheme)
    },
    // Hook classes, CSS variables and decorative plates: the full player's while it is shown; while collapsed only the
    // collapsed layout's (non-equipment themes get none either way)
    faceplateHooks() {
      if (!this.showFullscreen) return { classes: this.miniLayout.classes, vars: this.miniLayout.vars, plates: { top: false, console: false, seek: false } }
      const { classes, vars, plates } = this.fullscreenLayout
      return { classes, vars, plates }
    },
    showLoadingState() {
      return this.isLoading || this.isCheckingServerProgress
    },
    showCastBtn() {
      return this.$store.state.isCastAvailable
    },
    isCasting() {
      return this.mediaPlayer === 'cast-player'
    },
    mediaPlayer() {
      return this.playbackSession?.mediaPlayer || null
    },
    mediaType() {
      return this.playbackSession?.mediaType || null
    },
    isPodcast() {
      return this.mediaType === 'podcast'
    },
    mediaMetadata() {
      return this.playbackSession?.mediaMetadata || null
    },
    libraryItem() {
      return this.playbackSession?.libraryItem || null
    },
    localLibraryItem() {
      return this.playbackSession?.localLibraryItem || null
    },
    localLibraryItemCoverSrc() {
      var localItemCover = this.localLibraryItem?.coverContentUrl || null
      if (localItemCover) return Capacitor.convertFileSrc(localItemCover)
      return null
    },
    // Which artwork the player shows: its loaded aspect ratio is forgotten only when this changes
    coverIdentity() {
      return `${this.libraryItem?.id || ''}|${this.localLibraryItemCoverSrc || ''}`
    },
    playMethod() {
      return this.playbackSession?.playMethod || 0
    },
    isLocalPlayMethod() {
      return this.playMethod == this.$constants.PlayMethod.LOCAL
    },
    isDirectPlayMethod() {
      return this.playMethod == this.$constants.PlayMethod.DIRECTPLAY
    },
    title() {
      const mediaItemTitle = this.playbackSession?.displayTitle || this.mediaMetadata?.title || 'Title'
      if (this.currentChapterTitle) {
        if (this.showFullscreen) return this.currentChapterTitle
        return `${mediaItemTitle} | ${this.currentChapterTitle}`
      }
      return mediaItemTitle
    },
    authorName() {
      if (this.playbackSession) return this.playbackSession.displayAuthor
      return this.mediaMetadata?.authorName || 'Author'
    },
    chapters() {
      return this.playbackSession?.chapters || []
    },
    currentChapter() {
      return this.chapterAt(this.currentTime)
    },
    nextChapter() {
      if (!this.chapters.length) return
      return this.chapters.find((c) => Number(Number(c.start).toFixed(2)) > this.currentTime)
    },
    currentChapterTitle() {
      return this.currentChapter?.title || ''
    },
    currentChapterDuration() {
      return this.currentChapter ? this.currentChapter.end - this.currentChapter.start : this.totalDuration
    },
    totalDurationPretty() {
      return this.$secondsToTimestamp(this.totalDuration)
    },
    // "Update once a minute" (utils/playerClock.js). A drag always draws exact times, so a precise seek stays possible
    coarseDrawing() {
      return this.playerSettings.updateEveryMinute && !this.isDraggingCursor
    },
    // The position the readouts and bars are drawn from
    drawnTime() {
      return this.drawTime(this.isDraggingCursor ? this.draggingCurrentTime : this.currentTime, this.currentChapter)
    },
    currentTimePretty() {
      let currentTimeToUse = this.drawnTime
      if (this.playerSettings.scaleElapsedTimeBySpeed) {
        currentTimeToUse = currentTimeToUse / this.currentPlaybackRate
      }
      return this.clockText(currentTimeToUse)
    },
    timeRemaining() {
      let currentTimeToUse = this.drawnTime
      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        var currChapTime = currentTimeToUse - this.currentChapter.start
        return (this.currentChapterDuration - currChapTime) / this.currentPlaybackRate
      }
      return this.totalTimeRemaining
    },
    totalTimeRemaining() {
      let currentTimeToUse = this.drawnTime
      return (this.totalDuration - currentTimeToUse) / this.currentPlaybackRate
    },
    totalTimeRemainingPretty() {
      if (this.totalTimeRemaining < 0) {
        return this.clockText(this.totalTimeRemaining * -1)
      }
      return '-' + this.clockText(this.totalTimeRemaining, true)
    },
    timeRemainingPretty() {
      if (this.timeRemaining < 0) {
        return this.clockText(this.timeRemaining * -1)
      }
      return '-' + this.clockText(this.timeRemaining, true)
    },
    playbackRateLabel() {
      return formatPlaybackRate(this.currentPlaybackRate)
    },
    sleepTimeRemainingPretty() {
      return sleepRemainingLabel(this.sleepTimeRemaining, this.playerSettings.updateEveryMinute)
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    mediaId() {
      if (this.isPodcast || !this.playbackSession) return null
      if (this.playbackSession.libraryItemId) {
        return this.playbackSession.episodeId ? `${this.playbackSession.libraryItemId}-${this.playbackSession.episodeId}` : this.playbackSession.libraryItemId
      }
      const localLibraryItem = this.playbackSession.localLibraryItem
      if (!localLibraryItem) return null

      return this.playbackSession.localEpisodeId ? `${localLibraryItem.id}-${this.playbackSession.localEpisodeId}` : localLibraryItem.id
    }
  },
  methods: {
    chapterAt(time) {
      if (!this.chapters.length) return null
      return this.chapters.find((ch) => Number(Number(ch.start).toFixed(2)) <= time && Number(Number(ch.end).toFixed(2)) > time)
    },
    // Coarse drawing: whole minutes of what the elapsed readout shows (listening time when it is scaled by speed, media
    // time otherwise), counted from the chapter start on the chapter track so its bar never starts below zero. The end
    // of the book is drawn exactly, so a finished book reads 100% and no time left
    drawTime(time, chapter) {
      if (!this.coarseDrawing) return time
      // Before metadata arrives the duration is 0, which is not the end
      if (this.totalDuration > 0 && (this.isEnded || time >= this.totalDuration)) return Math.min(time, this.totalDuration)
      const step = COARSE_STEP_S * (this.playerSettings.scaleElapsedTimeBySpeed && this.currentPlaybackRate > 0 ? this.currentPlaybackRate : 1)
      const origin = this.playerSettings.useChapterTrack && chapter ? Number(chapter.start) : 0
      return coarseTime(time, step, origin)
    },
    clockText(seconds, roundUp = false) {
      return this.coarseDrawing ? coarseClock(seconds, roundUp) : this.$secondsToTimestamp(seconds)
    },
    // Every per-second write to the seek row goes through here: with coarse drawing an unchanged value is not rewritten
    // (each write is a refresh on e-paper); otherwise it writes every time, as before
    paint(el, key, value) {
      return this.painter(el, key, value, key === 'text' ? setText : key === 'left' ? setLeft : setWidth, !this.coarseDrawing)
    },
    showSyncsFailedDialog() {
      Dialog.alert({
        title: this.$strings.HeaderProgressSyncFailed,
        message: this.$strings.MessageProgressSyncFailed,
        cancelText: this.$strings.ButtonOk
      })
    },
    clickChaptersBtn() {
      if (!this.chapters.length) return
      this.showChapterModal = true
    },
    async coverImageLoaded(fullCoverUrl) {
      if (!fullCoverUrl) return
      // The artwork's own aspect ratio sizes the landscape artwork frame (theme/playerLayout.js landscapeArtwork)
      const img = this.$refs.cover?.$refs.cover
      if (img?.naturalWidth && img.naturalHeight) this.coverImageRatio = img.naturalHeight / img.naturalWidth
      const avg = await getAverageColorFromCoverUrl(this, fullCoverUrl)
      if (!avg) {
        this.coverRgb = 'rgb(55, 56, 56)'
        this.coverBgIsLight = false
      } else {
        this.coverRgb = avg.rgba
        this.coverBgIsLight = avg.isLight
      }
    },
    clickTitleAndAuthor() {
      if (!this.showFullscreen) return
      const llid = this.serverLibraryItemId || this.libraryItem?.id || this.localLibraryItem?.id
      if (llid) {
        this.$router.push(`/item/${llid}`)
        this.showFullscreen = false
      }
    },
    async selectChapter(chapter) {
      await this.$hapticsImpact()
      this.seek(chapter.start)
      this.showChapterModal = false
    },
    async castClick() {
      await this.$hapticsImpact()
      if (this.isLocalPlayMethod) {
        this.$eventBus.$emit('cast-local-item')
        return
      }
      AbsAudioPlayer.requestSession()
    },
    clickContainer() {
      this.expandToFullscreen()
    },
    expandToFullscreen() {
      this.showFullscreen = true
      if (this.titleMarquee) this.titleMarquee.reset()

      this.$nextTick(() => {
        this.measureAndUpdateTrackWidth()
      })
    },
    collapseFullscreen() {
      this.showFullscreen = false
      if (this.titleMarquee) this.titleMarquee.reset()

      this.forceCloseDropdownMenu()
    },
    async jumpNextChapter() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return
      if (!this.nextChapter) return
      this.seek(this.nextChapter.start)
    },
    async jumpChapterStart() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return
      if (!this.currentChapter) {
        return this.restart()
      }

      // If 4 seconds or less into current chapter, then go to previous
      if (this.currentTime - this.currentChapter.start <= 4) {
        const currChapterIndex = this.chapters.findIndex((ch) => Number(ch.start) <= this.currentTime && Number(ch.end) >= this.currentTime)
        if (currChapterIndex > 0) {
          const prevChapter = this.chapters[currChapterIndex - 1]
          this.seek(prevChapter.start)
        }
      } else {
        this.seek(this.currentChapter.start)
      }
    },
    showSleepTimerModal() {
      this.$emit('showSleepTimer')
    },
    async setPlaybackSpeed(speed) {
      console.log(`[AudioPlayer] Set Playback Rate: ${speed}`)
      this.currentPlaybackRate = speed
      this.updateTimestamp()
      this.updateCoarseTrack()
      AbsAudioPlayer.setPlaybackSpeed({ value: speed })
    },
    restart() {
      this.seek(0)
    },
    async jumpBackwards() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return
      AbsAudioPlayer.seekBackward({ value: this.jumpBackwardsTime })
    },
    async jumpForward() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return
      AbsAudioPlayer.seekForward({ value: this.jumpForwardTime })
    },
    async longJumpBackwards() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return
      AbsAudioPlayer.seekBackward({ value: this.longJumpBackwardsTime })
    },
    async longJumpForward() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return
      AbsAudioPlayer.seekForward({ value: this.longJumpForwardTime })
    },
    setStreamReady() {
      this.readyTrackWidth = this.trackWidth
      this.updateReadyTrack()
    },
    setChunksReady(chunks, numSegments) {
      let largestSeg = 0
      for (let i = 0; i < chunks.length; i++) {
        const chunk = chunks[i]
        if (typeof chunk === 'string') {
          const chunkRange = chunk.split('-').map((c) => Number(c))
          if (chunkRange.length < 2) continue
          if (chunkRange[1] > largestSeg) largestSeg = chunkRange[1]
        } else if (chunk > largestSeg) {
          largestSeg = chunk
        }
      }
      const percentageReady = largestSeg / numSegments
      const widthReady = Math.round(this.trackWidth * percentageReady)
      if (this.readyTrackWidth === widthReady) {
        return
      }
      this.readyTrackWidth = widthReady
      this.updateReadyTrack()
    },
    updateReadyTrack() {
      if (!this.$refs.readyTrack) return

      if (this.playerSettings.useChapterTrack) {
        if (this.$refs.totalReadyTrack && this.trackWidth) {
          const readyPercent = (this.readyTrackWidth / this.trackWidth) * 100
          this.$refs.totalReadyTrack.style.width = readyPercent + '%'
        }
        this.$refs.readyTrack.style.width = '100%'
      } else if (this.trackWidth) {
        const readyPercent = (this.readyTrackWidth / this.trackWidth) * 100
        this.$refs.readyTrack.style.width = readyPercent + '%'
      }
    },
    // `at`: draw this position instead of the current one (a seek target, before playback reports it)
    updateTimestamp(at) {
      const ts = this.$refs.currentTimestamp
      if (!ts) {
        console.error('No timestamp el')
        return
      }

      const chapter = at === undefined ? this.currentChapter : this.chapterAt(at)
      let currentTime = at === undefined ? this.drawnTime : this.drawTime(at, chapter)
      if (this.playerSettings.useChapterTrack && chapter) {
        currentTime = Math.max(0, currentTime - chapter.start)
      }
      if (this.playerSettings.scaleElapsedTimeBySpeed) {
        currentTime = currentTime / this.currentPlaybackRate
      }

      this.paint(ts, 'text', this.clockText(currentTime))
    },
    timeupdate() {
      if (!this.$refs.playedTrack) {
        console.error('Invalid no played track ref')
        return
      }
      this.$emit('updateTime', this.currentTime)

      if (this.seekLoading) {
        this.seekLoading = false
        if (this.$refs.playedTrack) {
          this.$refs.playedTrack.classList.remove('bg-yellow-300')
          this.$refs.playedTrack.classList.add('bg-gray-200')
        }
      }

      this.updateTimestamp()
      this.updateTrack()
    },
    // The coarse step follows the speed and the elapsed-time scaling, so the bars are redrawn with the time when they
    // change (also while paused, when no poll would do it)
    updateCoarseTrack() {
      if (this.playerSettings.updateEveryMinute) this.updateTrack()
    },
    // `at`: draw this position instead of the current one (a seek target, before playback reports it)
    updateTrack(at) {
      // Coarse drawing writes only what changes, so it never draws an unknown duration (x / 0 would read as finished)
      if (this.coarseDrawing && !(this.totalDuration > 0)) return
      // Update progress track UI
      const chapter = at === undefined ? this.currentChapter : this.chapterAt(at)
      const currentTimeToUse = at === undefined ? this.drawnTime : this.drawTime(at, chapter)
      const onChapterTrack = this.playerSettings.useChapterTrack && chapter
      // The buffered bar is a fixed media-time step: it does not depend on the playback speed
      const bufferedTime = this.coarseDrawing ? coarseTime(this.bufferedTime, COARSE_STEP_S, onChapterTrack ? Number(chapter.start) : 0) : this.bufferedTime
      let percentDone = currentTimeToUse / this.totalDuration
      const totalPercentDone = percentDone
      let bufferedPercent = bufferedTime / this.totalDuration
      const totalBufferedPercent = bufferedPercent

      if (onChapterTrack) {
        const chapterDuration = chapter.end - chapter.start
        const currChapTime = currentTimeToUse - chapter.start
        percentDone = currChapTime / chapterDuration
        bufferedPercent = Math.max(0, Math.min(1, (bufferedTime - chapter.start) / chapterDuration))
      }
      if (this.coarseDrawing) percentDone = Math.max(0, Math.min(1, percentDone))

      const playedPercent = percentDone * 100
      const bufferedPercentWidth = bufferedPercent * 100
      this.paint(this.$refs.playedTrack, 'width', playedPercent + '%')
      this.paint(this.$refs.bufferedTrack, 'width', bufferedPercentWidth + '%')

      if (this.$refs.trackCursor && this.$refs.track) {
        const trackWidth = this.$refs.track.clientWidth
        const left = Math.round(percentDone * trackWidth) - 14 + 'px'
        this.paint(this.$refs.trackCursor, 'left', left)
        this.paint(this.$refs.trackCursorHit, 'left', left)
      }

      if (this.playerSettings.useChapterTrack) {
        this.paint(this.$refs.totalPlayedTrack, 'width', totalPercentDone * 100 + '%')
        this.paint(this.$refs.totalBufferedTrack, 'width', totalBufferedPercent * 100 + '%')
      }
    },
    seek(time) {
      if (this.showLoadingState) return
      if (this.seekLoading) {
        console.error('Already seek loading', this.seekedTime)
        return
      }

      this.seekedTime = time
      this.seekLoading = true

      // Pass fractional seconds so seeks to non-integer chapter starts don't truncate
      AbsAudioPlayer.seek({ value: time })

      if (this.$refs.playedTrack) {
        if (this.coarseDrawing) {
          // Drawn at once, and already as the coarse position playback will report, so it is not redrawn a second later
          this.updateTimestamp(time)
          this.updateTrack(time)
        } else {
          const perc = time / this.totalDuration
          this.painter(this.$refs.playedTrack, 'width', perc * 100 + '%', setWidth, true)
        }

        this.$refs.playedTrack.classList.remove('bg-gray-200')
        this.$refs.playedTrack.classList.add('bg-yellow-300')
      }
    },
    async touchstartCursor(e) {
      if (!e || !e.touches || !this.$refs.track || !this.showFullscreen || this.playerSettings.lockUi) return

      await this.$hapticsImpact()
      this.isDraggingCursor = true
      this.draggingTouchStartX = e.touches[0].pageX
      this.draggingTouchStartTime = this.currentTime
      this.draggingCurrentTime = this.currentTime
      this.updateTrack()
    },
    async playPauseClick() {
      await this.$hapticsImpact()
      if (this.showLoadingState) return

      this.isPlaying = !!((await AbsAudioPlayer.playPause()) || {}).playing
      this.isEnded = false
    },
    setIsCheckingServerProgress(value) {
      this.isCheckingServerProgress = !!value
    },
    play() {
      AbsAudioPlayer.playPlayer()
      this.startPlayInterval()
      this.isPlaying = true
    },
    pause() {
      AbsAudioPlayer.pausePlayer()
      this.stopPlayInterval()
      this.isPlaying = false
    },
    startPlayInterval() {
      clearInterval(this.playInterval)
      this.playInterval = setInterval(async () => {
        var data = await AbsAudioPlayer.getCurrentTime()
        this.currentTime = Number(data.value.toFixed(2))
        this.bufferedTime = Number(data.bufferedTime.toFixed(2))
        this.timeupdate()
      }, 1000)
    },
    stopPlayInterval() {
      clearInterval(this.playInterval)
    },
    resetStream(startTime) {
      this.closePlayback()
    },
    touchstart(e) {
      if (!e.changedTouches || this.$store.state.globals.isModalOpen) return
      const touchPosY = e.changedTouches[0].pageY
      // when minimized only listen to touchstart on the player (its projected plate height)
      if (!this.showFullscreen && touchPosY < window.innerHeight - this.miniLayout.height) return

      // for ios
      if (!this.showFullscreen && e.pageX < 20) {
        e.preventDefault()
        e.stopImmediatePropagation()
      }

      this.touchStartY = touchPosY
      this.touchStartTime = Date.now()
    },
    touchend(e) {
      if (!e.changedTouches) return
      const touchDuration = Date.now() - this.touchStartTime
      const touchEndY = e.changedTouches[0].pageY
      const touchDistanceY = touchEndY - this.touchStartY

      // reset touch start data
      this.touchStartTime = 0
      this.touchStartY = 0

      if (this.isDraggingCursor) {
        // Ended before the seek, so a coarse player draws the released position coarse right away
        this.isDraggingCursor = false
        if (this.draggingCurrentTime !== this.currentTime) {
          this.seek(this.draggingCurrentTime)
        } else if (this.playerSettings.updateEveryMinute) {
          this.updateTimestamp()
          this.updateTrack()
        }
      } else {
        if (touchDuration > 1200) {
          // console.log('touch too long', touchDuration)
          return
        }
        if (this.showFullscreen) {
          // Touch start higher than touchend
          if (touchDistanceY > 100) {
            this.collapseFullscreen()
          }
        } else if (touchDistanceY < -100) {
          this.expandToFullscreen()
        }
      }
    },
    touchmove(e) {
      if (!this.isDraggingCursor || !e.touches) return

      const distanceMoved = e.touches[0].pageX - this.draggingTouchStartX
      let duration = this.totalDuration
      let minTime = 0
      let maxTime = duration
      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        duration = this.currentChapterDuration
        minTime = this.currentChapter.start
        maxTime = minTime + duration
      }

      const trackWidth = this.$refs.track?.clientWidth || this.trackWidth
      const timePerPixel = duration / trackWidth
      const newTime = this.draggingTouchStartTime + timePerPixel * distanceMoved
      this.draggingCurrentTime = Math.min(maxTime, Math.max(minTime, newTime))

      this.updateTimestamp()
      this.updateTrack()
    },
    async clickMenuAction(action) {
      await this.$hapticsImpact()
      this.showMoreMenuDialog = false
      this.$nextTick(() => {
        if (action === 'history') {
          this.$router.push(`/media/${this.mediaId}/history?title=${this.title}`)
          this.showFullscreen = false
        } else if (action === 'queue') {
          this.$emit('showQueue')
        } else if (action === 'show_queue_icon') {
          this.playerSettings.showQueueIcon = !this.playerSettings.showQueueIcon
          this.savePlayerSettings()
        } else if (action === 'scale_elapsed_time') {
          this.playerSettings.scaleElapsedTimeBySpeed = !this.playerSettings.scaleElapsedTimeBySpeed
          this.updateTimestamp()
          this.updateCoarseTrack()
          this.savePlayerSettings()
        } else if (action === 'update_every_minute') {
          this.playerSettings.updateEveryMinute = !this.playerSettings.updateEveryMinute
          this.painter = createPainter()
          this.updateTimestamp()
          this.updateTrack()
          this.savePlayerSettings()
        } else if (action === 'lock') {
          this.playerSettings.lockUi = !this.playerSettings.lockUi
          this.savePlayerSettings()
        } else if (action === 'chapter_track') {
          this.playerSettings.useChapterTrack = !this.playerSettings.useChapterTrack
          this.playerSettings.useTotalTrack = !this.playerSettings.useChapterTrack || this.playerSettings.useTotalTrack

          this.updateTimestamp()
          this.updateTrack()
          this.updateReadyTrack()
          this.updateUseChapterTrack()
          this.savePlayerSettings()
        } else if (action === 'total_track') {
          this.playerSettings.useTotalTrack = !this.playerSettings.useTotalTrack
          this.playerSettings.useChapterTrack = !this.playerSettings.useTotalTrack || this.playerSettings.useChapterTrack

          this.updateTimestamp()
          this.updateTrack()
          this.updateReadyTrack()
          this.updateUseChapterTrack()
          this.savePlayerSettings()
        } else if (action === 'close') {
          this.closePlayback()
        }
      })
    },
    updateUseChapterTrack() {
      // Chapter track in NowPlaying only supported on iOS for now
      if (this.$platform === 'ios') {
        AbsAudioPlayer.setChapterTrack({ enabled: this.playerSettings.useChapterTrack })
      }
    },
    forceCloseDropdownMenu() {
      if (this.$refs.dropdownMenu && this.$refs.dropdownMenu.closeMenu) {
        this.$refs.dropdownMenu.closeMenu()
      }
    },
    closePlayback() {
      this.endPlayback()
      AbsAudioPlayer.closePlayback()
    },
    endPlayback() {
      this.$store.commit('setPlaybackSession', null)
      this.showFullscreen = false
      this.isEnded = false
      this.isLoading = false
      this.playbackSession = null
    },
    async loadPlayerSettings() {
      const savedPlayerSettings = await this.$localStore.getPlayerSettings()
      if (!savedPlayerSettings) {
        // In 0.9.72-beta 'useChapterTrack', 'useTotalTrack' and 'playerLock' was replaced with 'playerSettings' JSON object
        // Check if this old key was set and if so migrate them over to 'playerSettings'
        const chapterTrackPref = await this.$localStore.getPreferenceByKey('useChapterTrack')
        if (chapterTrackPref) {
          this.playerSettings.useChapterTrack = chapterTrackPref === '1'
          const totalTrackPref = await this.$localStore.getPreferenceByKey('useTotalTrack')
          this.playerSettings.useTotalTrack = totalTrackPref === '1'
          const playerLockPref = await this.$localStore.getPreferenceByKey('playerLock')
          this.playerSettings.lockUi = playerLockPref === '1'
        }
        this.savePlayerSettings()
      } else {
        this.playerSettings.useChapterTrack = !!savedPlayerSettings.useChapterTrack
        this.playerSettings.useTotalTrack = !!savedPlayerSettings.useTotalTrack
        this.playerSettings.lockUi = !!savedPlayerSettings.lockUi
        this.playerSettings.scaleElapsedTimeBySpeed = !!savedPlayerSettings.scaleElapsedTimeBySpeed
        this.playerSettings.showQueueIcon = !!savedPlayerSettings.showQueueIcon
        this.playerSettings.updateEveryMinute = !!savedPlayerSettings.updateEveryMinute
      }
    },
    savePlayerSettings() {
      return this.$localStore.setPlayerSettings({ ...this.playerSettings })
    },
    //
    // Listeners from audio AbsAudioPlayer
    //
    onPlayingUpdate(data) {
      console.log('onPlayingUpdate', JSON.stringify(data))
      this.isPlaying = !!data.value
      this.$store.commit('setPlayerPlaying', this.isPlaying)
      if (this.isPlaying) {
        this.startPlayInterval()
      } else {
        this.stopPlayInterval()
      }
    },
    onMetadata(data) {
      console.log('onMetadata', JSON.stringify(data))
      this.totalDuration = Number(data.duration.toFixed(2))
      this.currentTime = Number(data.currentTime.toFixed(2))

      // Done loading
      if (data.playerState !== 'BUFFERING' && data.playerState !== 'IDLE') {
        this.isLoading = false
      }

      if (data.playerState === 'ENDED') {
        console.log('[AudioPlayer] Playback ended')
        this.$eventBus.$emit('playback-ended')
      }
      this.isEnded = data.playerState === 'ENDED'

      console.log('received metadata update', data)

      this.timeupdate()
    },
    // A session the native player prepared (a Play from this UI, a queue auto-advance, or a widget/headset/notification
    // Play that restored the last session). The container reconciles Up Next for sessions native prepared on its own.
    onNativePlaybackSession(playbackSession) {
      this.onPlaybackSession(playbackSession)
      this.$eventBus.$emit('native-playback-session', playbackSession)
    },
    // When a playback session is started the native android/ios will send the session
    onPlaybackSession(playbackSession) {
      console.log('onPlaybackSession received', JSON.stringify(playbackSession))
      this.$diag.debug('AudioPlayer', `Playback session received: item ${playbackSession.libraryItemId || playbackSession.localLibraryItem?.id} playMethod=${playbackSession.playMethod} currentTime=${playbackSession.currentTime}`)
      this.playbackSession = playbackSession

      this.isEnded = false
      this.isLoading = true
      this.syncStatus = 0
      this.$store.commit('setPlaybackSession', this.playbackSession)

      // Set track width
      this.$nextTick(() => {
        if (this.titleMarquee) this.titleMarquee.reset()
        this.titleMarquee = new WrappingMarquee(this.$refs.titlewrapper)
        this.titleMarquee.init(this.title)

        this.measureAndUpdateTrackWidth()
      })
    },
    onPlaybackClosed() {
      this.$diag.debug('AudioPlayer', 'Playback closed by native player')
      this.endPlayback()
    },
    onPlaybackFailed(data) {
      console.log('Received onPlaybackFailed evt')
      var errorMessage = data.value || 'Unknown Error'
      this.$diag.error('AudioPlayer', `Playback failed: ${errorMessage}`)
      this.$toast.error(`Playback Failed: ${errorMessage}`)
      this.endPlayback()
    },
    onPlaybackSpeedChanged(data) {
      // Native reports a 32-bit float speed (1.4 arrives as 1.399999976158142)
      const rate = normalizePlaybackRate(data.value)
      if (rate === null) return
      this.currentPlaybackRate = rate
      this.updateTimestamp()
      this.updateCoarseTrack()
    },
    async init() {
      await this.loadPlayerSettings()

      AbsAudioPlayer.addListener('onPlaybackSession', this.onNativePlaybackSession)
      AbsAudioPlayer.addListener('onPlaybackClosed', this.onPlaybackClosed)
      AbsAudioPlayer.addListener('onPlaybackFailed', this.onPlaybackFailed)
      AbsAudioPlayer.addListener('onPlayingUpdate', this.onPlayingUpdate)
      AbsAudioPlayer.addListener('onMetadata', this.onMetadata)
      AbsAudioPlayer.addListener('onProgressSyncFailing', this.showProgressSyncIsFailing)
      AbsAudioPlayer.addListener('onProgressSyncSuccess', this.showProgressSyncSuccess)
      AbsAudioPlayer.addListener('onPlaybackSpeedChanged', this.onPlaybackSpeedChanged)

      await this.reattachNativePlayback()
    },
    // A freshly started UI (e.g. after the task was swiped away while audio kept playing, or after a
    // widget Play restored the session) otherwise never learns about the native session, because
    // onPlaybackSession only fires when a session is prepared. Never starts playback itself.
    async reattachNativePlayback() {
      if (this.$platform !== 'android' || this.playbackSession) return
      try {
        const state = await AbsAudioPlayer.getCurrentPlaybackState()
        if (!state?.playbackSession) this.$diag.debug('AudioPlayer', 'UI started with no native playback session to reattach')
        if (!state?.playbackSession || this.playbackSession) return
        console.log('[AudioPlayer] Reattaching to existing native playback session', state.playbackSession.id)
        this.$diag.debug('AudioPlayer', `UI reattached to existing native session: playing=${!!state.isPlaying} position=${state.currentTime} rate=${state.playbackRate} queue=${state.queue?.items?.length || 0}`)
        // The native player's speed is a 32-bit float; keep the speed the user chose (1.4, not 1.399999976158142)
        const rate = normalizePlaybackRate(state.playbackRate)
        if (rate !== null) this.currentPlaybackRate = rate
        this.onPlaybackSession(state.playbackSession)
        // The player template (and its timestamp/track refs) only renders once a session is set
        await this.$nextTick()
        this.onMetadata({ duration: Number(state.duration) || 0, currentTime: Number(state.currentTime) || 0, playerState: 'READY' })
        this.onPlayingUpdate({ value: !!state.isPlaying })
        if (state.mediaPlayer) this.$store.commit('setMediaPlayer', state.mediaPlayer)
        this.$eventBus.$emit('native-playback-reattached', state)
      } catch (error) {
        console.error('[AudioPlayer] Failed to read native playback state', error)
      }
    },
    async screenOrientationChange() {
      if (this.isRefreshingUI) return
      this.isRefreshingUI = true
      const windowWidth = window.innerWidth
      this.refreshUI()

      // Window width does not always change right away. Wait up to 250ms for a change.
      // iPhone 10 on iOS 16 took between 100 - 200ms to update when going from portrait to landscape
      //   but landscape to portrait was immediate
      for (let i = 0; i < 5; i++) {
        await new Promise((resolve) => setTimeout(resolve, 50))
        if (window.innerWidth !== windowWidth) {
          this.refreshUI()
          break
        }
      }

      // playerContainer width transition is 150ms, remeasure after layout settles
      await new Promise((resolve) => setTimeout(resolve, 200))
      this.refreshUI()

      this.isRefreshingUI = false
    },
    measureAndUpdateTrackWidth() {
      if (!this.$refs.track) {
        console.error('Track not loaded', this.$refs)
        return
      }
      this.trackWidth = this.$refs.track.clientWidth
      this.updateTrack()
      this.updateReadyTrack()
    },
    refreshUI() {
      this.updateScreenSize()
      this.$nextTick(() => {
        this.measureAndUpdateTrackWidth()
      })
    },
    // The window's safe-area insets (CSS px): a hidden probe padded by env(safe-area-inset-*), since script cannot read env()
    readSafeAreaInsets() {
      const probe = document.createElement('div')
      probe.style.cssText = 'position:fixed;visibility:hidden;pointer-events:none;padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left)'
      document.body.appendChild(probe)
      const style = getComputedStyle(probe)
      const insets = { top: parseFloat(style.paddingTop) || 0, right: parseFloat(style.paddingRight) || 0, bottom: parseFloat(style.paddingBottom) || 0, left: parseFloat(style.paddingLeft) || 0 }
      probe.remove()
      return insets
    },
    updateScreenSize() {
      setTimeout(() => {
        if (this.titleMarquee) this.titleMarquee.init(this.title)
      }, 500)

      this.windowHeight = window.innerHeight
      this.windowWidth = window.innerWidth
      this.safeAreaInsets = this.readSafeAreaInsets()
      const coverHeight = this.fullscreenBookCoverWidth * this.bookCoverAspectRatio
      const coverImageWidthCollapsed = 46 / this.bookCoverAspectRatio
      const titleAuthorLeftOffsetCollapsed = 30 + coverImageWidthCollapsed
      const titleAuthorWidthCollapsed = this.windowWidth - 128 - titleAuthorLeftOffsetCollapsed - 10

      document.documentElement.style.setProperty('--cover-image-width', this.fullscreenBookCoverWidth + 'px')
      document.documentElement.style.setProperty('--cover-image-height', coverHeight + 'px')
      document.documentElement.style.setProperty('--cover-image-width-collapsed', coverImageWidthCollapsed + 'px')
      document.documentElement.style.setProperty('--cover-image-height-collapsed', 46 + 'px')
      document.documentElement.style.setProperty('--title-author-left-offset-collapsed', titleAuthorLeftOffsetCollapsed + 'px')
      document.documentElement.style.setProperty('--title-author-width-collapsed', titleAuthorWidthCollapsed + 'px')
    },
    minimizePlayerEvt() {
      this.collapseFullscreen()
    },
    showProgressSyncIsFailing() {
      this.syncStatus = this.$constants.SyncStatus.FAILED
    },
    showProgressSyncSuccess() {
      this.syncStatus = this.$constants.SyncStatus.SUCCESS
    }
  },
  created() {
    // Not reactive on purpose: it only remembers what was last written to the seek row
    this.painter = createPainter()
  },
  mounted() {
    this.updateScreenSize()
    if (screen.orientation) {
      // Not available on ios
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    } else {
      document.addEventListener('orientationchange', this.screenOrientationChange)
    }
    window.addEventListener('resize', this.screenOrientationChange)

    this.$eventBus.$on('minimize-player', this.minimizePlayerEvt)
    document.body.addEventListener('touchstart', this.touchstart, { passive: false })
    document.body.addEventListener('touchend', this.touchend)
    document.body.addEventListener('touchmove', this.touchmove)
    this.$nextTick(this.init)
  },
  beforeDestroy() {
    if (screen.orientation) {
      // Not available on ios
      screen.orientation.removeEventListener('change', this.screenOrientationChange)
    } else {
      document.removeEventListener('orientationchange', this.screenOrientationChange)
    }
    window.removeEventListener('resize', this.screenOrientationChange)

    if (this.playbackSession) {
      console.log('[AudioPlayer] Before destroy closing playback')
      this.closePlayback()
    }

    this.forceCloseDropdownMenu()
    this.$eventBus.$off('minimize-player', this.minimizePlayerEvt)
    document.body.removeEventListener('touchstart', this.touchstart)
    document.body.removeEventListener('touchend', this.touchend)
    document.body.removeEventListener('touchmove', this.touchmove)

    if (AbsAudioPlayer.removeAllListeners) {
      AbsAudioPlayer.removeAllListeners()
    }
    clearInterval(this.playInterval)
  }
}
</script>

<style>
:root {
  --cover-image-width: 0px;
  --cover-image-height: 0px;
  --cover-image-width-collapsed: 46px;
  --cover-image-height-collapsed: 46px;
  --title-author-left-offset-collapsed: 80px;
  --title-author-width-collapsed: 40%;
}

.playerContainer {
  height: 120px;
}
.fullscreen .playerContainer {
  height: 200px;
}
@media (orientation: landscape) {
  .fullscreen .playerContainer {
    width: 50%;
    left: 50%;
  }
}
#playerContent {
  box-shadow: 0px -8px 8px #11111155;
}
.fullscreen #playerContent {
  box-shadow: none;
}

#playerTrack {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: margin;
  bottom: 35px;
  max-width: 100%;
}
/* Seek-row times: the stylesheet (not an inline style) sizes them, so a presentation theme can set their type */
/* Compact collapsed player (theme/playerLayout.js miniLayout; the equipment finish only): the plate loses the empty band
   under the seek strip and every module moves down by the same trim, so their sizes and spacing are unchanged */
#streamContainer.mini-compact .playerContainer {
  height: calc(120px - var(--mini-trim));
}
#streamContainer.mini-compact #playerTrack {
  bottom: calc(35px - var(--mini-trim));
}
#streamContainer.mini-compact .cover-wrapper {
  bottom: calc(68px - var(--mini-trim));
}
#streamContainer.mini-compact .title-author-texts {
  bottom: calc(76px - var(--mini-trim));
}
#streamContainer.mini-compact #playerControls {
  bottom: calc(70px - var(--mini-trim));
}
#playerTrack .track-time {
  font-size: 0.8rem;
}
.fullscreen #playerTrack {
  bottom: unset;
}

.cover-wrapper {
  bottom: 68px;
  left: 24px;
  height: var(--cover-image-height-collapsed);
  width: var(--cover-image-width-collapsed);
  transition: all 0.25s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: left, bottom, width, height;
  transform-origin: left bottom;
  border-radius: 3px;
  overflow: hidden;
}
@media (orientation: landscape) {
  .fullscreen .cover-wrapper {
    left: 25px !important;
    top: 50px;
    width: calc(50% - 25px) !important;
    height: calc(100% - 85px) !important;
  }
  .fullscreen .cover-wrapper > div > div {
    width: 100% !important;
    height: 100% !important;
    max-width: 100% !important;
    max-height: 100% !important;
  }
  .fullscreen .cover-wrapper > div > div > div {
    background-color: transparent;
  }
  .fullscreen .cover-wrapper img {
    object-fit: contain;
  }
}

.total-track {
  bottom: 215px;
  left: 0;
  right: 0;
}
@media (orientation: landscape) {
  .fullscreen .total-track {
    left: 50%;
    width: 50%;
  }
}

@media (orientation: landscape) {
  .fullscreen .title-author-texts {
    left: 50% !important;
    width: 50% !important;
  }
}
.title-author-texts {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: left, bottom, width, height;
  transform-origin: left bottom;

  width: var(--title-author-width-collapsed);
  bottom: 76px;
  left: var(--title-author-left-offset-collapsed);
  text-align: left;
}
.title-author-texts .title-text {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;
  font-size: 0.85rem;
  line-height: 1.5;
}
@media (orientation: landscape) {
  .fullscreen .title-author-texts .title-text {
    font-size: larger !important;
  }
}
.title-author-texts .author-text {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;
  font-size: 0.75rem;
  line-height: 1.2;
}
@media (orientation: landscape) {
  .fullscreen .title-author-texts .author-text {
    font-size: inherit !important;
  }
}

.fullscreen .title-author-texts {
  bottom: calc(50% - var(--cover-image-height) / 2 + 50px);
  width: 80%;
  left: 10%;
  text-align: center;
  padding-bottom: calc(((260px - var(--cover-image-height)) / 260) * 40);
  pointer-events: auto;
}
.fullscreen .title-author-texts .title-text {
  font-size: clamp(0.8rem, calc(var(--cover-image-height) / 260 * 20), 1.3rem);
}
.fullscreen .title-author-texts .author-text {
  font-size: clamp(0.6rem, calc(var(--cover-image-height) / 260 * 16), 1rem);
}

#playerControls {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: width, bottom;
  width: 128px;
  padding-right: 24px;
  bottom: 70px;
}
#playerControls .jump-icon {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;

  margin: 0px 0px;
  font-size: 1.6rem;
}
#playerControls .jump-label {
  margin-top: 2px;
}
#playerControls .play-btn {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: padding, margin, height, width, min-width, min-height;

  height: 40px;
  width: 40px;
  min-width: 40px;
  min-height: 40px;
  margin: 0px 7px;
}
#playerControls .play-btn .material-symbols {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;

  font-size: 1.5rem;
}

.fullscreen .cover-wrapper {
  margin: 0 auto;
  height: var(--cover-image-height);
  width: var(--cover-image-width);
  left: calc(50% - (calc(var(--cover-image-width)) / 2));
  bottom: calc(50% + 120px - (calc(var(--cover-image-height)) / 2));
  border-radius: 16px;
  overflow: hidden;
}

.fullscreen #playerControls {
  width: 100%;
  padding-left: 24px;
  padding-right: 24px;
  bottom: 78px;
  left: 0;
}
/* Top padding as tall as the duration below (10px at leading-tight, 2px gap) centers the glyph on Play, like the chapter
   keys, while the duration keeps its place in the layout */
.fullscreen #playerControls .jump-icon {
  font-size: 2.4rem;
  padding-top: 14.5px;
}
.fullscreen #playerControls .jump-icon > .material-symbols {
  font-size: 2rem;
}
.fullscreen #playerControls .jump-label {
  text-align: center;
}
.fullscreen #playerControls .next-icon {
  font-size: 2rem;
}
.fullscreen #playerControls .play-btn {
  height: 65px;
  width: 65px;
  min-width: 65px;
  min-height: 65px;
}
.fullscreen #playerControls .play-btn .material-symbols {
  font-size: 2.1rem;
}
/* With seven keys a narrow screen at a large font scale would push chapter end off screen: keys shrink instead, and there
   the glyphs may overlap */
.fullscreen #playerControls .long-jumps .player-key {
  flex: 0 1 auto;
  min-width: 0;
}

/*
 * Faceplate layout (theme/playerLayout.js, Phase 6B). Applies only through the hook classes and --faceplate-*
 * variables the layout projection puts on #streamContainer while the full player is shown; non-equipment themes
 * and the mini-player get neither, so none of this applies to them. Paint (plates, bezel, colors) is the equipment
 * recipe's (theme/presets.js); this is geometry and type only.
 */
/* Decorative plates: never a touch target */
.player-plate {
  position: absolute;
  pointer-events: none;
}
.player-plate-top {
  left: 8px;
  right: 8px;
  top: 12px;
  height: var(--faceplate-plate-height);
}
/* The console plate surrounds the transport and utility banks, and on wide screens follows their 414px column */
.player-plate-console {
  z-index: -1;
  left: max(6px, calc(50% - 222px));
  right: max(6px, calc(50% - 222px));
  bottom: 6px;
  top: var(--faceplate-console-top);
}
.faceplate-flat .player-plate-console {
  top: 2px;
}
/* Behind the seek row's recessed well (its content box): 6px of plate around it */
.player-plate-seek {
  z-index: -1;
  left: 18px;
  right: 18px;
  top: -2px;
  bottom: -6px;
}

/* Stacked modules: the deck holds the seek/time module above the unchanged console; the artwork and readout are
   bottom-anchored like the original layout (so expand/collapse still animates), positioned from the layout's budget */
#streamContainer.fullscreen.faceplate .playerContainer {
  height: var(--faceplate-deck);
}
#streamContainer.fullscreen.faceplate .cover-wrapper {
  bottom: calc(100% - var(--faceplate-cover-top) - var(--cover-image-height));
}
/* The cover is sized by the layout's width, so the cover component and the bay always agree. Its own min-width
   (its width prop) must never be able to hold it wider than the bay, which would crop instead of scale */
#streamContainer.fullscreen.faceplate .cover-wrapper > div > div {
  width: 100% !important;
  height: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
  max-height: 100% !important;
}
/* Landscape artwork (theme/playerLayout.js landscapeArtwork; equipment finish only): the box is the artwork's own size,
   so its frame hugs the cover; bottom-anchored on the player's bottom edge and centered in the left column. Replaces the
   previous fixed half-screen box (top 50px), whose frame enclosed letterbox space and crossed the top chrome */
#streamContainer.fullscreen.faceplate-landscape .cover-wrapper {
  top: auto;
  left: var(--landscape-art-left) !important;
  bottom: var(--landscape-art-bottom);
  width: var(--landscape-art-width) !important;
  height: var(--landscape-art-height) !important;
}
#streamContainer.fullscreen.faceplate-landscape .cover-wrapper > div > div {
  width: 100% !important;
  height: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
  max-height: 100% !important;
}
#streamContainer.fullscreen.faceplate-landscape .cover-wrapper img {
  object-fit: contain;
}
/* Metadata band: a fixed height that holds the title and author at font scale 1.3, text centered in it */
#streamContainer.fullscreen.faceplate .title-author-texts {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: var(--faceplate-band);
  bottom: var(--faceplate-readout-bottom);
  left: 16px;
  width: calc(100% - 32px);
  padding: 6px 12px;
}
#streamContainer.fullscreen.faceplate .title-author-texts .title-text {
  line-height: 1.25;
}
#streamContainer.fullscreen.faceplate #playerTrack {
  padding-top: 4px;
}
#streamContainer.fullscreen.faceplate #playerTrack > div.flex {
  padding: 5px 10px 2px;
}
#streamContainer.fullscreen.faceplate #playerTrack > div.relative {
  margin-bottom: 8px;
}

/* Readout type: condensed system face (no font asset), tabular figures; metadata stays neutral in color */
.faceplate-type .title-author-texts .title-text {
  font-family: sans-serif-condensed, sans-serif;
  letter-spacing: 0.01em;
}
.faceplate-type .title-author-texts .author-text {
  font-family: sans-serif-condensed, sans-serif;
  letter-spacing: 0.02em;
}
.faceplate-type #playerTrack p.font-mono {
  font-family: sans-serif-condensed, sans-serif;
  font-size: var(--faceplate-times) !important;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}
.faceplate-type .total-track p.font-mono {
  font-family: sans-serif-condensed, sans-serif;
  font-variant-numeric: tabular-nums;
}
.faceplate-type .speed-readout,
.faceplate-type .sleep-readout {
  font-family: sans-serif-condensed, sans-serif;
  letter-spacing: 0.02em;
}
.faceplate-type .playback-method {
  font-family: sans-serif-condensed, sans-serif;
  font-size: 11px !important;
}
/* Previous geometry (two rails, landscape): the seek row keeps its 200px deck, so its times sit on a tight line */
.faceplate-flat #playerTrack > div.flex {
  padding: 0 10px;
}
.faceplate-flat #playerTrack p.font-mono {
  line-height: 1;
}

/* Chapter + book progress (two rails, previous geometry): one dual-track instrument directly above the console. The
   chapter rail (the seek row) and the book rail are two equal sections stacked with no gap, bottom-anchored from the
   layout's offsets, inside one plate (the seek plate, extended over the book section). Progress, seek and the rails'
   behavior are unchanged; this only places and sizes the two existing rows */
#streamContainer.fullscreen.faceplate-dual #playerTrack {
  top: auto;
  bottom: var(--faceplate-dual-main-bottom);
  height: var(--faceplate-dual-section);
  padding-top: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
#streamContainer.fullscreen.faceplate-dual .total-track {
  bottom: var(--faceplate-dual-book-bottom);
  height: var(--faceplate-dual-section);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.faceplate-dual #playerTrack > div.flex,
.faceplate-dual .total-track > div.flex {
  padding: 0 10px;
}
.faceplate-dual #playerTrack > div.relative,
.faceplate-dual .total-track > div.w-full {
  margin-top: 2px;
}
/* The book rail matches the chapter rail (6px) */
.faceplate-dual .total-track div.relative {
  height: 6px;
}
.faceplate-dual #playerTrack p.font-mono,
.faceplate-dual .total-track p.font-mono {
  line-height: 1;
}
.faceplate-dual .total-track p.font-mono {
  font-size: var(--faceplate-times) !important;
}
.faceplate-dual .player-plate-seek {
  top: calc(-6px - var(--faceplate-dual-section));
  bottom: -6px;
}
</style>
