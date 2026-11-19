<template>
  <div class="w-full h-full min-h-full relative">
    <div v-if="showCatalogPartial" class="catalog-partial sticky top-0 z-40 w-full px-3 text-center text-xs text-warning bg-bg/90" style="height: 24px; line-height: 24px">{{ $strings.MessageCatalogPartial }}</div>
    <div v-if="attemptingConnection" class="w-full pt-4 flex items-center justify-center">
      <widgets-loading-spinner />
      <p class="pl-4">{{ $strings.MessageAttemptingServerConnection }}</p>
    </div>
    <div v-if="shelves.length && isLoading" class="w-full pt-4 flex items-center justify-center">
      <widgets-loading-spinner />
      <p class="pl-4">{{ $strings.MessageLoadingServerData }}</p>
    </div>

    <p v-if="catalogNotLoaded && shelves.length" class="w-full px-6 pt-4 text-center text-sm text-fg-muted">{{ $strings.MessageCatalogNotLoaded }}</p>
    <div class="w-full" :class="{ 'py-6': altViewEnabled }">
      <template v-for="(shelf, index) in shelves">
        <bookshelf-shelf :key="shelf.id" :label="getShelfLabel(shelf)" :entities="shelf.entities" :type="shelf.type" :style="{ zIndex: shelves.length - index }" />
      </template>
    </div>

    <div v-if="!shelves.length && !isLoading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
      <div>
        <p class="mb-4 text-center text-xl">
          {{ catalogNotLoaded ? $strings.MessageCatalogNotLoaded : $strings.MessageBookshelfEmpty }}
        </p>
        <div class="w-full" v-if="!user && serverUnreachable">
          <div class="flex justify-center items-center mb-3">
            <span class="material-symbols text-warning text-lg">cloud_off</span>
            <p class="pl-2 text-warning text-sm">{{ $strings.MessageServerUnreachableShowingDevice }}</p>
          </div>
          <!-- A way out when the saved server is gone for good -->
          <p class="text-center text-sm text-fg-muted underline" @click="$router.push('/connect')">{{ $strings.ButtonSwitchServerUser }}</p>
        </div>
        <div class="w-full" v-else-if="!user">
          <div class="flex justify-center items-center mb-3">
            <span class="material-symbols text-error text-lg">cloud_off</span>
            <p class="pl-2 text-error text-sm">{{ $strings.MessageAudiobookshelfServerNotConnected }}</p>
          </div>
        </div>
        <div class="flex justify-center">
          <ui-btn v-if="!user && !serverUnreachable" small @click="$router.push('/connect')" class="w-32">{{ $strings.ButtonConnect }}</ui-btn>
        </div>
      </div>
    </div>
    <div v-else-if="!shelves.length && isLoading && !attemptingConnection" class="absolute top-0 left-0 z-50 w-full h-full flex items-center justify-center">
      <ui-loading-indicator :text="$strings.MessageLoading" />
    </div>
  </div>
</template>

<script>
import { mergeHomeShelves, shouldRefreshHome, continueKeysOf } from '@/utils/homeShelves'
import { createDebouncer, isReplicaIncomplete } from '@/utils/catalogRefresh'

/** A read of the same library, server and connection within this time is skipped (notices of native are not). */
const FETCH_THROTTLE_MS = 5000

