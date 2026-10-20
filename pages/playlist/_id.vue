<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto py-6 md:p-8">
      <div class="w-full flex justify-center">
        <covers-playlist-cover class="detail-artwork" :items="playlistItems" :width="180" :height="180" />
      </div>
      <div class="flex-grow px-1 py-6">
        <div class="flex items-center px-3">
          <h1 class="text-xl font-sans">
            {{ playlistName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" color="success" :padding-x="4" :loading="playerIsStartingForThisMedia" small class="flex items-center justify-center mx-1 w-24" @click="playClick">
            <span class="material-symbols text-2xl fill">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : $strings.ButtonPlay }}</span>
          </ui-btn>
          <span v-if="showPlayButton" class="material-symbols text-2xl text-fg-muted cursor-pointer mx-1" @click="addToQueueClick">queue_music</span>
        </div>

        <div class="my-8 max-w-2xl px-3">
          <p class="text-base text-fg">{{ description }}</p>
        </div>

        <tables-playlist-items-table :items="playlistItems" :playlist-id="playlist.id" @showMore="showMore" />
      </div>
    </div>

    <modals-item-more-menu-modal v-model="showMoreMenu" :library-item="selectedLibraryItem" :episode="selectedEpisode" :playlist="playlist" hide-rss-feed-option :processing.sync="processing" @removedFromPlaylist="itemRemoved" />
    <div v-show="processing" class="fixed top-0 left-0 w-screen h-screen flex items-center justify-center bg-black/50 z-50">
      <ui-loading-indicator />
    </div>
    <modals-download-or-stream-modal v-model="showDownloadOrStreamModal" :any-local="pendingAnyLocal" @play="onDownloadOrStreamPlay" @downloadAndPlay="onDownloadOrStreamDownloadAndPlay" />
  </div>
</template>

<script>
import { queueItemPayload, resolveItemProgress } from '@/utils/playbackQueue'
import { downloadMissingItems } from '@/utils/bulkDownload'
import { trackCount, fetchPlaylistItems } from '@/utils/catalogItem'
import { createDebouncer } from '@/utils/catalogRefresh'
import { leavesOnLibraryChange } from '@/utils/libraryScope'
import requiresServer from '@/mixins/requiresServer'

/**
 * The playlist with all its items ($absData: the replica, also offline), each with its downloaded copy of the active
 * server (replicas of two accounts on one server share item ids); null when it is gone.
 */
async function readPlaylist({ $absData, $db }, id, activeServerConfigId) {
  const answer = await $absData.getPlaylist({ id })
  if (!answer?.playlist) return null
  const items = await fetchPlaylistItems($absData, id, { serverConfigId: answer.serverConfigId })
  if (!items) return null
  const mediaTypes = [...new Set(items.map((item) => item.libraryItem?.mediaType).filter(Boolean))]
  const localLibraryItems = (await Promise.all(mediaTypes.map((mediaType) => $db.getLocalLibraryItems(mediaType)))).flatMap((list) => list || [])
  items.forEach((item) => {
    const ino = item.libraryItem?.ino
    const copy = localLibraryItems.find((lli) => {
      if (activeServerConfigId && lli.serverConnectionConfigId !== activeServerConfigId) return false
      if (lli.isInvalid) return false
      return lli.libraryItemId === item.libraryItemId || (ino && lli.ino && lli.ino === ino)
    })
    if (!copy) return
    if (item.episodeId) {
      const localEpisode = copy.media?.episodes?.find((lep) => lep.serverEpisodeId === item.episodeId)
      if (!localEpisode) return
      item.localLibraryItem = copy
      item.localEpisode = localEpisode
    } else {
      item.localLibraryItem = copy
    }
  })
  return { ...answer.playlist, items }
}

