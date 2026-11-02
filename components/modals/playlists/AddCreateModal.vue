<template>
  <modals-fullscreen-modal v-model="show" :processing="processing">
    <div class="flex items-end justify-between h-24 px-4 pb-2">
      <h1 class="text-lg">{{ $strings.LabelAddToPlaylist }}</h1>
      <button class="flex" @click="show = false">
        <span class="material-symbols">close</span>
      </button>
    </div>

    <!-- create new playlist form -->
    <div v-if="showPlaylistNameInput" class="w-full h-full max-h-[calc(100vh-176px)] flex items-center">
      <div class="w-full px-4">
        <div class="flex mb-4 items-center">
          <div class="w-9 h-9 flex items-center justify-center rounded-full cursor-pointer" @click.stop="showPlaylistNameInput = false">
            <span class="material-symbols text-3xl">arrow_back</span>
          </div>
          <p class="text-xl pl-2 leading-none">{{ $strings.HeaderNewPlaylist }}</p>
          <div class="flex-grow" />
        </div>

        <ui-text-input-with-label v-model="newPlaylistName" :label="$strings.LabelName" />
        <div class="flex justify-end mt-6">
          <ui-btn color="success" :loading="processing" :disabled="!isServerOnline" class="w-full" @click.stop="submitCreatePlaylist">{{ $strings.ButtonCreate }}</ui-btn>
        </div>
        <p v-if="!isServerOnline" class="requires-server-label text-sm text-fg-muted text-center mt-2">{{ requiresServerLabel() }}</p>
      </div>
    </div>

    <!-- playlists list -->
    <div v-if="!showPlaylistNameInput" class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(100vh-176px)]">
      <div class="w-full h-full" v-show="!showPlaylistNameInput">
        <template v-for="playlist in sortedPlaylists">
          <modals-playlists-playlist-row :key="playlist.id" :in-playlist="playlist.isItemIncluded" :playlist="playlist" :disabled="!isServerOnline" @click="clickPlaylist" @close="show = false" />
        </template>
        <div v-if="!playlists.length" class="flex h-full items-center justify-center">
          <p class="text-xl">{{ loading ? $strings.MessageLoading : $strings.MessageNoUserPlaylists }}</p>
        </div>
      </div>
    </div>

    <!-- create playlist btn -->
    <div v-if="!showPlaylistNameInput" class="flex flex-col items-stretch min-h-20 pt-2 pb-2 absolute bottom-0 left-0 w-full">
      <ui-btn :loading="processing" :disabled="!isServerOnline" color="success" class="w-full h-14 flex items-center justify-center" @click.stop="createPlaylist">
        <p class="text-base">{{ $strings.ButtonCreateNewPlaylist }}</p>
      </ui-btn>
      <!-- Under the button: inside it the label ran into the button text -->
      <p v-if="!isServerOnline" class="requires-server-label text-xs text-fg-muted text-center mt-1">{{ requiresServerLabel() }}</p>
    </div>
  </modals-fullscreen-modal>
</template>

<script>
import requiresServer from '@/mixins/requiresServer'
import { playlistListEntry } from '@/utils/dataService'

/** `$absData` lists at most 20 playlists a call (each carries its cover items). */
const PLAYLISTS_PAGE = 20