export default {
  props: {},
  data() {
    return {
      shelves: [],
      catalogNotLoaded: false,
      // Native's shelves come from a library whose reconcile never finished (or was unstable)
      replicaIncomplete: false,
      lastFetch: 0,
      lastFetchKey: null,
      fetchSeq: 0,
      // `{ key, context }`: the playing item a playback tick already re-read the home for (see shouldRefreshHome), and
      // the first "continue" key and playing episode it was asked under; it counts while those stay the same
      refreshedFor: null,
      // `<serverConfigId>|<libraryId>` the shown shelves were read for
      shownFor: null,
      refreshDebouncer: null,
      stopDataChanged: null,
      localLibraryItems: [],
      isLoading: false
    }
  },
  watch: {
    user(newVal, oldVal) {
      // A sign-in makes the server readable for a library native never reconciled
      if ((newVal && !oldVal) || (!newVal && oldVal)) {
        console.log(`[categories] user changed so fetching categories`)
        this.fetchCategories({ force: true })
      }
    }
  },
  computed: {
    // A saved server that does not answer: connecting again is not what helps, the indicator already says so
    /** Shelves of an unfinished catalog copy carry the same note as the catalog tabs while the server is away. */
    showCatalogPartial() {
      return this.replicaIncomplete && this.$store.state.serverState !== 'online'
    },
    serverUnreachable() {
      return this.$store.getters['getHasActiveServer'] && this.$store.state.serverState === 'offline'
    },
    user() {
      return this.$store.state.user.user
    },
    isIos() {
      return this.$platform === 'ios'
    },
    currentLibraryName() {
      return this.$store.getters['libraries/getCurrentLibraryName']
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    currentLibraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    },
    currentLibraryIsPodcast() {
      return this.currentLibraryMediaType === 'podcast'
    },
    altViewEnabled() {
      return this.$store.getters['getAltViewEnabled']
    },
    localMediaProgress() {
      return this.$store.state.globals.localMediaProgress
    },
    attemptingConnection() {
      return this.$store.state.attemptingConnection
    }
  },
  methods: {
    getShelfLabel(shelf) {
      if (shelf.labelStringKey && this.$strings[shelf.labelStringKey]) return this.$strings[shelf.labelStringKey]
      return shelf.label
    },
    getLocalMediaItemCategories() {
      const localMedia = this.localLibraryItems
      if (!localMedia?.length) return []

      const categories = []
      const books = []
      const podcasts = []
      const booksContinueListening = []
      const podcastEpisodesContinueListening = []
      localMedia.forEach((item) => {
        if (item.mediaType == 'book') {
          item.progress = this.$store.getters['globals/getLocalMediaProgressById'](item.id)
          if (item.progress && !item.progress.isFinished && item.progress.progress > 0) booksContinueListening.push(item)
          books.push(item)
        } else if (item.mediaType == 'podcast') {
          const podcastEpisodeItemCloner = { ...item }
          item.media.episodes = item.media.episodes.map((ep) => {
            ep.progress = this.$store.getters['globals/getLocalMediaProgressById'](item.id, ep.id)
            if (ep.progress && !ep.progress.isFinished && ep.progress.progress > 0) {
              podcastEpisodesContinueListening.push({
                ...podcastEpisodeItemCloner,
                recentEpisode: ep
              })
            }
            return ep
          })
          podcasts.push(item)
        }
      })

      // Local continue listening shelves, only shown offline
      if (booksContinueListening.length) {
        categories.push({
          id: 'local-books-continue',
          label: this.$strings.LabelContinueBooks,
          type: 'book',
          localOnly: true,
          entities: booksContinueListening.sort((a, b) => {
            if (a.progress && b.progress) {
              return b.progress.lastUpdate > a.progress.lastUpdate ? 1 : -1
            }
            return 0
          })
        })
      }
      if (podcastEpisodesContinueListening.length) {
        categories.push({
          id: 'local-episodes-continue',
          label: this.$strings.LabelContinueEpisodes,
          type: 'episode',
          localOnly: true,
          entities: podcastEpisodesContinueListening.sort((a, b) => {
            if (a.recentEpisode.progress && b.recentEpisode.progress) {
              return b.recentEpisode.progress.lastUpdate > a.recentEpisode.progress.lastUpdate ? 1 : -1
            }
            return 0
          })
        })
      }

      // Local books and local podcast shelves
      if (books.length) {
        categories.push({
          id: 'local-books',
          label: this.$strings.LabelLocalBooks,
          type: 'book',
          entities: books.sort((a, b) => {
            if (a.progress && a.progress.isFinished) return 1
            else if (b.progress && b.progress.isFinished) return -1
            else if (a.progress && b.progress) {
              return b.progress.lastUpdate > a.progress.lastUpdate ? 1 : -1
            }
            return 0
          })
        })
      }
      if (podcasts.length) {
        categories.push({
          id: 'local-podcasts',
          label: this.$strings.LabelLocalPodcasts,
          type: 'podcast',
          entities: podcasts
        })
      }

      return categories
    },
    /**
     * The home of the current library: native's shelves (the replica, or the server while the library was never
     * reconciled), the downloaded copies joined to them, and the local shelves. `force`: a re-read for a change native
     * reported, not throttled. Only a read of another library (or the first) shows the loading state.
     */
    async fetchCategories({ force = false } = {}) {
      const libraryId = this.currentLibraryId
      const serverConfigId = this.$store.state.activeServerConfigId ?? null
      const readsCatalog = !!(this.$store.getters['getHasActiveServer'] && libraryId)
      const key = [serverConfigId, libraryId, !!this.user, this.$store.state.serverState].join('|')
      if (!force && this.lastFetch && Date.now() - this.lastFetch < FETCH_THROTTLE_MS && this.lastFetchKey === key) {
        console.log(`[categories] fetchCategories was ${Date.now() - this.lastFetch}ms ago so not doing it.`)
        return
      }
      this.lastFetch = Date.now()
      this.lastFetchKey = key
      const seq = ++this.fetchSeq
      const shelvesFor = `${serverConfigId}|${libraryId}`
      const otherShelves = !this.shelves.length || this.shownFor !== shelvesFor
      if (otherShelves) {
        this.isLoading = true
        // Not a moment of the previous library's shelves while the downloaded items are read
        if (this.shownFor !== null && this.shownFor !== shelvesFor) this.showLocalShelves([])
      }

      const localLibraryItems = await this.$db.getLocalLibraryItems()
      if (seq !== this.fetchSeq) return
      this.localLibraryItems = localLibraryItems
      const localShelves = this.getLocalMediaItemCategories()
      // Shelves of the previous library or server never stay: the local ones while native answers. A re-read of the
      // same library keeps its shelves in place
      if (otherShelves) this.showLocalShelves(localShelves)

      let answer = null
      let failed = false
      if (readsCatalog) {
        answer = await this.$absData.getPersonalized({ libraryId }).catch((error) => {
          console.error('[categories] Failed to read the home shelves', error)
          failed = true
          return null
        })
        if (seq !== this.fetchSeq) return
        // Asked for another library or server than the current one: the read for the current one is on its way
        if (answer && (answer.libraryId !== this.currentLibraryId || (answer.serverConfigId ?? null) !== (this.$store.state.activeServerConfigId ?? null))) {
          if (this.shownFor !== `${this.$store.state.activeServerConfigId ?? null}|${this.currentLibraryId}`) this.showLocalShelves(localShelves)
          this.isLoading = false
          return
        }
      }

      if (failed) {
        this.showLocalShelves(localShelves)
      } else {
        const merged = mergeHomeShelves({ answer, localShelves, localItems: localLibraryItems, activeServerConfigId: serverConfigId, mediaType: this.currentLibraryMediaType })
        this.shelves = merged.shelves
        this.catalogNotLoaded = merged.catalogNotLoaded
        // Without native's shelves "catalog not loaded" (or nothing) says it instead
        this.replicaIncomplete = isReplicaIncomplete({ ...answer, total: answer?.shelves?.length || 0 })
        this.shownFor = shelvesFor
      }
      console.log('[categories] Shelves set', this.shelves.length, answer?.fromServer ? 'from the server' : 'from the device')
      this.isLoading = false
    },
    /** The downloaded items' shelves alone, belonging to no library's catalog. */
    showLocalShelves(localShelves) {
      this.shelves = localShelves
      this.catalogNotLoaded = false
      this.replicaIncomplete = false
      this.shownFor = null
    },
    /** A download finished or a downloaded copy was deleted: the copies joined to the shelves change. */
    localItemsChanged() {
      return this.fetchCategories({ force: true })
    },
    libraryChanged() {
      if (this.currentLibraryId) {
        console.log(`[categories] libraryChanged so fetching categories`)
        this.fetchCategories()
      }
    },
    /** A change native reported that may change the home: one re-read a second after the last such notice. */
    dataChanged(change) {
      const playingEpisodeId = this.$store.state.currentPlaybackSession?.episodeId || null
      const continueKeys = continueKeysOf(this.shelves)
      const context = `${continueKeys[0] ?? ''}|${playingEpisodeId ?? ''}`
      const refreshedForKey = this.refreshedFor?.context === context ? this.refreshedFor.key : null
      if (!shouldRefreshHome(change, { libraryId: this.currentLibraryId, continueKeys, playingEpisodeId, refreshedForKey })) return
      if (change.scope === 'playback') {
        const keyOf = (id) => (playingEpisodeId ? `${id}:${playingEpisodeId}` : id)
        const id = (change.ids || []).find((i) => keyOf(i) !== continueKeys[0] && keyOf(i) !== refreshedForKey)
        this.refreshedFor = { key: keyOf(id), context }
      }
      if (!this.refreshDebouncer) this.refreshDebouncer = createDebouncer({ delayMs: 1000 })
      this.refreshDebouncer.schedule(() => this.fetchCategories({ force: true }))
    },
    initListeners() {
      this.$eventBus.$on('library-changed', this.libraryChanged)
      this.$eventBus.$on('server-reachable', this.libraryChanged)
      this.$eventBus.$on('new-local-library-item', this.localItemsChanged)
      this.$eventBus.$on('local-library-items-changed', this.localItemsChanged)
    },
    removeListeners() {
      this.$eventBus.$off('library-changed', this.libraryChanged)
      this.$eventBus.$off('server-reachable', this.libraryChanged)
      this.$eventBus.$off('new-local-library-item', this.localItemsChanged)
      this.$eventBus.$off('local-library-items-changed', this.localItemsChanged)
    }
  },
  async mounted() {
    if (this.$route.query.error) {
      this.$toast.error(this.$route.query.error)
    }

    this.initListeners()
    this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
    await this.$store.dispatch('globals/loadLocalMediaProgress')
    console.log(`[categories] mounted so fetching categories`)
    this.fetchCategories()
  },
  beforeDestroy() {
    this.removeListeners()
    if (this.stopDataChanged) this.stopDataChanged()
    this.stopDataChanged = null
    if (this.refreshDebouncer) this.refreshDebouncer.cancel()
  }
}
</script>
