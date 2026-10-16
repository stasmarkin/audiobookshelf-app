<template>
  <div class="w-full h-full">
    <div class="px-4 py-6">
      <ui-text-input ref="input" v-model="search" @input="updateSearch" borderless :placeholder="$strings.ButtonSearch" bg="white bg-opacity-10" rounded="md" prepend-icon="search" text-size="base" clearable class="w-full text-lg" />
    </div>
    <div class="w-full overflow-x-hidden overflow-y-auto search-content px-4" @click.stop>
      <div v-if="showCatalogPartial" class="catalog-partial sticky top-0 z-40 w-full px-3 text-center text-xs text-warning bg-bg/90" style="height: 24px; line-height: 24px">{{ $strings.MessageCatalogPartial }}</div>
      <div v-show="isFetching" class="w-full py-8 flex justify-center">
        <p class="text-lg text-fg-muted">{{ $strings.MessageFetching }}</p>
      </div>
      <div v-if="!isFetching && lastSearch && !totalResults" class="w-full py-8 flex justify-center">
        <p class="text-lg text-fg-muted text-center">{{ catalogNotLoaded ? $strings.MessageCatalogNotLoaded : $strings.MessageNoItemsFound }}</p>
      </div>
      <p v-if="bookResults.length" class="search-section-label font-semibold text-sm mb-1">{{ $strings.LabelBooks }}</p>
      <template v-for="item in bookResults">
        <div :key="item.libraryItem.id" class="w-full h-16 py-1">
          <nuxt-link v-if="!isUnavailable(item)" :to="itemLink(item)">
            <cards-item-search-card :library-item="item.libraryItem" :local-library-item="item.localLibraryItem" :search="lastSearch" />
          </nuxt-link>
          <div v-else role="link" tabindex="0" @click="showUnavailable" @keydown.enter="showUnavailable">
            <cards-item-search-card :library-item="item.libraryItem" :local-library-item="item.localLibraryItem" :search="lastSearch" />
          </div>
        </div>
      </template>

      <p v-if="podcastResults.length" class="search-section-label font-semibold text-sm mb-1 mt-2">{{ $strings.LabelPodcasts }}</p>
      <template v-for="item in podcastResults">
        <div :key="item.libraryItem.id" class="text-fg select-none relative py-1">
          <nuxt-link v-if="!isUnavailable(item)" :to="itemLink(item)">
            <cards-item-search-card :library-item="item.libraryItem" :local-library-item="item.localLibraryItem" :search="lastSearch" />
          </nuxt-link>
          <div v-else role="link" tabindex="0" @click="showUnavailable" @keydown.enter="showUnavailable">
            <cards-item-search-card :library-item="item.libraryItem" :local-library-item="item.localLibraryItem" :search="lastSearch" />
          </div>
        </div>
      </template>

      <p v-if="episodeResults.length" class="search-section-label font-semibold text-sm mb-1 mt-2">{{ $strings.HeaderEpisodes }}</p>
      <template v-for="item in episodeResults">
        <div :key="item.libraryItem.recentEpisode.id" class="text-fg select-none relative py-1">
          <nuxt-link v-if="!isUnavailable(item)" :to="episodeLink(item)">
            <cards-episode-search-card :episode="item.libraryItem.recentEpisode" :library-item="item.libraryItem" :local-episode="item.localEpisode" />
          </nuxt-link>
          <div v-else role="link" tabindex="0" @click="showUnavailable" @keydown.enter="showUnavailable">
            <cards-episode-search-card :episode="item.libraryItem.recentEpisode" :library-item="item.libraryItem" :local-episode="item.localEpisode" />
          </div>
        </div>
      </template>

      <p v-if="seriesResults.length" class="search-section-label font-semibold text-sm mb-1 mt-2">{{ $strings.LabelSeries }}</p>
      <template v-for="seriesResult in seriesResults">
        <div :key="seriesResult.series.id" class="w-full h-16 py-1">
          <nuxt-link :to="`/bookshelf/series/${seriesResult.series.id}`">
            <cards-series-search-card :series="seriesResult.series" :book-items="seriesResult.books" />
          </nuxt-link>
        </div>
      </template>

      <p v-if="authorResults.length" class="search-section-label font-semibold text-sm mb-1 mt-2">{{ $strings.LabelAuthors }}</p>
      <template v-for="authorResult in authorResults">
        <div :key="authorResult.id" class="w-full h-14 py-1">
          <nuxt-link :to="`/bookshelf/library?filter=authors.${$encode(authorResult.id)}`">
            <cards-author-search-card :author="authorResult" />
          </nuxt-link>
        </div>
      </template>

      <p v-if="narratorResults.length" class="search-section-label font-semibold text-sm mb-1 mt-2">{{ $strings.LabelNarrators }}</p>
      <template v-for="narrator in narratorResults">
        <div :key="narrator.name" class="w-full h-14 py-1">
          <nuxt-link :to="`/bookshelf/library?filter=narrators.${$encode(narrator.name)}`">
            <cards-narrator-search-card :narrator="narrator.name" />
          </nuxt-link>
        </div>
      </template>

      <p v-if="tagResults.length" class="search-section-label font-semibold text-sm mb-1 mt-2">{{ $strings.LabelTags }}</p>
      <template v-for="tag in tagResults">
        <div :key="tag.name" class="w-full h-14 py-1">
          <nuxt-link :to="`/bookshelf/library?filter=tags.${$encode(tag.name)}`">
            <cards-tag-search-card :tag="tag.name" />
          </nuxt-link>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import { findLocalCopy } from '@/utils/localCopy'