/** Adding, removing and creating write to the server: offline the list shows, the writes are disabled and labeled. */
export default {
  mixins: [requiresServer],
  props: {
    libraryItemId: String
  },
  data() {
    return {
      showPlaylistNameInput: false,
      newPlaylistName: '',
      playlists: [],
      processing: false,
      loading: false,
      loadGeneration: 0,
      /** Playlists the server answered a write with, by id (null: deleted), until a list read shows the same. */
      writes: {}
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.writes = {}
        this.setListeners()
        this.showPlaylistNameInput = false
        this.newPlaylistName = ''
        this.loadPlaylists()
      } else {
        this.unsetListeners()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.globals.showPlaylistsAddCreateModal
      },
      set(val) {
        this.$store.commit('globals/setShowPlaylistsAddCreateModal', val)
      }
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    selectedPlaylistItems() {
      return this.$store.state.globals.selectedPlaylistItems || []
    },
    sortedPlaylists() {
      return this.playlists
        .map((playlist) => {
          const includesItem = !this.selectedPlaylistItems.some((item) => !this.checkIsItemInPlaylist(playlist, item))

          return {
            isItemIncluded: includesItem,
            ...playlist
          }
        })
        .sort((a, b) => (a.isItemIncluded ? -1 : 1))
    }
  },
  methods: {
    /** By `itemKeys`: every item of the playlist, while `items` holds only the first few for the cover. */
    checkIsItemInPlaylist(playlist, item) {
      const keys = playlist.itemKeys || playlist.items || []
      if (item.episode) {
        return keys.some((i) => i.libraryItemId === item.libraryItem.id && i.episodeId === item.episode.id)
      }
      return keys.some((i) => i.libraryItemId === item.libraryItem.id)
    },
    /** Every playlist of the library, a page at a time; only the latest read lands. */
    async loadPlaylists() {
      const generation = ++this.loadGeneration
      const libraryId = this.currentLibraryId
      this.loading = true
      const collected = new Map()
      try {
        for (let page = 0; ; page++) {
          const answer = await this.$absData.getPlaylists({ libraryId, limit: PLAYLISTS_PAGE, page })
          if (generation !== this.loadGeneration) return
          const results = answer?.results || []
          results.forEach((playlist) => collected.set(playlist.id, playlist))
          if (!results.length || (page + 1) * PLAYLISTS_PAGE >= (answer?.total || 0)) break
        }
        this.playlists = this.withWrites([...collected.values()], true)
      } catch (error) {
        if (generation !== this.loadGeneration) return
        console.error('Failed', error)
        this.$toast.error('Failed to load playlists')
      } finally {
        if (generation === this.loadGeneration) this.loading = false
      }
    },
    async clickPlaylist(playlist) {
      if (!this.isServerOnline) return
      await this.$hapticsImpact()
      if (playlist.isItemIncluded) {
        this.removeFromPlaylist(playlist)
      } else {
        this.addToPlaylist(playlist)
      }
    },
    removeFromPlaylist(playlist) {
      if (!this.isServerOnline || !this.selectedPlaylistItems.length) return
      this.processing = true

      const itemObjects = this.selectedPlaylistItems.map((pi) => ({ libraryItemId: pi.libraryItem.id, episodeId: pi.episode ? pi.episode.id : null }))
      this.$nativeHttp
        .post(`/api/playlists/${playlist.id}/batch/remove`, { items: itemObjects })
        .then((updatedPlaylist) => {
          console.log(`Items removed from playlist`, updatedPlaylist)
          this.applyServerPlaylist(updatedPlaylist)
        })
        .catch((error) => {
          console.error('Failed to remove items from playlist', error)
          this.$toast.error('Failed to remove playlist item(s)')
        })
        .finally(() => {
          this.processing = false
        })
    },
    addToPlaylist(playlist) {
      if (!this.isServerOnline || !this.selectedPlaylistItems.length) return
      this.processing = true

      const itemObjects = this.selectedPlaylistItems.map((pi) => ({ libraryItemId: pi.libraryItem.id, episodeId: pi.episode ? pi.episode.id : null }))
      this.$nativeHttp
        .post(`/api/playlists/${playlist.id}/batch/add`, { items: itemObjects })
        .then((updatedPlaylist) => {
          console.log(`Items added to playlist`, updatedPlaylist)
          this.applyServerPlaylist(updatedPlaylist)
        })
        .catch((error) => {
          console.error('Failed to add items to playlist', error)
          this.$toast.error('Failed to add items to playlist')
        })
        .finally(() => {
          this.processing = false
        })
    },
    createPlaylist() {
      if (!this.isServerOnline) return
      this.newPlaylistName = ''
      this.showPlaylistNameInput = true
    },
    async submitCreatePlaylist() {
      if (!this.isServerOnline) return
      await this.$hapticsImpact()
      if (!this.newPlaylistName || !this.selectedPlaylistItems.length) {
        return
      }
      this.processing = true

      const itemObjects = this.selectedPlaylistItems.map((pi) => ({ libraryItemId: pi.libraryItem.id, episodeId: pi.episode ? pi.episode.id : null }))
      const newPlaylist = {
        items: itemObjects,
        libraryId: this.currentLibraryId,
        name: this.newPlaylistName
      }

      this.$nativeHttp
        .post('/api/playlists', newPlaylist)
        .then((data) => {
          console.log('New playlist created', data)
          this.newPlaylistName = ''
          this.showPlaylistNameInput = false
          this.applyServerPlaylist(data)
          // The Playlists tab shows at once, before the catalog notice
          if (!this.$store.state.libraries.numUserPlaylists) this.$store.commit('libraries/setNumUserPlaylists', 1)
        })
        .catch((error) => {
          console.error('Failed to create playlist', error)
          this.$toast.error(this.$strings.ToastPlaylistCreateFailed)
        })
        .finally(() => {
          this.processing = false
        })
    },
    /**
     * The server's answer to a write (the expanded playlist) right away: during a catalog pass native's notice comes only
     * after it. A playlist left without items was deleted by the server.
     */
    applyServerPlaylist(playlist) {
      if (!playlist?.id) return
      const entry = playlistListEntry(playlist)
      // A read that started before the write (or before the replica took it) would put the old playlist back
      this.writes = { ...this.writes, [entry.id]: entry.numItems ? entry : null }
      this.playlists = this.withWrites(this.playlists)
    },
    /** [list] with the writes over it; a list read ([fromRead]) that already shows a write ends it. */
    withWrites(list, fromRead = false) {
      const same = (a, b) => !!a && a.name === b.name && JSON.stringify(a.itemKeys) === JSON.stringify(b.itemKeys)
      const out = [...list]
      const pending = {}
      for (const [id, written] of Object.entries(this.writes)) {
        const index = out.findIndex((p) => p.id === id)
        const shown = index >= 0 ? out[index] : null
        if (written ? same(shown, written) : !shown) continue
        pending[id] = written
        if (!written) out.splice(index, 1)
        else if (index >= 0) out.splice(index, 1, written)
        else out.push(written)
      }
      if (fromRead) this.writes = pending
      return out
    },
    /** Native changed this library's playlists (an add, a remove, a new one): read the list again while open. */
    dataChanged(change) {
      if (change?.scope !== 'catalog' || !(change.ids || []).includes(`playlists:${this.currentLibraryId}`)) return
      this.loadPlaylists()
    },
    setListeners() {
      if (!this.stopDataChanged) this.stopDataChanged = this.$absData.onDataChanged(this.dataChanged)
    },
    unsetListeners() {
      if (this.stopDataChanged) this.stopDataChanged()
      this.stopDataChanged = null
      // A read still running lands nowhere
      this.loadGeneration++
    }
  },
  beforeDestroy() {
    this.unsetListeners()
  }
}
</script>
