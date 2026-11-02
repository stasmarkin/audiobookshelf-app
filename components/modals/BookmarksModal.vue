<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <template #outer>
      <div class="absolute top-11 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.LabelYourBookmarks }}</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full rounded-lg bg-primary border border-border overflow-y-auto overflow-x-hidden relative mt-16" style="max-height: 80vh" @click.stop.prevent>
        <div class="w-full h-full p-4" v-if="showBookmarkTitleInput">
          <div class="flex mb-4 items-center">
            <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="showBookmarkTitleInput = false">
              <span class="material-symbols text-3xl">arrow_back</span>
            </div>
            <p class="text-xl pl-2">{{ selectedBookmark ? 'Edit Bookmark' : 'New Bookmark' }}</p>
            <div class="flex-grow" />
            <p class="text-xl font-mono">{{ this.$secondsToTimestamp(currentTime / _playbackRate) }}</p>
          </div>

          <ui-text-input-with-label v-model="newBookmarkTitle" :placeholder="bookmarkPlaceholder()" :autofocus="false" ref="noteInput" label="Note" />
          <div class="flex justify-end mt-6">
            <ui-btn color="success" class="w-full" @click.stop="submitBookmark">{{ selectedBookmark ? 'Update' : 'Create' }}</ui-btn>
          </div>
        </div>
        <div class="bookmarks-list w-full h-full" v-else>
          <template v-for="bookmark in shownBookmarks">
            <modals-bookmarks-bookmark-item :key="bookmark.id" :highlight="currentTime === bookmark.time" :bookmark="bookmark" :playback-rate="_playbackRate" @click="clickBookmark" @edit="editBookmark" @delete="deleteBookmark" />
          </template>
          <div v-if="!shownBookmarks.length" class="flex h-32 items-center justify-center">
            <p class="text-xl">{{ $strings.MessageNoBookmarks }}</p>
          </div>
        </div>
        <div v-if="canCreateBookmark && !showBookmarkTitleInput" class="flex px-4 py-2 items-center text-center justify-between border-b border-fg/10 bg-success-action cursor-pointer text-white sticky bottom-0 left-0 w-full" @click.stop="createBookmark">
          <span class="material-symbols">add</span>
          <p class="text-base pl-2">{{ $strings.ButtonCreateBookmark }}</p>
          <p class="text-sm font-mono">{{ this.$secondsToTimestamp(currentTime / _playbackRate) }}</p>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  props: {
    value: Boolean,
    bookmarks: {
      type: Array,
      default: () => []
    },
    currentTime: {
      type: Number,
      default: 0
    },
    playbackRate: {
      type: Number,
      default: 1
    },
    libraryItemId: String,
    // The server of the downloaded copy that is playing, when it is not the active one; null writes for the active server
    serverConfigId: {
      type: String,
      default: null
    }
  },
  data() {
    return {
      selectedBookmark: null,
      showBookmarkTitleInput: false,
      newBookmarkTitle: '',
      copyBookmarks: [],
      stopListening: null
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.showBookmarkTitleInput = false
        this.newBookmarkTitle = ''
        this.loadCopyBookmarks()
      }
    },
    serverConfigId() {
      this.loadCopyBookmarks()
    },
    libraryItemId() {
      this.loadCopyBookmarks()
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    // A downloaded copy of another saved server: its bookmarks are not the active server's list in the store
    foreignServer() {
      return !!this.serverConfigId && this.serverConfigId !== this.$store.state.activeServerConfigId
    },
    shownBookmarks() {
      return this.foreignServer ? this.copyBookmarks : this.bookmarks
    },
    canCreateBookmark() {
      return !this.shownBookmarks.find((bm) => bm.time === this.currentTime)
    },
    _playbackRate() {
      if (!this.playbackRate || isNaN(this.playbackRate)) return 1
      return this.playbackRate
    }
  },
  methods: {
    async loadCopyBookmarks() {
      if (!this.foreignServer) return
      const serverConfigId = this.serverConfigId
      const libraryItemId = this.libraryItemId
      const list = await this.$absData.getBookmarks({ serverConfigId }).catch((error) => {
        console.error('[BookmarksModal] Could not read bookmarks', error)
        return null
      })
      if (!list || serverConfigId !== this.serverConfigId || libraryItemId !== this.libraryItemId) return
      this.copyBookmarks = list.filter((bm) => bm.libraryItemId === libraryItemId)
    },
    bookmarkPlaceholder() {
      // using a method prevents caching the date
      return this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
    },
    editBookmark(bm) {
      this.selectedBookmark = bm
      this.newBookmarkTitle = bm.title
      this.showBookmarkTitleInput = true
    },
    async deleteBookmark(bm) {
      await this.$hapticsImpact()
      const { value } = await Dialog.confirm({
        title: 'Remove Bookmark',
        message: this.$strings.MessageConfirmRemoveBookmark
      })
      if (!value) return

      // Saved on the device and sent when the server is reachable; the list follows through $absData.onDataChanged
      this.$absData.removeBookmark({ libraryItemId: this.libraryItemId, time: bm.time, serverConfigId: this.serverConfigId }).catch((error) => {
        this.$toast.error(this.$strings.ToastBookmarkRemoveFailed)
        console.error(error)
      })
    },
    async clickBookmark(bm) {
      await this.$hapticsImpact()
      this.$emit('select', bm)
    },
    submitUpdateBookmark(updatedBookmark) {
      this.$absData
        .renameBookmark({ libraryItemId: this.libraryItemId, time: updatedBookmark.time, title: updatedBookmark.title, serverConfigId: this.serverConfigId })
        .then(() => {
          this.showBookmarkTitleInput = false
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkUpdateFailed)
          console.error(error)
        })
    },
    submitCreateBookmark() {
      if (!this.newBookmarkTitle) {
        this.newBookmarkTitle = this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
      }
      const bookmark = {
        title: this.newBookmarkTitle,
        time: Math.floor(this.currentTime)
      }
      this.$absData
        .addBookmark({ libraryItemId: this.libraryItemId, ...bookmark, serverConfigId: this.serverConfigId })
        .then(() => {
          this.$toast.success('Bookmark added')
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkCreateFailed)
          console.error(error)
        })

      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = false

      this.show = false
    },
    createBookmark() {
      this.selectedBookmark = null
      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = true
    },
    async submitBookmark() {
      await this.$hapticsImpact()
      if (this.selectedBookmark) {
        var updatePayload = {
          ...this.selectedBookmark,
          title: this.newBookmarkTitle
        }
        this.submitUpdateBookmark(updatePayload)
      } else {
        this.submitCreateBookmark()
      }
    }
  },
  mounted() {
    this.stopListening = this.$absData.onDataChanged((change) => {
      if (change?.scope === 'bookmarks') this.loadCopyBookmarks()
    })
  },
  beforeDestroy() {
    if (this.stopListening) this.stopListening()
  }
}
</script>
