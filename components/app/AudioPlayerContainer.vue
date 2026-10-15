<template>
  <div>
    <app-audio-player ref="audioPlayer" :bookmarks="bookmarks" :sleep-timer-running="isSleepTimerRunning" :sleep-time-remaining="sleepTimeRemaining" :serverLibraryItemId="serverLibraryItemId" :bookmarkable="!!bookmarkLibraryItemId" @selectPlaybackSpeed="showPlaybackSpeedModal = true" @updateTime="(t) => (currentTime = t)" @showSleepTimer="showSleepTimer" @showBookmarks="showBookmarks" @showQueue="showQueueModal = true" />

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-rate.sync="playbackSpeed" @update:playbackRate="updatePlaybackSpeed" @change="changePlaybackSpeed" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeRemaining" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" :is-auto="isAutoSleepTimer" @change="selectSleepTimeout" @cancel="cancelSleepTimer" @increase="increaseSleepTimer" @decrease="decreaseSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :bookmarks="bookmarks" :current-time="currentTime" :library-item-id="bookmarkLibraryItemId" :server-config-id="bookmarkServerConfigId" :playback-rate="playbackSpeed" @select="selectBookmark" />
    <modals-queue-modal v-model="showQueueModal" :queue="playbackQueue" @reorder="reorderQueue" @remove="removeFromQueue" />
  </div>
</template>

<script>
import { AbsAudioPlayer, AbsLogger } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
import CellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'
import { nativeQueueItems, nativeQueueMatches, queueDisplayFromNative, queueItemKeyOf, resolvePlaybackQueue, resolveQueueSourceAppendItems, sessionToQueueItem } from '@/utils/playbackQueue'
import { refocusDisplayTime, refocusServerAction, readRefocusHandled, writeRefocusHandled } from '@/utils/livePosition'

