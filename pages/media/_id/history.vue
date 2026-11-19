<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto relative bg-bg">
    <div class="mb-4 flex items-center">
      <p class="text-lg font-semibold flex-grow">History for {{ displayTitle }}</p>
      <div class="flex items-center pl-2 cursor-pointer" @click="toggleShowAll">
        <span class="material-symbols text-xl text-fg-muted">{{ showAll ? 'check_box' : 'check_box_outline_blank' }}</span>
        <p class="text-sm text-fg-muted pl-1 whitespace-nowrap">{{ $strings.LabelAllEvents }}</p>
      </div>
    </div>

    <div v-if="!groups.length" class="text-center py-8">
      <p class="text-fg">No History</p>
      <p v-if="mediaEvents.length && !showAll" class="text-sm text-fg-muted pt-2">{{ $strings.MessageHistoryOnlyServiceEvents }}</p>
    </div>

    <div v-for="group in groups" :key="group.dayStart" class="py-2">
      <p class="my-2 text-fg-muted font-semibold">{{ dayLabel(group.dayStart) }}</p>
      <div v-for="(row, index) in group.rows" :key="row.evt.id || `${row.evt.timestamp}-${row.evt.name}-${index}`" class="py-3 flex items-center">
        <p class="text-sm text-fg-muted w-12">{{ $formatDate(row.evt.timestamp, 'HH:mm') }}</p>
        <span class="material-symbols fill px-2" :class="`text-${row.isSleepTimer ? 'info' : getEventColor(row.evt.name)}`">{{ row.isSleepTimer ? 'bedtime' : getEventIcon(row.evt.name) }}</span>
        <p class="text-sm text-fg px-1">{{ row.isSleepTimer ? $strings.LabelSleepTimer : row.evt.name }}</p>

        <span v-if="row.evt.serverSyncAttempted && row.evt.serverSyncSuccess" class="material-symbols px-1 text-base text-success">cloud_done</span>
        <span v-if="row.evt.serverSyncAttempted && !row.evt.serverSyncSuccess" class="material-symbols px-1 text-base text-error">error_outline</span>

        <p v-if="row.num" class="text-sm text-fg-muted italic px-1">+{{ row.num }}</p>
        <p v-if="row.isNow" class="text-sm text-fg-muted px-1">· {{ $strings.LabelNowPlaying }}</p>
        <p v-else-if="row.durationMs !== null" class="text-sm text-fg-muted px-1">· {{ formatListenDuration(row.durationMs) }}</p>

        <div class="flex-grow" />
        <p class="text-base text-fg" @click="clickPlaybackTime(row.evt.currentTime)">{{ $secondsToTimestampFull(row.evt.currentTime) }}</p>
      </div>
    </div>
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { acceptsHistoryUpdate, buildHistoryRows, formatListenDuration, pickPlayTarget } from '@/utils/mediaHistory'

export default {
  async asyncData({ params, store, redirect, app, query }) {
    const mediaItemHistory = await app.$db.getMediaItemHistory(params.id)

    return {
      title: query.title || 'Unknown',
      mediaItemHistory
    }
  },
  data() {
    return {
      showAll: false,
      onMediaItemHistoryUpdatedListener: null
    }
  },
  computed: {
    displayTitle() {
      if (!this.mediaItemHistory) return this.title
      return this.mediaItemHistory.mediaDisplayTitle
    },
    mediaEvents() {
      if (!this.mediaItemHistory) return []
      return this.mediaItemHistory.events || []
    },
    // The open book is the one playing now: its newest Play shows "now" instead of a duration
    nowPlaying() {
      const session = this.$store.state.currentPlaybackSession
      if (!this.$store.state.playerIsPlaying || !session) return false
      const key = session.libraryItemId ? (session.episodeId ? `${session.libraryItemId}-${session.episodeId}` : session.libraryItemId) : session.localLibraryItem ? (session.localEpisodeId ? `${session.localLibraryItem.id}-${session.localEpisodeId}` : session.localLibraryItem.id) : null
      return key === this.$route.params.id
    },
    groups() {
      return buildHistoryRows(this.mediaEvents, { showAll: this.showAll, nowPlaying: this.nowPlaying })
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    }
  },
  methods: {
    formatListenDuration,
    dayLabel(dayStart) {
      const date = this.$formatDate(dayStart, 'MMM dd, yyyy')
      if (date === this.$formatDate(new Date(), 'MMM dd, yyyy')) return 'Today'
      if (date === this.$formatDate(Date.now() - 1000 * 60 * 60 * 24, 'MMM dd, yyyy')) return 'Yesterday'
      return date
    },
    toggleShowAll() {
      this.showAll = !this.showAll
      this.$localStore.setHistoryShowAll(this.showAll)
    },
    async clickPlaybackTime(time) {
      if (this.playerIsStartingPlayback || !this.mediaItemHistory) return

      await this.$hapticsImpact()
      this.playAtTime(time)
    },
    async playAtTime(startTime) {
      const history = this.mediaItemHistory
      const localCopy = history.isLocal ? null : await this.$db.getLocalLibraryItemByLId(history.libraryItemId)
      const target = pickPlayTarget({
        history,
        localCopy,
        isCasting: this.$store.state.isCasting,
        currentServerAddress: this.$store.getters['user/getServerAddress'],
        currentUserId: this.$store.state.user.user?.id || null
      })
      if (!target) {
        this.$toast.error(this.$strings.ToastHistoryItemUnavailable)
        return
      }
      this.$store.commit('setPlayerIsStartingPlayback', target.episodeId || target.libraryItemId)
      this.$eventBus.$emit('play-item', { ...target, startTime, playAfterSeek: true })
    },
    getEventIcon(name) {
      switch (name) {
        case 'Play':
          return 'play_circle'
        case 'Pause':
          return 'pause_circle'
        case 'Stop':
          return 'stop_circle'
        case 'Save':
          return 'sync'
        case 'Seek':
          return 'commit'
        case 'Sync':
          return 'cloud_download'
        default:
          return 'info'
      }
    },
    getEventColor(name) {
      switch (name) {
        case 'Play':
          return 'success'
        case 'Pause':
          return 'gray-300'
        case 'Stop':
          return 'error'
        case 'Save':
          return 'info'
        case 'Seek':
          return 'gray-200'
        case 'Sync':
          return 'accent'
        default:
          return 'info'
      }
    },
    onMediaItemHistoryUpdated(mediaItemHistory) {
      if (!acceptsHistoryUpdate(this.$route.params.id, mediaItemHistory)) return
      this.mediaItemHistory = mediaItemHistory
    }
  },
  async mounted() {
    this.showAll = await this.$localStore.getHistoryShowAll()
    this.onMediaItemHistoryUpdatedListener = await AbsAudioPlayer.addListener('onMediaItemHistoryUpdated', this.onMediaItemHistoryUpdated)
    // A journal write that landed before the listener was attached is not lost
    const latest = await this.$db.getMediaItemHistory(this.$route.params.id)
    if (latest) this.mediaItemHistory = latest
  },
  beforeDestroy() {
    this.onMediaItemHistoryUpdatedListener?.remove()
  }
}
</script>