import { createDebouncer, isReplicaIncomplete } from '@/utils/catalogRefresh'

/** Catalog topics after which an open search is run again (so it moves from the server to the replica). */
const SEARCH_TOPICS = ['state', 'items', 'series', 'authors', 'episodes']

export default {
  data() {
    return {
      search: null,
      searchTimeout: null,
      lastSearch: null,
      isFetching: false,
      // Only the answer of the latest request is shown and clears isFetching
      searchSeq: 0,
      catalogNotLoaded: false,
      // Results of a library whose reconcile never finished (or was unstable)
      replicaIncomplete: false,
      // The groups of the shown answer, before the downloaded copies are joined
      answerResults: {},
      // The downloaded items, read once for the open page and again when a download finishes or a copy is deleted
      localItems: null,
      // Bumped when the cache is dropped: a read that started before must not fill it
      localItemsGeneration: 0,
      refreshDebouncer: null,
      stopDataChanged: null,
      bookResults: [],
      podcastResults: [],
      episodeResults: [],
      seriesResults: [],
      authorResults: [],
      narratorResults: [],
      tagResults: []
    }
  },
  watch: {
    currentLibraryId() {
      this.libraryOrServerChanged()
    },
    activeServerConfigId() {
      this.libraryOrServerChanged()
    }
  },
  computed: {
    /** Results from an unfinished catalog copy carry the same note as the catalog tabs while the server is away. */
    showCatalogPartial() {
      return this.replicaIncomplete && this.$store.state.serverState !== 'online'
    },
    activeServerConfigId() {
      return this.$store.state.activeServerConfigId ?? null
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    totalResults() {
      return this.bookResults.length + this.seriesResults.length + this.authorResults.length + this.podcastResults.length + this.narratorResults.length + this.tagResults.length + this.episodeResults.length
    }
  },
  methods: {
    clearResults() {
      this.bookResults = []
      this.podcastResults = []
      this.episodeResults = []
      this.seriesResults = []
      this.authorResults = []
      this.narratorResults = []
      this.tagResults = []
      this.answerResults = {}
      this.catalogNotLoaded = false
      this.replicaIncomplete = false
    },
    /**
     * `silent`: a re-run for a catalog change, which keeps the shown results and shows no "Fetching" until it answers.
     */
    async runSearch(value, { silent = false } = {}) {
      const seq = ++this.searchSeq
      this.lastSearch = value
      this.$store.commit('globals/setLastSearch', value)

      const q = (value || '').trim()
      if (!q) {
        this.clearResults()
        this.isFetching = false
        return
      }
      if (!silent) this.isFetching = true
      const libraryId = this.currentLibraryId
      const [answer] = await Promise.all([
        this.$absData.search({ libraryId, q }).catch((error) => {
          console.error('Search error', error)
          return null
        }),
        this.loadLocalItems()
      ])
      if (seq !== this.searchSeq) return
      this.isFetching = false
      // Asked for the library or server that was current before a switch: none of it belongs here
      if (answer && (answer.libraryId !== this.currentLibraryId || (answer.serverConfigId ?? null) !== this.activeServerConfigId)) {
        this.clearResults()
        return
      }
      this.answerResults = answer?.results || {}
      this.showResults()
      this.catalogNotLoaded = !!answer && answer.replica?.reconciledAt == null && !answer.fromServer && !this.totalResults
      // Nothing found says "catalog not loaded" (or "no items") instead
      this.replicaIncomplete = isReplicaIncomplete({ ...answer, total: this.totalResults })
    },
    async loadLocalItems() {
      if (this.localItems !== null) return this.localItems
      const generation = this.localItemsGeneration
      const items = await this.$db.getLocalLibraryItems().catch(() => [])
      if (generation === this.localItemsGeneration) this.localItems = items
      return this.localItems || []
    },
    /** The groups of the shown answer with the downloaded copies of the active server joined. */
    showResults() {
      const results = this.answerResults
      const localItems = this.localItems || []
      const withCopy = (item) => ({ ...item, localLibraryItem: findLocalCopy(localItems, item.libraryItem, this.activeServerConfigId) })
      const withEpisode = (item) => {
        const copy = withCopy(item)
        const episodeId = item.libraryItem?.recentEpisode?.id
        const localEpisode = (copy.localLibraryItem?.media?.episodes || []).find((ep) => ep.serverEpisodeId === episodeId) || null
        return { ...copy, localEpisode }
      }
      this.bookResults = (results.book || []).map(withCopy)
      this.podcastResults = (results.podcast || []).map(withCopy)
      this.episodeResults = (results.episodes || []).map(withEpisode)
      this.seriesResults = results.series || []
      this.authorResults = results.authors || []
      this.narratorResults = results.narrators || []
      this.tagResults = results.tags || []
    },
    /** A download finished or a downloaded copy was deleted: the copies joined to the results change. */
    async localItemsChanged() {
      this.localItems = null
      this.localItemsGeneration++
      await this.loadLocalItems()
      this.showResults()
    },
    /** Results of another library or server never stay: the open query runs again for the current one. */
    libraryOrServerChanged() {
      this.clearResults()
      if ((this.lastSearch || '').trim()) this.runSearch(this.lastSearch)
      else this.searchSeq++
    },
    /** Offline, a result without a downloaded copy (an episode: without this episode downloaded) opens nothing. */
    isUnavailable(item) {
      if (!['offline', 'unauthorized'].includes(this.$store.state.serverState)) return false
      return item.libraryItem?.recentEpisode ? !item.localEpisode : !item.localLibraryItem
    },
    showUnavailable() {
      this.$toast?.info(this.$strings.LabelNotAvailableOffline)
    },
    /** A downloaded copy goes along, so the item page can open it without the server. */
    itemLink(item) {
      const id = item.libraryItem.id
      return item.localLibraryItem ? `/item/${id}?localLibraryItemId=${item.localLibraryItem.id}` : `/item/${id}`
    },
    /** Without the server the episode page reads only a downloaded copy, by its local ids. */
    episodeLink(item) {
      const readsLocal = !this.$store.state.user.serverConnectionConfig || this.$store.state.serverState !== 'online'
      if (item.localEpisode && readsLocal) return `/item/${item.localLibraryItem.id}/${item.localEpisode.id}`
      return `/item/${item.libraryItem.id}/${item.libraryItem.recentEpisode.id}`
    },
    /** The catalog of the current library changed: the open search is run again a second after the last notice. */
    dataChanged(change) {
      if (change?.scope !== 'catalog' || !this.currentLibraryId || !(this.lastSearch || '').trim()) return
      const ids = change.ids || []
      if (!SEARCH_TOPICS.some((topic) => ids.includes(`${topic}:${this.currentLibraryId}`))) return
      if (!this.refreshDebouncer) this.refreshDebouncer = createDebouncer({ delayMs: 1000 })
      this.refreshDebouncer.schedule(() => this.runSearch(this.lastSearch, { silent: true }))
    },
    updateSearch(val) {
      clearTimeout(this.searchTimeout)
      this.searchTimeout = setTimeout(() => {
        this.runSearch(val)
      }, 500)
    },
    setFocus() {
      setTimeout(() => {
        if (this.$refs.input) {
          this.$refs.input.focus()
        }
      }, 100)
    }
  },
  mounted() {
    this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
    this.$eventBus.$on('new-local-library-item', this.localItemsChanged)
    this.$eventBus.$on('local-library-items-changed', this.localItemsChanged)
    if (this.$store.state.globals.lastSearch) {
      this.search = this.$store.state.globals.lastSearch
      this.runSearch(this.search)
    } else {
      this.$nextTick(this.setFocus())
    }
  },
  beforeDestroy() {
    clearTimeout(this.searchTimeout)
    this.$eventBus.$off('new-local-library-item', this.localItemsChanged)
    this.$eventBus.$off('local-library-items-changed', this.localItemsChanged)
    if (this.stopDataChanged) this.stopDataChanged()
    this.stopDataChanged = null
    if (this.refreshDebouncer) this.refreshDebouncer.cancel()
  }
}
</script>

<style>
.search-content {
  height: calc(100% - 108px);
  max-height: calc(100% - 108px);
}
</style>
