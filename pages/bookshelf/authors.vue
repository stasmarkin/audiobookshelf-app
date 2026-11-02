<template>
  <div>
    <div v-if="showCatalogPartial" class="catalog-partial sticky top-0 z-40 w-full px-3 text-center text-xs text-warning bg-bg/90" style="height: 24px; line-height: 24px">{{ $strings.MessageCatalogPartial }}</div>
    <div id="bookshelf" class="w-full h-full p-4 overflow-y-auto">
      <div v-if="catalogNotLoaded" class="w-full py-16 px-6 text-center text-lg">
        <p class="py-4">{{ $strings.MessageCatalogNotLoaded }}</p>
      </div>
      <div v-else class="flex flex-wrap justify-center">
        <template v-for="author in authors">
          <cards-author-card :key="author.id" :author="author" :width="cardWidth" :height="cardHeight" class="p-2" />
        </template>
      </div>
    </div>
  </div>
</template>

<script>
import { shouldRefreshShelf, createDebouncer, isReplicaIncomplete } from '@/utils/catalogRefresh'

/** Reads from the bridge are capped at 100 entities a call. */
const PAGE_SIZE = 100

export default {
  data() {
    return {
      loading: true,
      authors: [],
      loadedLibraryId: null,
      loadedServerConfigId: null,
      catalogNotLoaded: false,
      replicaIncomplete: false,
      loadGeneration: 0,
      cardWidth: 200
    }
  },
  computed: {
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    activeServerConfigId() {
      return this.$store.state.activeServerConfigId
    },
    canLoad() {
      return !!this.$store.getters.getHasActiveServer
    },
    showCatalogPartial() {
      return this.replicaIncomplete && this.$store.state.serverState !== 'online'
    },
    cardHeight() {
      return this.cardWidth * 1.25
    }
  },
  methods: {
    /** Reads every page of the library's authors, showing each as it comes; a newer load drops this one. */
    async init() {
      this.cardWidth = (window.innerWidth - 64) / 2
      const generation = ++this.loadGeneration
      const libraryId = this.currentLibraryId
      const serverConfigId = this.activeServerConfigId
      if (!this.canLoad) {
        this.$eventBus.$emit('bookshelf-total-entities', 0)
        this.loading = false
        return
      }
      if (!libraryId) {
        // The saved server has no library on the device yet: its catalog was never downloaded
        this.authors = []
        this.catalogNotLoaded = true
        this.$eventBus.$emit('bookshelf-total-entities', null)
        this.loading = false
        return
      }
      if (libraryId !== this.loadedLibraryId || serverConfigId !== this.loadedServerConfigId) this.authors = []
      this.loadedLibraryId = libraryId
      this.loadedServerConfigId = serverConfigId
      const stale = (answer) => generation !== this.loadGeneration || libraryId !== this.currentLibraryId || (!!answer && (answer.serverConfigId ?? null) !== (this.activeServerConfigId ?? null))

      const collected = []
      let total = 0
      try {
        for (let page = 0; ; page++) {
          const answer = await this.$absData.getAuthors({ libraryId, limit: PAGE_SIZE, page })
          if (stale(answer)) return
          const results = answer?.results || []
          total = answer?.total || 0
          if (page === 0) {
            this.catalogNotLoaded = answer?.replica?.reconciledAt == null && !total && !answer?.fromServer
            this.replicaIncomplete = isReplicaIncomplete(answer)
          }
          collected.push(...results)
          // A re-read replaces the list in place rather than emptying it first
          const ids = new Set(collected.map((author) => author.id))
          this.authors = collected.concat(this.authors.slice(collected.length).filter((author) => !ids.has(author.id)))
          if (!results.length || collected.length >= total) break
        }
        this.authors = collected
        this.$eventBus.$emit('bookshelf-total-entities', this.catalogNotLoaded ? null : total)
      } catch (error) {
        if (!stale()) console.error('Failed to load authors', error)
      } finally {
        if (generation === this.loadGeneration) this.loading = false
      }
    },
    /** Native's catalog notice for this library's authors: read them again. */
    dataChanged(change) {
      if (!shouldRefreshShelf(change, { entity: 'authors', libraryId: this.currentLibraryId })) return
      if (!this.refreshDebouncer) this.refreshDebouncer = createDebouncer({ delayMs: 1000 })
      this.refreshDebouncer.schedule(this.init)
    },
    libraryChanged(libraryId) {
      if (libraryId !== this.loadedLibraryId) {
        if (this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'book') {
          this.init()
        } else {
          this.$router.replace('/bookshelf')
        }
      }
    }
  },
  mounted() {
    this.init()
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    if (this.stopDataChanged) this.stopDataChanged()
    if (this.refreshDebouncer) this.refreshDebouncer.cancel()
    // Answers still on the way are dropped
    this.loadGeneration++
  }
}
</script>
