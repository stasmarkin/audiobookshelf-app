<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
      <div class="w-full flex justify-center md:block sm:w-32 md:w-52" style="min-width: 240px">
        <div class="relative" style="height: fit-content">
          <covers-collection-cover class="detail-artwork" :book-items="bookItems" :width="240" :height="120 * bookCoverAspectRatio" :book-cover-aspect-ratio="bookCoverAspectRatio" />
        </div>
      </div>
      <div class="flex-grow py-6">
        <div class="flex items-center px-2">
          <h1 class="text-xl font-sans">
            {{ collectionName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" color="success" :padding-x="4" :loading="playerIsStartingForThisMedia" small class="flex items-center justify-center mx-1 w-24" @click="playClick">
            <span class="material-symbols text-2xl fill">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : $strings.ButtonPlay }}</span>
          </ui-btn>
          <span v-if="showPlayButton && $platform === 'android'" class="material-symbols text-2xl text-fg-muted cursor-pointer mx-1" @click="addToQueueClick">queue_music</span>
        </div>

        <div class="my-8 max-w-2xl px-2">
          <p class="text-base text-fg">{{ description }}</p>
        </div>

        <tables-collection-books-table :books="bookItems" :collection-id="collection.id" />
      </div>
    </div>
    <div v-show="processingRemove" class="absolute top-0 left-0 w-full h-full z-10 bg-black bg-opacity-40 flex items-center justify-center">
      <ui-loading-indicator />
    </div>
    <modals-download-or-stream-modal v-model="showDownloadOrStreamModal" :any-local="pendingAnyLocal" @play="onDownloadOrStreamPlay" @downloadAndPlay="onDownloadOrStreamDownloadAndPlay" />
  </div>
</template>

<script>
import { downloadMissingItems } from '@/utils/bulkDownload'
import { buildQueueSourceItems } from '@/utils/playbackQueue'
import { trackCount, fetchCollectionBooks } from '@/utils/catalogItem'
import { createDebouncer } from '@/utils/catalogRefresh'
import { leavesOnLibraryChange } from '@/utils/libraryScope'
import requiresServer from '@/mixins/requiresServer'

/**
 * The collection with all its books ($absData: the replica, also offline), each with its downloaded copy of the active
 * server (replicas of two accounts on one server share item ids); null when it is gone.
 */
async function readCollection({ $absData, $db }, id, activeServerConfigId) {
  const answer = await $absData.getCollection({ id })
  if (!answer?.collection) return null
  const books = await fetchCollectionBooks($absData, id, { serverConfigId: answer.serverConfigId })
  if (!books) return null
  const localLibraryItems = books.length ? (await $db.getLocalLibraryItems('book')) || [] : []
  books.forEach((book) => {
    const copy = localLibraryItems.find((lli) => {
      if (activeServerConfigId && lli.serverConnectionConfigId !== activeServerConfigId) return false
      return lli.libraryItemId === book.id || (book.ino && lli.ino && lli.ino === book.ino)
    })
    if (copy) book.localLibraryItem = copy
  })
  return { ...answer.collection, books }
}

