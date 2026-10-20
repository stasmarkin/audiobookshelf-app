<template>
  <div class="w-full p-4">
    <h1 class="text-xl mb-2 font-semibold">{{ $strings.HeaderLatestEpisodes }}</h1>

    <p v-if="catalogNotLoaded" class="text-fg-muted text-center py-6">{{ $strings.MessageCatalogNotLoaded }}</p>
    <template v-for="episode in recentEpisodes">
      <tables-podcast-latest-episode-row :episode="episode" :local-episode="localEpisodeMap[episode.id]" :library-item-id="episode.libraryItemId" :local-library-item-id="localEpisodeMap[episode.id]?.localLibraryItemId" :key="episode.id" @addToPlaylist="addEpisodeToPlaylist" />
    </template>
  </div>
</template>

<script>
import requiresServer from '@/mixins/requiresServer'
import { createDebouncer } from '@/utils/catalogRefresh'

const PAGE_SIZE = 50

export default {
  mixins: [requiresServer],
  data() {
    return {
      processing: false,
      recentEpisodes: [],
      // null: the server's list does not say how many there are
      totalEpisodes: null,
      currentPage: 0,
      catalogNotLoaded: false,
      localLibraryItems: [],
      loadedLibraryId: null,
      stopDataChanged: null,
      refreshDebouncer: null
    }
  },
  watch: {},
  computed: {
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    localEpisodes() {
      const episodes = []
      const activeServerConfigId = this.$store.state.activeServerConfigId
      this.localLibraryItems.forEach((li) => {
        // Replicas of two accounts on one server share episode ids: only the active server's copies count
        if (activeServerConfigId && li.serverConnectionConfigId !== activeServerConfigId) return
        if (li.isInvalid) return
        if (li.media.episodes?.length) {
          li.media.episodes.map((ep) => {
            ep.localLibraryItemId = li.id
            episodes.push(ep)
          })
        }
      })
      return episodes
    },
    localEpisodeMap() {
      var epmap = {}
      this.localEpisodes.forEach((localEp) => {
        if (localEp.serverEpisodeId) {
          epmap[localEp.serverEpisodeId] = localEp
        }
      })
      return epmap
    }
  },
  methods: {
    async addEpisodeToPlaylist(episode) {
      if (!this.isServerOnline) return
      const answer = await this.$absData.getLibraryItem({ id: episode.libraryItemId }).catch((error) => {
        console.error('Failed to get library item', error)
        return null
      })
      const libraryItem = answer?.libraryItem
      if (!libraryItem) {
        this.$toast.error('Failed to get library item')
        return
      }

      this.$store.commit('globals/setSelectedPlaylistItems', [{ libraryItem, episode }])
      this.$store.commit('globals/setShowPlaylistsAddCreateModal', true)
    },
    async loadRecentEpisodes(page = 0) {
      const libraryId = this.currentLibraryId
      this.loadedLibraryId = libraryId
      this.processing = true
      const answer = await this.$absData.getRecentEpisodes({ libraryId, limit: PAGE_SIZE, page }).catch((error) => {
        console.error('Failed to get recent episodes', error)
        this.$toast.error('Failed to get recent episodes')
        return null
      })
      this.processing = false
      // Another library was chosen while this one was read
      if (libraryId !== this.currentLibraryId) return
      this.recentEpisodes = answer?.episodes || []
      this.totalEpisodes = answer?.total ?? null
      this.currentPage = page
      this.catalogNotLoaded = !!answer && answer.replica?.reconciledAt == null && !answer.fromServer && !this.recentEpisodes.length
    },
    /** Native changed this library's episodes or podcasts: read the shown page again, a second after the last notice. */
    dataChanged(change) {
      const libraryId = this.loadedLibraryId
      if (change?.scope !== 'catalog' || !libraryId) return
      if (![`episodes:${libraryId}`, `items:${libraryId}`].some((topic) => (change.ids || []).includes(topic))) return
      if (!this.refreshDebouncer) this.refreshDebouncer = createDebouncer({ delayMs: 1000 })
      this.refreshDebouncer.schedule(() => this.loadRecentEpisodes(this.currentPage))
    },
    libraryChanged(libraryId) {
      if (libraryId !== this.loadedLibraryId) {
        if (this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'podcast') {
          this.loadRecentEpisodes()
        } else {
          this.$router.replace('/bookshelf')
        }
      }
    },
    async loadLocalPodcastLibraryItems() {
      this.localLibraryItems = await this.$db.getLocalLibraryItems('podcast')
    },
    newLocalLibraryItem(item) {
      if (item.mediaType !== 'podcast') {
        return
      }
      const matchingLocalLibraryItem = this.localLibraryItems.find((lli) => lli.id === item.id)
      if (matchingLocalLibraryItem) {
        matchingLocalLibraryItem.media.episodes = item.media.episodes
      } else {
        this.localLibraryItems.push(item)
      }
    }
  },
  mounted() {
    this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
    this.loadRecentEpisodes()
    this.loadLocalPodcastLibraryItems()
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.$eventBus.$on('local-library-items-changed', this.loadLocalPodcastLibraryItems)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    this.$eventBus.$off('local-library-items-changed', this.loadLocalPodcastLibraryItems)
    if (this.stopDataChanged) this.stopDataChanged()
    this.stopDataChanged = null
    if (this.refreshDebouncer) this.refreshDebouncer.cancel()
  }
}
</script>
