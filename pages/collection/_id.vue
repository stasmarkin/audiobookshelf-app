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
export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    var collection = await app.$nativeHttp.get(`/api/collections/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!collection) {
      return redirect('/bookshelf')
    }

    // Lookup matching local items and attach to collection items
    if (collection.books.length) {
      const localLibraryItems = (await app.$db.getLocalLibraryItems('book')) || []
      if (localLibraryItems.length) {
        collection.books.forEach((collectionItem) => {
          const matchingLocalLibraryItem = localLibraryItems.find((lli) => lli.libraryItemId === collectionItem.id || (collectionItem.ino && lli.ino && lli.ino === collectionItem.ino))
          if (!matchingLocalLibraryItem) return
          collectionItem.localLibraryItem = matchingLocalLibraryItem
        })
      }
    }

    return {
      collection
    }
  },
  data() {
    return {
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
        return !book.isMissing && !book.isInvalid && book.media.tracks.length
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
    // Downloads may have been removed (or added) in the background: re-match every book and drop the gone ones
    async refreshLocalItems() {
      const books = this.collection.books || []
      if (!books.length) return
      const locals = (await this.$db.getLocalLibraryItems('book')) || []
      books.forEach((book) => {
        const match = locals.find((lli) => lli.libraryItemId === book.id || (book.ino && lli.ino && lli.ino === book.ino))
        this.$set(book, 'localLibraryItem', match || null)
      })
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
      downloadMissingItems(this, this.pendingQueueItems)
      this.startCollectionPlayback(this.pendingQueueItems)
    },
    async addToQueueClick() {
      await this.$hapticsImpact()
      this.$eventBus.$emit('add-queue-source-to-queue', { sourceType: 'collection', sourceId: this.collection.id, books: this.bookItems })
    },
    libraryChanged(libraryId) {
      // A collection belongs to a single library, so leave this page when a different library is
      // selected rather than showing a collection that is not in the current library
      if (!libraryId || libraryId !== this.collection.libraryId) {
        this.$router.replace('/bookshelf/collections')
      }
    }
  },
  mounted() {
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('local-library-items-changed', this.refreshLocalItems)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('local-library-items-changed', this.refreshLocalItems)
  }
}
</script>