export default {
  data() {
    return {
      isReady: false,
      settingsLoaded: false,
      audioPlayerReady: false,
      preparingPlayback: false,
      stream: null,
      download: null,
      showPlaybackSpeedModal: false,
      showBookmarksModal: false,
      showSleepTimerModal: false,
      showQueueModal: false,
      playbackSpeed: 1,
      currentTime: 0,
      isSleepTimerRunning: false,
      sleepTimerEndTime: 0,
      sleepTimeRemaining: 0,
      isAutoSleepTimer: false,
      onLocalMediaProgressUpdateListener: null,
      onSleepTimerEndedListener: null,
      onSleepTimerSetListener: null,
      onMediaPlayerChangedListener: null,
      onAwaitingServerListener: null,
      sleepInterval: null,
      currentEndOfChapterTime: 0,
      serverLibraryItemId: null,
      serverEpisodeId: null,
      // The streamed session's server progress the refocus check already dealt with (refocusServerAction)
      refocusServerHandled: null
    }
  },
  mixins: [CellularPermissionHelpers],
  computed: {
    /**
     * The server item the bookmarks belong to. Unlike serverLibraryItemId (cast, opening the item: the active server
     * only), a downloaded copy of another saved server has its bookmarks too, kept for that server.
     */
    bookmarkLibraryItemId() {
      if (this.serverLibraryItemId) return this.serverLibraryItemId
      const session = this.currentPlaybackSession
      if (!session?.localLibraryItem?.serverConnectionConfigId) return null
      return session.libraryItemId || session.localLibraryItem.libraryItemId || null
    },
    bookmarks() {
      if (!this.bookmarkLibraryItemId) return []
      // The store holds the active server's bookmarks; the modal reads another server's copy bookmarks itself
      const server = this.bookmarkServerConfigId
      if (server && server !== this.$store.state.activeServerConfigId) return []
      return this.$store.getters['user/getUserBookmarksForItem'](this.bookmarkLibraryItemId)
    },
    bookmarkServerConfigId() {
      // A downloaded copy keeps its own server: its bookmarks are queued for that server, not the active one
      return this.currentPlaybackSession?.localLibraryItem?.serverConnectionConfigId || null
    },
    isIos() {
      return this.$platform === 'ios'
    },
    currentPlaybackSession() {
      return this.$store.state.currentPlaybackSession
    },
    playbackQueue() {
      return this.$store.state.playbackQueue
    }
  },
  watch: {
    currentPlaybackSession() {
      // Sessions arrive on native advancement, including after background playback.
      this.onPlaybackEnded()
      this.setSessionServerIds()
    },
    '$store.state.activeServerConfigId'() {
      // Known late on a cold start (or switched): a downloaded copy playing may now be, or no longer be, the active
      // server's. A stream keeps the ids it started with
      if (this.currentPlaybackSession?.localLibraryItem) this.setSessionServerIds()
    }
  },
  methods: {
    /** The playing session's server ids; none for a copy of another saved server, which mean nothing to the active one. */
    setSessionServerIds() {
      const session = this.currentPlaybackSession
      const copyServer = session?.localLibraryItem?.serverConnectionConfigId
      const foreignCopy = !!session?.localLibraryItem && copyServer !== this.$store.state.activeServerConfigId
      this.serverLibraryItemId = foreignCopy ? null : session?.libraryItemId || null
      this.serverEpisodeId = foreignCopy ? null : session?.episodeId || null
    },
    /**
     * The server ids of what started playing, both from one source. A downloaded copy of the active server knows its
     * own (the session's ids, from LocalLibraryItem.libraryItemId) when the page could not tell them, e.g. offline on
     * a cold start. A copy of another saved server gets none: its ids mean nothing to the active server.
     */
    setServerIds(libraryItemId, episodeId, serverLibraryItemId, serverEpisodeId) {
      let ids = [null, null]
      if (!libraryItemId.startsWith('local')) {
        ids = [libraryItemId, episodeId && !episodeId.startsWith('local') ? episodeId : serverEpisodeId]
      } else if (serverLibraryItemId) {
        ids = [serverLibraryItemId, serverEpisodeId]
      } else {
        const session = this.currentPlaybackSession
        const copy = session?.localLibraryItem
        const ownCopy = copy?.id === libraryItemId && !!copy.serverConnectionConfigId && copy.serverConnectionConfigId === this.$store.state.activeServerConfigId
        if (ownCopy) ids = [session.libraryItemId || null, session.episodeId || null]
      }
      this.serverLibraryItemId = ids[0]
      this.serverEpisodeId = ids[1] || null
    },
    showBookmarks() {
      this.showBookmarksModal = true
    },
    selectBookmark(bookmark) {
      this.showBookmarksModal = false
      if (!bookmark || isNaN(bookmark.time)) return
      const bookmarkTime = Number(bookmark.time)
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.seek(bookmarkTime)
      }
    },
    onSleepTimerEnded({ value: currentPosition }) {
      this.isSleepTimerRunning = false
      if (currentPosition) {
        console.log('Sleep Timer Ended Current Position: ' + currentPosition)
      }
    },
    onSleepTimerSet(payload) {
      const { value: sleepTimeRemaining, isAuto } = payload
      console.log('SLEEP TIMER SET', JSON.stringify(payload))
      if (sleepTimeRemaining === 0) {
        console.log('Sleep timer canceled')
        this.isSleepTimerRunning = false
      } else {
        this.isSleepTimerRunning = true
      }

      this.isAutoSleepTimer = !!isAuto
      this.sleepTimeRemaining = sleepTimeRemaining
    },
    showSleepTimer() {
      if (this.$refs.audioPlayer && this.$refs.audioPlayer.currentChapter) {
        this.currentEndOfChapterTime = Math.floor(this.$refs.audioPlayer.currentChapter.end)
      } else {
        this.currentEndOfChapterTime = 0
      }
      this.showSleepTimerModal = true
    },
    async selectSleepTimeout({ time, isChapterTime }) {
      console.log('Setting sleep timer', time, isChapterTime)
      var res = await AbsAudioPlayer.setSleepTimer({ time: String(time), isChapterTime })
      if (!res.success) {
        return this.$toast.error('Sleep timer did not set, invalid time')
      }
    },
    increaseSleepTimer() {
      // Default time to increase = 5 min
      AbsAudioPlayer.increaseSleepTime({ time: '300000' })
    },
    decreaseSleepTimer() {
      AbsAudioPlayer.decreaseSleepTime({ time: '300000' })
    },
    async cancelSleepTimer() {
      console.log('Canceling sleep timer')
      await AbsAudioPlayer.cancelSleepTimer()
    },
    streamClosed() {
      console.log('Stream Closed')
    },
    streamProgress(data) {
      if (!data.numSegments) return
      const chunks = data.chunks
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.setChunksReady(chunks, data.numSegments)
      }
    },
    streamReady() {
      console.log('[StreamContainer] Stream Ready')
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.setStreamReady()
      }
    },
    streamReset({ streamId, startTime }) {
      console.log('received stream reset', streamId, startTime)
      if (this.$refs.audioPlayer) {
        if (this.stream && this.stream.id === streamId) {
          this.$refs.audioPlayer.resetStream(startTime)
        }
      }
    },
    updatePlaybackSpeed(speed) {
      if (this.$refs.audioPlayer) {
        console.log(`[AudioPlayerContainer] Update Playback Speed: ${speed}`)
        this.$refs.audioPlayer.setPlaybackSpeed(speed)
      }
    },
    changePlaybackSpeed(speed) {
      console.log(`[AudioPlayerContainer] Change Playback Speed: ${speed}`)
      this.$store.dispatch('user/updateUserSettings', { playbackRate: speed })
    },
    settingsUpdated(settings) {
      console.log(`[AudioPlayerContainer] Settings Update | PlaybackRate: ${settings.playbackRate}`)
      this.playbackSpeed = settings.playbackRate
      if (this.$refs.audioPlayer && this.$refs.audioPlayer.currentPlaybackRate !== settings.playbackRate) {
        console.log(`[AudioPlayerContainer] PlaybackRate Updated: ${this.playbackSpeed}`)
        this.$refs.audioPlayer.setPlaybackSpeed(this.playbackSpeed)
      }

      // Settings have been loaded (at least once, so it's safe to kickoff onReady)
      if (!this.settingsLoaded) {
        this.settingsLoaded = true
        this.notifyOnReady()
      }
    },
    closeStreamOnly() {
      // If user logs out or disconnects from server and not playing local
      if (this.$refs.audioPlayer && !this.$refs.audioPlayer.isLocalPlayMethod) {
        this.$refs.audioPlayer.closePlayback()
      }
    },
    castLocalItem() {
      if (!this.serverLibraryItemId) {
        this.$toast.error(`Cannot cast locally downloaded media`)
      } else {
        // Change to server library item
        this.playServerLibraryItemAndCast(this.serverLibraryItemId, this.serverEpisodeId)
      }
    },
    async playServerLibraryItemAndCast(libraryItemId, episodeId) {
      this.$store.commit('clearPlaybackQueue')
      if (this.$platform === 'android') await AbsAudioPlayer.clearPlaylistQueue()
      var playbackRate = 1
      if (this.$refs.audioPlayer) {
        playbackRate = this.$refs.audioPlayer.currentPlaybackRate || 1
      }
      // A cast device cannot play a file on the phone: stream even when the book is downloaded
      AbsAudioPlayer.prepareLibraryItem({ libraryItemId, episodeId, playWhenReady: false, playbackRate, forceStream: true })
        .then((data) => {
          if (data.error) {
            const errorMsg = data.error || 'Failed to play'
            this.$toast.error(errorMsg)
          } else {
            console.log('Library item play response', JSON.stringify(data))
            AbsAudioPlayer.requestSession()
          }
        })
        .catch((error) => {
          console.error('Failed', error)
          this.$toast.error('Failed to play')
        })
    },
    // Up Next display for a queue the native player holds without this UI having set it (queueDisplayFromNative):
    // the saved display copy is reused only if it still describes exactly the native queue (it may have advanced or
    // been restored in the background). `generation` is the store's queue generation when the native state was
    // requested: any queue set or cleared since then (a newer Play, Close, an edit) wins over this older answer.
    async restoreQueueDisplay(native, session, generation, origin) {
      if (this.$store.state.playbackQueue || this.preparingPlayback || !native?.items?.length) return
      const saved = await this.$localStore.getPlaybackQueue()
      if (this.$store.state.playbackQueue || this.preparingPlayback || this.$store.state.playbackQueueGeneration !== generation) return
      const { queue, reason } = queueDisplayFromNative(saved, native, session)
      if (!queue) {
        console.log(`[AudioPlayerContainer] Native queue not shown in Up Next (${origin}): ${reason}`)
        this.$diag.debug('AudioPlayerContainer', `Up Next display not restored (${origin}): ${reason}`)
        return
      }
      console.log('[AudioPlayerContainer] Restored queue display from native queue', queue.currentIndex)
      this.$diag.debug('AudioPlayerContainer', `Up Next display restored from native queue (${origin}, ${native.items.length} items, index ${queue.currentIndex})`)
      this.$store.commit('setPlaybackQueue', queue)
    },
    // The UI started while a native session already existed (AudioPlayer.reattachNativePlayback)
    async nativePlaybackReattached(state) {
      await this.restoreQueueDisplay(state?.queue, state?.playbackSession || null, this.$store.state.playbackQueueGeneration, 'reattach')
    },
    // The native player prepared a session while this UI was open. A Play from this UI sets or clears the queue first,
    // so this only matters for sessions native prepared on its own: a widget, headset or notification Play that
    // restored the last session and its queue after the process had been stopped (e.g. after inactivity or an update).
    async nativePlaybackSessionPrepared(session) {
      if (this.$platform !== 'android' || this.$store.state.playbackQueue || this.preparingPlayback) return
      const generation = this.$store.state.playbackQueueGeneration
      let native
      try {
        native = await AbsAudioPlayer.getPlaylistQueue()
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to read native queue', error)
        return
      }
      // Another session arrived while the queue was read: that one's own event decides
      if (session?.id && this.$store.state.currentPlaybackSession?.id && this.$store.state.currentPlaybackSession.id !== session.id) return
      await this.restoreQueueDisplay(native, session, generation, 'session')
    },
    async onPlaybackEnded() {
      const queue = this.$store.state.playbackQueue
      if (!queue || this.$platform !== 'android') return
      try {
        const native = await AbsAudioPlayer.getPlaylistQueue()
        // A newer play request must not be overwritten by an old bridge response.
        if (this.$store.state.playbackQueue !== queue) return
        if (!native.items.length) this.$store.commit('clearPlaybackQueue')
        else if (nativeQueueMatches(native.items, queue.items)) {
          this.$store.commit('setPlaybackQueue', { ...queue, currentIndex: native.currentIndex })
        }
      } catch (error) {
        console.error('Failed to read playback queue', error)
      }
    },
    // Single choke point for reorder/remove/append so native is never written to with a stale index.
    async mutateQueue(newItems) {
      const queue = this.$store.state.playbackQueue
      if (!queue || this.$platform !== 'android') return
      try {
        const native = await AbsAudioPlayer.getPlaylistQueue()
        // A native auto-advance raced this edit - resync instead of writing over it.
        if (native.currentIndex !== queue.currentIndex || native.items.length <= queue.currentIndex) {
          await this.onPlaybackEnded()
          this.$toast.error('Queue changed, try again')
          return
        }
        await AbsAudioPlayer.setPlaylistQueue({ items: nativeQueueItems(newItems, this.nativeQueueServerConfigId()), currentIndex: queue.currentIndex })
        this.$store.commit('setPlaybackQueue', { ...queue, items: newItems })
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to mutate playback queue', error)
        this.$toast.error('Failed to update queue')
      }
    },
    reorderQueue(newUpcomingOrder) {
      const queue = this.$store.state.playbackQueue
      if (!queue) return
      this.mutateQueue(queue.items.slice(0, queue.currentIndex + 1).concat(newUpcomingOrder))
    },
    removeFromQueue(item) {
      const queue = this.$store.state.playbackQueue
      if (!queue) return
      const key = queueItemKeyOf(item)
      const newItems = queue.items.filter((queueItem, index) => index <= queue.currentIndex || queueItemKeyOf(queueItem) !== key)
      this.mutateQueue(newItems)
    },
    isAlreadyQueued(items, item) {
      const key = queueItemKeyOf(item)
      return items.some((queueItem) => queueItemKeyOf(queueItem) === key)
    },
    async addSingleItemToQueue(item) {
      if (!this.currentPlaybackSession) {
        // Nothing playing - a queue with nothing playing isn't a meaningful state, just play it.
        this.$eventBus.$emit('play-item', item)
        return
      }
      const queue = this.$store.state.playbackQueue
      if (!queue) {
        const newQueue = { sourceType: 'adhoc', sourceId: null, items: [sessionToQueueItem(this.currentPlaybackSession), item], currentIndex: 0 }
        if (this.$platform === 'android') await AbsAudioPlayer.setPlaylistQueue({ items: nativeQueueItems(newQueue.items, this.nativeQueueServerConfigId()), currentIndex: 0 })
        this.$store.commit('setPlaybackQueue', newQueue)
        return
      }
      if (this.isAlreadyQueued(queue.items, item)) {
        this.$toast.info('Already in queue')
        return
      }
      this.mutateQueue([...queue.items, item])
    },
    async addQueueSourceToQueue(source) {
      if (!this.currentPlaybackSession) {
        this.$eventBus.$emit('play-item', { queueSource: source })
        return
      }
      const queue = this.$store.state.playbackQueue
      const existingItems = queue ? queue.items : [sessionToQueueItem(this.currentPlaybackSession)]
      try {
        const appendItems = await resolveQueueSourceAppendItems(this, source, existingItems)
        if (!appendItems.length) {
          this.$toast.info('Nothing new to add to the queue')
          return
        }
        if (!queue) {
          const newQueue = { sourceType: 'adhoc', sourceId: null, items: [...existingItems, ...appendItems], currentIndex: 0 }
          if (this.$platform === 'android') await AbsAudioPlayer.setPlaylistQueue({ items: nativeQueueItems(newQueue.items, this.nativeQueueServerConfigId()), currentIndex: 0 })
          this.$store.commit('setPlaybackQueue', newQueue)
          return
        }
        this.mutateQueue([...queue.items, ...appendItems])
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to add source to queue', error)
        this.$toast.error(error.message || 'Failed to add to queue')
      }
    },
    async playLibraryItem(payload) {
      if (this.preparingPlayback) return
      this.preparingPlayback = true
      try {
        // Explicit source context is required, even if the item also occurs in an old queue.
        this.$store.commit('clearPlaybackQueue')
        if (this.$platform === 'android') await AbsAudioPlayer.clearPlaylistQueue()
        const resolved = await resolvePlaybackQueue(this, payload)
        await this.startLibraryItem(resolved.payload, resolved.queue)
      } catch (error) {
        this.$store.commit('clearPlaybackQueue')
        if (this.$platform === 'android') await AbsAudioPlayer.clearPlaylistQueue().catch((clearError) => console.error('Failed to clear playback queue', clearError))
        this.$store.commit('setPlayerDoneStartingPlayback')
        this.$toast.error(error.message || 'Failed to prepare playback queue')
      } finally {
        this.preparingPlayback = false
      }
    },
    async startLibraryItem(payload, queue) {
      await AbsLogger.info({ tag: 'AudioPlayerContainer', message: `playLibraryItem: Received play request for library item ${payload.libraryItemId} ${payload.episodeId ? `episode ${payload.episodeId}` : ''}` })
      const libraryItemId = payload.libraryItemId
      const episodeId = payload.episodeId
      const startTime = payload.startTime
      const startWhenReady = !payload.paused

      // Native plays a downloaded copy of a server id too; only a stream needs the cellular permission
      const source = await this.resolvePlaybackSource(libraryItemId, episodeId, payload.ino)
      if (source === 'stream') {
        const hasPermission = await this.checkCellularPermission('streaming')
        if (!hasPermission) {
          this.$store.commit('setPlayerDoneStartingPlayback')
          return
        }
      }

      // When playing local library item and can also play this item from the server
      //   then store the server library item id so it can be used if a cast is made
      const serverLibraryItemId = payload.serverLibraryItemId || null
      const serverEpisodeId = payload.serverEpisodeId || null

      if (source === 'local' && this.$store.state.isCasting) {
        const { value } = await Dialog.confirm({
          title: 'Warning',
          message: `Cannot cast downloaded media items. Confirm to close cast and play on your device.`
        })
        if (!value) {
          this.$store.commit('setPlayerDoneStartingPlayback')
          return
        }
      }

      if (queue) {
        await AbsAudioPlayer.setPlaylistQueue({ items: nativeQueueItems(queue.items, this.nativeQueueServerConfigId()), currentIndex: queue.currentIndex })
        this.$store.commit('setPlaybackQueue', queue)
      }

      // if already playing this item then jump to start time
      if (this.$store.getters['getIsMediaStreaming'](libraryItemId, episodeId)) {
        console.log('Already streaming item', startTime)
        if (startTime !== undefined && startTime !== null) {
          // seek to start time
          AbsAudioPlayer.seek({ value: startTime })
        } else if (this.$refs.audioPlayer) {
          this.$refs.audioPlayer.play()
        }
        this.$store.commit('setPlayerDoneStartingPlayback')
        return
      }

      this.serverLibraryItemId = null
      this.serverEpisodeId = null

      let playbackRate = 1
      if (this.$refs.audioPlayer) {
        playbackRate = this.$refs.audioPlayer.currentPlaybackRate || 1
      }

      console.log('Called playLibraryItem', libraryItemId)
      const preparePayload = { libraryItemId, episodeId, playWhenReady: startWhenReady, playbackRate }
      if (payload.ino) preparePayload.ino = payload.ino
      if (startTime !== undefined && startTime !== null) preparePayload.startTime = startTime
      return AbsAudioPlayer.prepareLibraryItem(preparePayload)
        .then((data) => {
          if (data.error) {
            this.$store.commit('clearPlaybackQueue')
            if (this.$platform === 'android') AbsAudioPlayer.clearPlaylistQueue()
            const errorMsg = data.error || 'Failed to play'
            this.$toast.error(errorMsg)
          } else {
            console.log('Library item play response', JSON.stringify(data))
            this.setServerIds(libraryItemId, episodeId, serverLibraryItemId, serverEpisodeId)
          }
        })
        .catch((error) => {
          this.$store.commit('clearPlaybackQueue')
          if (this.$platform === 'android') AbsAudioPlayer.clearPlaylistQueue()
          console.error('Failed', error)
          this.$toast.error('Failed to play')
        })
        .finally(() => {
          this.$store.commit('setPlayerDoneStartingPlayback')
        })
    },
    // The server a queue item streams from when it names none (no downloaded copy)
    nativeQueueServerConfigId() {
      return this.$store.state.activeServerConfigId || this.$store.getters['user/getServerConnectionConfigId'] || null
    },
    /** 'local', 'stream' or 'broken' as native would play it; without an answer the id decides, as before. */
    async resolvePlaybackSource(libraryItemId, episodeId, ino) {
      try {
        const { source } = await AbsAudioPlayer.resolvePlaybackSource({ libraryItemId, episodeId: episodeId || null, ino: ino || null })
        if (source) return source
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to resolve the playback source', error)
      }
      return libraryItemId.startsWith('local') ? 'local' : 'stream'
    },
    pauseItem() {
      if (this.$refs.audioPlayer && this.$refs.audioPlayer.isPlaying) {
        this.$refs.audioPlayer.pause()
      }
    },
    onLocalMediaProgressUpdate(localMediaProgress) {
      console.log('Got local media progress update', localMediaProgress.progress, JSON.stringify(localMediaProgress))
      this.$store.commit('globals/updateLocalMediaProgress', localMediaProgress)
    },
    // Native paused a stream whose server went away and resumes it itself: a label, not an error, and the player stays
    onAwaitingServer(data) {
      this.$store.commit('setPlayerAwaitingServer', !!data?.awaiting)
    },
    onMediaPlayerChanged(data) {
      this.$store.commit('setMediaPlayer', data.value)
    },
    onReady() {
      // The UI is reporting elsewhere we are ready
      this.isReady = true
      this.notifyOnReady()
    },
    notifyOnReady() {
      // TODO: was used on iOS to open last played media. May be removed
      if (!this.isIos) return

      // If settings aren't loaded yet, native player will receive incorrect settings
      console.log('Notify on ready... settingsLoaded:', this.settingsLoaded, 'isReady:', this.isReady)
      if (this.settingsLoaded && this.isReady && this.$store.state.isFirstAudioLoad) {
        this.$store.commit('setIsFirstAudioLoad', false) // Only run this once on app launch
        AbsAudioPlayer.onReady()
      }
    },
    playbackTimeUpdate(currentTime) {
      this.$refs.audioPlayer?.seek(currentTime)
    },
    /**
     * Fetch the current user's media progress from the server for a given library item / episode.
     * Returns the server media progress object, or null if the request fails, times out, or the
     * response doesn't match the requested library item.
     *
     * The audio player's loading state is shown while the request is in flight so the user
     * doesn't tap play before we have a chance to update the timestamps. The request timeout
     * is 7 seconds so a slow/unresponsive server doesn't block the user for long.
     */
    async getServerMediaProgressForCurrentSession() {
      if (!this.$store.state.user.user || !this.$store.state.networkConnected) return null
      const libraryItemId = this.currentPlaybackSession?.libraryItemId
      const episodeId = this.currentPlaybackSession?.episodeId
      if (!libraryItemId) return null

      if (this.$refs.audioPlayer?.isCheckingServerProgress) {
        console.log('[AudioPlayerContainer] getServerMediaProgressForCurrentSession: already checking server progress')
        return null
      }

      const url = episodeId ? `/api/me/progress/${libraryItemId}/${episodeId}` : `/api/me/progress/${libraryItemId}`

      this.$refs.audioPlayer?.setIsCheckingServerProgress(true)
      try {
        const data = await this.$nativeHttp.get(url, { connectTimeout: 7000, readTimeout: 7000 })
        if (!data || data.libraryItemId !== libraryItemId) return null
        return data
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to get server media progress', error)
        return null
      } finally {
        this.$refs.audioPlayer?.setIsCheckingServerProgress(false)
      }
    },
    // The native player's loaded session and live position (AbsAudioPlayer.getCurrentPlaybackState), or null when the
    // platform has none or the call fails, so callers fall back to their previous behaviour
    async getNativePlaybackState() {
      try {
        return (await AbsAudioPlayer.getCurrentPlaybackState()) || null
      } catch (error) {
        return null
      }
    },
    getLocalMediaProgressForCurrentSession() {
      if (!this.currentPlaybackSession) return null
      return this.$store.getters['globals/getLocalMediaProgressById'](this.currentPlaybackSession.localLibraryItem?.id, this.currentPlaybackSession.localEpisodeId)
    },
    /**
     * The server's progress of the downloaded item open in the player, merged natively by the one rule (its Paper copy
     * follows). The paused player moves to the position native holds afterwards, which may come from an earlier sync,
     * unless the device keeps its own (an unsent change or listening session).
     */
    async checkSyncServerProgressWithLocalProgress(localMediaProgress) {
      if (!localMediaProgress) return
      console.log('[AudioPlayerContainer] checkSyncServerProgressWithLocalProgress: checking server media progress for local media item open in player')
      const serverMediaProgress = await this.getServerMediaProgressForCurrentSession()
      if (!serverMediaProgress?.lastUpdate) return

      let result = null
      try {
        result = await this.$absData.applyServerProgress({ progress: serverMediaProgress })
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to apply the server progress', error)
      }
      if (!result || result.protected) return
      const target = result.applied ? serverMediaProgress.currentTime : result.currentTime
      if (typeof target !== 'number') return
      const shown = this.$refs.audioPlayer?.currentTime ?? localMediaProgress.currentTime
      console.log('[AudioPlayerContainer] checkSyncServerProgressWithLocalProgress: position after the merge', target, 'vs shown', shown, `(applied=${result.applied})`)
      // Under a second apart is the same place (the last tick and the paused player's own position)
      if (!this.$refs.audioPlayer?.isPlaying && Math.abs(target - shown) >= 1) {
        // Use seek() so the native audio player's current session is updated
        this.$refs.audioPlayer.seek(target)
      }
    },
    /**
     * When socket is reconnected after a delay, if a local media item is open in the player (paused)
     * we fetch the server media progress and sync it if it is more recent than the local progress
     *
     * If there is no socket connection we may have missed external progress updates
     */
    async socketReconnected() {
      if (!this.currentPlaybackSession) return
      // dont update timestamps if player is playing
      if (this.$refs.audioPlayer?.isPlaying) return

      if (this.$refs.audioPlayer.isLocalPlayMethod) {
        const localMediaProgress = this.getLocalMediaProgressForCurrentSession()
        if (!localMediaProgress) {
          console.error('[AudioPlayerContainer] socket reconnected: Local media progress not found')
          return
        }

        await this.checkSyncServerProgressWithLocalProgress(localMediaProgress)
      }
    },
    /**
     * When device re-gains focus then refresh the timestamps in the audio player
     * if local item is open then fetch the server media progress and update if more recent
     */
    async deviceFocused(hasFocus) {
      if (hasFocus) await this.onPlaybackEnded()
      if (!this.currentPlaybackSession || !hasFocus) return
      // dont update timestamps if player is playing
      if (this.$refs.audioPlayer?.isPlaying) return

      if (this.$refs.audioPlayer.isLocalPlayMethod) {
        const localMediaProgress = this.getLocalMediaProgressForCurrentSession()
        if (!localMediaProgress) {
          console.error('[AudioPlayerContainer] device visibility: Local media progress not found')
          return
        }

        // Show the native player's live position when it holds this session (it may have moved while the UI was in the
        // background); otherwise the saved local progress as before. Display only: no seek, no progress write
        const { time, source } = refocusDisplayTime(this.currentPlaybackSession, await this.getNativePlaybackState(), localMediaProgress.currentTime)
        console.log('[AudioPlayerContainer] device visibility: found local media progress', localMediaProgress.currentTime, 'showing', source, 'position', time, 'last time in player is', this.currentTime)
        this.$refs.audioPlayer.currentTime = time
        this.$refs.audioPlayer.timeupdate()

        await this.checkSyncServerProgressWithLocalProgress(localMediaProgress)
      } else {
        // server item so fetch server media progress and update player time
        console.log('[AudioPlayerContainer] device visibility: checking server media progress for server media item open in player')
        const data = await this.getServerMediaProgressForCurrentSession()
        if (!data) return
        if (!this.$refs.audioPlayer?.isPlaying) {
          // Another device's newer progress is applied as before; the server position this phone wrote itself is not, so
          // a position the native player moved to while paused (not yet synced) stands and is displayed
          if (!this.refocusServerHandled) this.refocusServerHandled = readRefocusHandled(globalThis.localStorage)
          const decision = refocusServerAction(this.currentPlaybackSession, await this.getNativePlaybackState(), data, this.currentTime, this.refocusServerHandled)
          if (decision.handled) {
            this.refocusServerHandled = decision.handled
            writeRefocusHandled(globalThis.localStorage, decision.handled)
          }
          console.log('[AudioPlayerContainer] device visibility: got server media progress', data.currentTime, 'last time in player is', this.currentTime, 'decision', decision.action, decision.origin || '')
          if (decision.action === 'seek') {
            // Use seek() so the native audio player's current session is updated
            this.$refs.audioPlayer.seek(decision.time)
          } else if (decision.action === 'display') {
            this.$refs.audioPlayer.currentTime = decision.time
            this.$refs.audioPlayer.timeupdate()
          }
        }
      }
    }
  },
  async mounted() {
    // Registered before any await: the child audio player emits this from its own init right after mount
    this.$eventBus.$on('native-playback-reattached', this.nativePlaybackReattached)
    this.$eventBus.$on('native-playback-session', this.nativePlaybackSessionPrepared)
    this.onLocalMediaProgressUpdateListener = await AbsAudioPlayer.addListener('onLocalMediaProgressUpdate', this.onLocalMediaProgressUpdate)
    this.onSleepTimerEndedListener = await AbsAudioPlayer.addListener('onSleepTimerEnded', this.onSleepTimerEnded)
    this.onSleepTimerSetListener = await AbsAudioPlayer.addListener('onSleepTimerSet', this.onSleepTimerSet)
    this.onMediaPlayerChangedListener = await AbsAudioPlayer.addListener('onMediaPlayerChanged', this.onMediaPlayerChanged)
    this.onAwaitingServerListener = await AbsAudioPlayer.addListener('onAwaitingServer', this.onAwaitingServer)

    this.playbackSpeed = this.$store.getters['user/getUserSetting']('playbackRate')
    console.log(`[AudioPlayerContainer] Init Playback Speed: ${this.playbackSpeed}`)

    this.$eventBus.$on('abs-ui-ready', this.onReady)
    this.$eventBus.$on('play-item', this.playLibraryItem)
    this.$eventBus.$on('pause-item', this.pauseItem)
    this.$eventBus.$on('close-stream', this.closeStreamOnly)
    this.$eventBus.$on('cast-local-item', this.castLocalItem)
    this.$eventBus.$on('user-settings', this.settingsUpdated)
    this.$eventBus.$on('playback-time-update', this.playbackTimeUpdate)
    this.$eventBus.$on('device-focus-update', this.deviceFocused)
    this.$eventBus.$on('socket-reconnected', this.socketReconnected)
    this.$eventBus.$on('playback-ended', this.onPlaybackEnded)
    this.$eventBus.$on('add-to-queue', this.addSingleItemToQueue)
    this.$eventBus.$on('add-queue-source-to-queue', this.addQueueSourceToQueue)
  },
  beforeDestroy() {
    this.onLocalMediaProgressUpdateListener?.remove()
    this.onSleepTimerEndedListener?.remove()
    this.onSleepTimerSetListener?.remove()
    this.onMediaPlayerChangedListener?.remove()
    this.onAwaitingServerListener?.remove()

    this.$eventBus.$off('abs-ui-ready', this.onReady)
    this.$eventBus.$off('play-item', this.playLibraryItem)
    this.$eventBus.$off('pause-item', this.pauseItem)
    this.$eventBus.$off('close-stream', this.closeStreamOnly)
    this.$eventBus.$off('cast-local-item', this.castLocalItem)
    this.$eventBus.$off('user-settings', this.settingsUpdated)
    this.$eventBus.$off('playback-time-update', this.playbackTimeUpdate)
    this.$eventBus.$off('device-focus-update', this.deviceFocused)
    this.$eventBus.$off('socket-reconnected', this.socketReconnected)
    this.$eventBus.$off('playback-ended', this.onPlaybackEnded)
    this.$eventBus.$off('add-to-queue', this.addSingleItemToQueue)
    this.$eventBus.$off('add-queue-source-to-queue', this.addQueueSourceToQueue)
    this.$eventBus.$off('native-playback-reattached', this.nativePlaybackReattached)
    this.$eventBus.$off('native-playback-session', this.nativePlaybackSessionPrepared)
  }
}
</script>
