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

    <modals-item-more-menu-modal v-model="showMoreMenu" :library-item="selectedLibraryItem" :episode="selectedEpisode" :playlist="playlist" hide-rss-feed-option :processing.sync="processing" />
    <div v-show="processing" class="fixed top-0 left-0 w-screen h-screen flex items-center justify-center bg-black/50 z-50">
      <ui-loading-indicator />
    </div>
    <modals-download-or-stream-modal v-model="showDownloadOrStreamModal" :any-local="pendingAnyLocal" @play="onDownloadOrStreamPlay" @downloadAndPlay="onDownloadOrStreamDownloadAndPlay" />
  </div>
</template>

<script>
import { queueItemPayload, resolveItemProgress } from '@/utils/playbackQueue'
import { downloadMissingItems } from '@/utils/bulkDownload'

export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    const playlist = await app.$nativeHttp.get(`/api/playlists/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!playlist) {
      return redirect('/bookshelf/playlists')
    }

    // Lookup matching local items & episodes and attach to playlist items
    if (playlist.items.length) {
      const localLibraryItems = (await app.$db.getLocalLibraryItems(playlist.items[0].libraryItem.mediaType)) || []
      if (localLibraryItems.length) {
        playlist.items.forEach((playlistItem) => {
          const matchingLocalLibraryItem = localLibraryItems.find((lli) => lli.libraryItemId === playlistItem.libraryItemId || (playlistItem.libraryItem?.ino && lli.ino && lli.ino === playlistItem.libraryItem.ino))
          if (!matchingLocalLibraryItem) return
          if (playlistItem.episode) {
            const matchingLocalEpisode = matchingLocalLibraryItem.media.episodes?.find((lep) => lep.serverEpisodeId === playlistItem.episodeId)
            if (matchingLocalEpisode) {
              playlistItem.localLibraryItem = matchingLocalLibraryItem
              playlistItem.localEpisode = matchingLocalEpisode
            }
          } else {
            playlistItem.localLibraryItem = matchingLocalLibraryItem
          }
        })
      }
    }

    return {
      playlist
    }
  },
  data() {
    return {
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
        if (libraryItem.isMissing || libraryItem.isInvalid) return false
        if (item.episode) return item.episode.audioFile
        return libraryItem.media.tracks.length
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
      // Local playback only resumes from local progress; pass an explicit override when this
      // item's real progress is server-side (e.g. listened elsewhere before downloading), so it
      // doesn't silently restart at 0:00 despite the UI showing it as partially listened.
      if (nextItem.localLibraryItem) {
        const progress = resolveItemProgress(this, nextItem)
        if (progress?.currentTime) payload.startTime = progress.currentTime
      }
      this.$eventBus.$emit('play-item', payload)
    },
    onDownloadOrStreamPlay() {
      this.startPlaylistPlayback(this.pendingNextItem)
    },
    onDownloadOrStreamDownloadAndPlay() {
      downloadMissingItems(this, this.playableItems)
      this.startPlaylistPlayback(this.pendingNextItem)
    },
    async addToQueueClick() {
      await this.$hapticsImpact()
      this.$eventBus.$emit('add-queue-source-to-queue', { sourceType: 'playlist', sourceId: this.playlist.id, items: this.playableItems })
    },
    playlistUpdated(playlist) {
      if (this.playlist.id !== playlist.id) return
      this.playlist = playlist
    },
    playlistRemoved(playlist) {
      if (this.playlist.id === playlist.id) {
        this.$router.replace('/bookshelf/playlists')
      }
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
      // Playlist contents are shown in the context of the selected library, so leave this page
      // when the library changes rather than showing stale items
      this.$router.replace('/bookshelf/playlists')
    }
  },
  mounted() {
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('local-library-items-changed', this.refreshLocalItems)
    this.$socket.$on('playlist_updated', this.playlistUpdated)
    this.$socket.$on('playlist_removed', this.playlistRemoved)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('local-library-items-changed', this.refreshLocalItems)
    this.$socket.$off('playlist_updated', this.playlistUpdated)
    this.$socket.$off('playlist_removed', this.playlistRemoved)
  }
}
</script>