export default {
  mixins: [requiresServer],
  async asyncData({ store, params, app, redirect }) {
    let playlist
    try {
      playlist = await readPlaylist(app, params.id, store.state.activeServerConfigId)
    } catch (error) {
      console.error('Failed to read the playlist', error)
      return redirect('/bookshelf/playlists?error=playlist')
    }
    if (!playlist) {
      return redirect('/bookshelf/playlists')
    }
    return {
      playlist
    }
  },
  data() {
    return {
      readGeneration: 0,
      /** Items removed here, by key, until the replica stops listing them. */
      removedKeys: [],
      showMoreMenu: false,
      processing: false,
      selectedLibraryItem: null,
      selectedEpisode: null,
      mediaIdStartingPlayback: null,
      showDownloadOrStreamModal: false,
      pendingAnyLocal: false,
      pendingNextItem: null
    }
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    playlistItems() {
      return this.playlist.items || []
    },
    playlistName() {
      return this.playlist.name || ''
    },
    description() {
      return this.playlist.description || ''
    },
    playableItems() {
      return this.playlistItems.filter((item) => {
        const libraryItem = item.libraryItem
        if (!libraryItem || libraryItem.isMissing || libraryItem.isInvalid) return false
        if (item.episodeId) return !!item.episode?.audioFile
        return trackCount(libraryItem) > 0
      })
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isOpenInPlayer
    },
    isOpenInPlayer() {
      return !!this.playableItems.find((i) => {
        if (i.localLibraryItem && this.$store.getters['getIsMediaStreaming'](i.localLibraryItem.id, i.localEpisode?.id)) return true
        return this.$store.getters['getIsMediaStreaming'](i.libraryItemId, i.episodeId)
      })
    },
    showPlayButton() {
      return this.playableItems.length
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      if (!this.mediaIdStartingPlayback) return false
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      return mediaId === this.mediaIdStartingPlayback
    }
  },
  methods: {
    showMore(playlistItem) {
      this.selectedLibraryItem = playlistItem.libraryItem
      this.selectedEpisode = playlistItem.episode
      this.showMoreMenu = true
    },
    async playClick() {
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()

      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.playNextItem()
      }
    },
    playNextItem() {
      const nextIndex = this.playableItems.findIndex((i) => {
        const prog = resolveItemProgress(this, i)
        return !prog?.isFinished
      })
      if (nextIndex < 0) return
      const nextItem = this.playableItems[nextIndex]
      const missingCount = this.playableItems.filter((item) => !item.localLibraryItem).length
      if (missingCount && this.$store.getters['getAskBeforeStreamingIncomplete']) {
        this.pendingNextItem = nextItem
        this.pendingAnyLocal = this.playableItems.length > missingCount
        this.showDownloadOrStreamModal = true
        return
      }
      this.startPlaylistPlayback(nextItem)
    },
    startPlaylistPlayback(nextItem) {
      this.mediaIdStartingPlayback = nextItem.episodeId || nextItem.libraryItemId
      this.$store.commit('setPlayerIsStartingPlayback', this.mediaIdStartingPlayback)
      const payload = {
        ...queueItemPayload(nextItem),
        queueSource: { sourceType: 'playlist', sourceId: this.playlist.id, items: this.playableItems }
      }
      this.$eventBus.$emit('play-item', payload)
    },
    onDownloadOrStreamPlay() {
      this.startPlaylistPlayback(this.pendingNextItem)
    },
    onDownloadOrStreamDownloadAndPlay() {
      if (!this.isServerOnline) return
      downloadMissingItems(this, this.playableItems)
      this.startPlaylistPlayback(this.pendingNextItem)
    },
    async addToQueueClick() {
      await this.$hapticsImpact()
      this.$eventBus.$emit('add-queue-source-to-queue', { sourceType: 'playlist', sourceId: this.playlist.id, items: this.playableItems })
    },
    /** Removed on the server: gone from the page at once; the server deletes a playlist left empty. */
    itemRemoved({ libraryItemId, episodeId }) {
      // A read running or due now may still see it in the replica
      this.removedKeys = [...this.removedKeys, `${libraryItemId}|${episodeId || ''}`]
      this.showItems(this.playlist)
    },
    /** [playlist] without the items removed here; one left empty was deleted by the server. */
    showItems(playlist) {
      const key = (i) => `${i.libraryItemId}|${i.episodeId || ''}`
      const listed = new Set((playlist.items || []).map(key))
      this.removedKeys = this.removedKeys.filter((k) => listed.has(k))
      const items = (playlist.items || []).filter((i) => !this.removedKeys.includes(key(i)))
      this.playlist = { ...playlist, items }
      if (!items.length) this.$router.replace('/bookshelf/playlists')
    },
    /** Native changed this library's playlists or items: read the playlist again (debounced). */
    dataChanged(change) {
      const libraryId = this.playlist.libraryId
      if (change?.scope !== 'catalog' || !libraryId) return
      if (!(change.ids || []).some((topic) => topic === `playlists:${libraryId}` || topic === `items:${libraryId}`)) return
      if (!this.refreshDebouncer) this.refreshDebouncer = createDebouncer({ delayMs: 1000 })
      this.refreshDebouncer.schedule(this.reload)
    },
    /** Only the latest read of this playlist lands; a playlist that went away leaves for the tab. */
    async reload() {
      const generation = ++this.readGeneration
      const id = this.playlist.id
      let playlist
      try {
        playlist = await readPlaylist(this, id, this.$store.state.activeServerConfigId)
      } catch (error) {
        console.error('Failed to read the playlist again', error)
        return
      }
      if (this.isDestroyed || generation !== this.readGeneration || this.playlist.id !== id) return
      if (!playlist) {
        this.$router.replace('/bookshelf/playlists')
        return
      }
      this.showItems(playlist)
    },
    // Downloads may have been removed (or added) in the background: re-match every item and drop the gone ones
    async refreshLocalItems() {
      const items = this.playlist.items || []
      if (!items.length) return
      const mediaType = items.find((i) => i.libraryItem)?.libraryItem.mediaType
      if (!mediaType) return
      const locals = (await this.$db.getLocalLibraryItems(mediaType)) || []
      items.forEach((playlistItem) => {
        const match = locals.find((lli) => lli.libraryItemId === playlistItem.libraryItemId || (playlistItem.libraryItem?.ino && lli.ino && lli.ino === playlistItem.libraryItem.ino))
        const localEpisode = match && playlistItem.episode ? match.media.episodes?.find((lep) => lep.serverEpisodeId === playlistItem.episodeId) || null : null
        const bound = match && (!playlistItem.episode || localEpisode)
        this.$set(playlistItem, 'localLibraryItem', bound ? match : null)
        this.$set(playlistItem, 'localEpisode', bound ? localEpisode : null)
      })
    },
    libraryChanged() {
      // Playlist contents are shown in the context of the selected library, so leave this page when another library
      // (or server) is selected; a sign-in or reconnect announces the same one again
      if (leavesOnLibraryChange(this.$store.state, this.playlist.libraryId, this.loadedServerConfigId)) this.$router.replace('/bookshelf/playlists')
    }
  },
  mounted() {
    this.loadedServerConfigId = this.$store.state.activeServerConfigId
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('local-library-items-changed', this.reload)
    this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
  },
  beforeDestroy() {
    this.isDestroyed = true
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('local-library-items-changed', this.reload)
    if (this.stopDataChanged) this.stopDataChanged()
    this.stopDataChanged = null
    if (this.refreshDebouncer) this.refreshDebouncer.cancel()
  }
}
</script>