export default {
  mixins: [requiresServer],
  async asyncData({ store, params, app, redirect }) {
    let collection
    try {
      collection = await readCollection(app, params.id, store.state.activeServerConfigId)
    } catch (error) {
      console.error('Failed to read the collection', error)
      return redirect('/bookshelf/collections?error=collection')
    }
    if (!collection) {
      return redirect('/bookshelf/collections')
    }
    return {
      collection
    }
  },
  data() {
    return {
      readGeneration: 0,
      mediaIdStartingPlayback: null,
      processingRemove: false,
      showDownloadOrStreamModal: false,
      pendingQueueItems: [],
      pendingAnyLocal: false
    }
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookItems() {
      return this.collection.books || []
    },
    collectionName() {
      return this.collection.name || ''
    },
    description() {
      return this.collection.description || ''
    },
    playableItems() {
      return this.bookItems.filter((book) => {
        return !book.isMissing && !book.isInvalid && trackCount(book) > 0
      })
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isOpenInPlayer
    },
    isOpenInPlayer() {
      return !!this.playableItems.find((i) => {
        if (i.localLibraryItem && this.$store.getters['getIsMediaStreaming'](i.localLibraryItem.id)) return true
        return this.$store.getters['getIsMediaStreaming'](i.id)
      })
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      if (!this.mediaIdStartingPlayback) return false
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      return mediaId === this.mediaIdStartingPlayback
    },
    showPlayButton() {
      return this.playableItems.length
    }
  },
  methods: {
    async playClick() {
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()

      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.playNextItem()
      }
    },
    async playNextItem() {
      if (this.$platform === 'android') {
        if (this.$store.state.playerIsStartingPlayback) return
        this.$store.commit('setPlayerIsStartingPlayback', this.collection.id)
        let items
        try {
          // Re-resolves local copies with the same completeness/ino-aware check continuous
          // series playback and Add-to-Queue already use, instead of trusting the asyncData
          // attachment as-is (which only checked libraryItemId and didn't verify the local copy
          // was actually complete).
          items = await buildQueueSourceItems(this, { sourceType: 'collection', sourceId: this.collection.id, books: this.bookItems })
        } catch (error) {
          console.error('Failed to resolve collection queue', error)
          this.$store.commit('setPlayerDoneStartingPlayback')
          this.$toast.error(error.message || 'Failed to load collection')
          return
        }
        const missingCount = items.filter((item) => !item.localLibraryItem).length
        if (missingCount && this.$store.getters['getAskBeforeStreamingIncomplete']) {
          this.$store.commit('setPlayerDoneStartingPlayback')
          this.pendingQueueItems = items
          this.pendingAnyLocal = items.length > missingCount
          this.showDownloadOrStreamModal = true
          return
        }
        this.startCollectionPlayback(items)
        return
      }
      const nextBookNotRead = this.playableItems.find((pb) => {
        const prog = this.$store.getters['user/getUserMediaProgress'](pb.id)
        return !prog?.isFinished
      })
      if (nextBookNotRead) {
        this.mediaIdStartingPlayback = nextBookNotRead.id
        this.$store.commit('setPlayerIsStartingPlayback', nextBookNotRead.id)

        if (nextBookNotRead.localLibraryItem) {
          this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.localLibraryItem.id, serverLibraryItemId: nextBookNotRead.id })
        } else {
          this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.id })
        }
      }
    },
    startCollectionPlayback(items) {
      this.$store.commit('setPlayerIsStartingPlayback', this.collection.id)
      this.mediaIdStartingPlayback = this.collection.id
      this.$eventBus.$emit('play-item', { queueSource: { sourceType: 'collection', sourceId: this.collection.id, items } })
    },
    onDownloadOrStreamPlay() {
      this.startCollectionPlayback(this.pendingQueueItems)
    },
    onDownloadOrStreamDownloadAndPlay() {
      if (!this.isServerOnline) return
      downloadMissingItems(this, this.pendingQueueItems)
      this.startCollectionPlayback(this.pendingQueueItems)
    },
    async addToQueueClick() {
      await this.$hapticsImpact()
      this.$eventBus.$emit('add-queue-source-to-queue', { sourceType: 'collection', sourceId: this.collection.id, books: this.bookItems })
    },
    /** Native changed this library's collections or books: read the collection again (debounced). */
    dataChanged(change) {
      const libraryId = this.collection.libraryId
      if (change?.scope !== 'catalog' || !libraryId) return
      if (!(change.ids || []).some((topic) => topic === `collections:${libraryId}` || topic === `items:${libraryId}`)) return
      if (!this.refreshDebouncer) this.refreshDebouncer = createDebouncer({ delayMs: 1000 })
      this.refreshDebouncer.schedule(this.reload)
    },
    /** Only the latest read of this collection lands; a collection that went away leaves for the tab. */
    async reload() {
      const generation = ++this.readGeneration
      const id = this.collection.id
      let collection
      try {
        collection = await readCollection(this, id, this.$store.state.activeServerConfigId)
      } catch (error) {
        console.error('Failed to read the collection again', error)
        return
      }
      if (this.isDestroyed || generation !== this.readGeneration || this.collection.id !== id) return
      if (!collection) {
        this.$router.replace('/bookshelf/collections')
        return
      }
      this.collection = collection
    },
    libraryChanged() {
      // A collection belongs to a single library, so leave this page when a different library (or server) is
      // selected rather than showing a collection that is not in the current library
      if (leavesOnLibraryChange(this.$store.state, this.collection.libraryId, this.loadedServerConfigId)) {
        this.$router.replace('/bookshelf/collections')
      }
    }
  },
  mounted() {
    this.loadedServerConfigId = this.$store.state.activeServerConfigId
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
  },
  beforeDestroy() {
    this.isDestroyed = true
    this.$eventBus.$off('library-changed', this.libraryChanged)
    if (this.stopDataChanged) this.stopDataChanged()
    this.stopDataChanged = null
    if (this.refreshDebouncer) this.refreshDebouncer.cancel()
  }
}
</script>
