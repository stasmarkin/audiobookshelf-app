<template>
  <div class="w-full h-full py-6 px-4 flex flex-col">
    <div class="flex items-center mb-2">
      <p class="text-base font-semibold">{{ $strings.LabelFolder }}: {{ folderName }}</p>
      <div class="flex-grow" />

      <span v-if="dialogItems.length" class="material-symbols text-2xl" @click="showDialog = true">more_vert</span>
    </div>

    <p class="text-sm mb-4 text-fg-muted">{{ $strings.LabelMediaType }}: {{ mediaType }}</p>

    <p class="mb-2 text-base text-fg">{{ $strings.HeaderLocalLibraryItems }} ({{ localLibraryItems.length }})</p>

    <div v-if="scan" class="flex items-start mb-2 text-sm" role="status" aria-live="polite">
      <svg v-if="isScanning" class="animate-spin text-fg-muted mr-2 mt-0.5 shrink-0" style="width: 18px; height: 18px" viewBox="0 0 24 24" aria-hidden="true">
        <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
      </svg>
      <span v-else class="material-symbols text-lg mr-2" :class="scan.phase === 'failed' ? 'text-error' : 'text-success'">{{ scan.phase === 'failed' ? 'error' : 'check_circle' }}</span>
      <div class="min-w-0">
        <p class="text-fg">{{ scanStatusText }}</p>
        <p v-if="isScanning && scan.latestTitle" class="text-xs text-fg-muted truncate">{{ $getString('LabelRescanFound', [scan.latestTitle]) }}</p>
        <p v-else-if="scan.phase === 'failed'" class="text-xs text-fg-muted">{{ rescanErrorMessage(scan.error) }}</p>
      </div>
    </div>

    <div class="w-full media-item-container overflow-y-auto">
      <template v-for="localLibraryItem in localLibraryItems">
        <nuxt-link :to="`/localMedia/item/${localLibraryItem.id}`" :key="localLibraryItem.id" class="flex my-1">
          <div class="w-12 h-12 min-w-12 min-h-12 bg-primary">
            <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain" />
          </div>
          <div class="flex-grow px-2">
            <p class="text-sm">{{ localLibraryItem.media.metadata.title }}</p>
            <p class="text-xs text-fg-muted">{{ getLocalLibraryItemSubText(localLibraryItem) }}</p>
          </div>
          <div class="w-12 h-12 flex items-center justify-center">
            <span class="material-symbols text-xl text-fg-muted">arrow_right</span>
          </div>
        </nuxt-link>
      </template>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'
import { isAppStorageFolderId } from '@/utils/appStorage'

export default {
  asyncData({ params, query }) {
    return {
      folderId: params.id
    }
  },
  data() {
    return {
      localLibraryItems: [],
      folder: null,
      removingFolder: false,
      // Rescan Folder status from AbsFileSystem "onRescanProgress" events (null until a scan is seen)
      scan: null,
      rescanCallPending: false,
      rescanProgressListener: null,
      showDialog: false
    }
  },
  computed: {
    folderName() {
      return this.folder?.name || null
    },
    mediaType() {
      return this.folder?.mediaType
    },
    isAppStorage() {
      return isAppStorageFolderId(this.folder?.id)
    },
    isScanning() {
      return this.scan?.phase === 'loading' || this.scan?.phase === 'checking'
    },
    scanStatusText() {
      const scan = this.scan
      if (!scan) return ''
      if (scan.phase === 'loading') return `${this.$strings.LabelRescanScanning} ${this.$strings.LabelRescanLoading}`
      if (scan.phase === 'checking') {
        if (scan.total < 0) return this.$strings.LabelRescanScanning
        return `${this.$strings.LabelRescanScanning} ${this.$getString('LabelRescanChecked', [scan.checked, scan.total, scan.found])}`
      }
      if (scan.phase === 'failed') return this.$getString('LabelRescanFailed', [scan.found])
      return this.$getString('LabelRescanComplete', [scan.found])
    },
    dialogItems() {
      if (this.isAppStorage) return []
      const items = []
      // Rescan matches book folders only, one scan per folder at a time
      if (this.mediaType === 'book' && !this.isScanning) {
        items.push({
          text: this.$strings.ButtonRescanFolder,
          value: 'rescan'
        })
      }
      items.push({
        text: this.$strings.ButtonRemove,
        value: 'remove'
      })
      return items
    }
  },
  methods: {
    getLocalLibraryItemSubText(localLibraryItem) {
      if (!localLibraryItem) return ''
      if (localLibraryItem.mediaType == 'book') {
        const txts = []
        if (localLibraryItem.media.ebookFile) {
          txts.push(`${localLibraryItem.media.ebookFile.ebookFormat} ${this.$strings.LabelEbook}`)
        }
        if (localLibraryItem.media.tracks?.length) {
          txts.push(`${localLibraryItem.media.tracks.length} ${this.$strings.LabelTracks}`)
        }
        return txts.join(' • ')
      } else {
        return `${localLibraryItem.media.episodes?.length || 0} ${this.$strings.HeaderEpisodes}`
      }
    },
    dialogAction(action) {
      console.log('Dialog action', action)
      if (action == 'remove') {
        this.removeFolder()
      } else if (action == 'rescan') {
        this.rescanFolder()
      }
      this.showDialog = false
    },
    async rescanFolder() {
      if (this.isScanning) return
      // Shown at once; native progress events then fill in the counts
      this.scan = { folderId: this.folderId, phase: 'loading', checked: 0, total: -1, found: 0, unmatched: 0, latestTitle: null }
      this.rescanCallPending = true
      let result
      try {
        // The selected library scopes author/title matches for folders without a cover-file id
        result = await AbsFileSystem.rescanFolder({ folderId: this.folderId, libraryId: this.$store.state.libraries.currentLibraryId })
      } catch (error) {
        console.error('Rescan failed', error)
        result = { error: 'unexpected' }
      }
      this.rescanCallPending = false
      if (result?.error === 'running') {
        // A scan of this folder started earlier is still running; its events keep updating the status
        this.$toast.info(this.$strings.MessageRescanErrorRunning)
        return
      }
      // Relinked items keep their local progress under a corrected server link: refresh the cached copy
      await this.$store.dispatch('globals/loadLocalMediaProgress')
      if (result?.error) {
        this.scan = { ...this.scan, phase: 'failed', error: result.error, found: result.matched || 0 }
        await this.init()
        this.$toast.error(this.rescanErrorMessage(result.error))
        return
      }
      this.scan = { ...this.scan, phase: 'complete', found: result?.matched || 0 }
      await this.init()
      const ambiguous = result?.ambiguous?.length || 0
      const messages = [this.$getString('MessageRescanFolderResult', [result?.matched || 0, (result?.unmatched?.length || 0) + ambiguous])]
      if (result?.relinked) messages.push(this.$getString('MessageRescanFolderRelinked', [result.relinked]))
      if (ambiguous) messages.push(this.$getString('MessageRescanFolderAmbiguous', [ambiguous]))
      this.$toast.success(messages.join(' '))
    },
    rescanErrorMessage(error) {
      const messages = {
        catalog: this.$strings.MessageRescanErrorCatalog,
        folder: this.$strings.MessageRescanErrorFolder,
        server: this.$strings.MessageRescanErrorServer,
        running: this.$strings.MessageRescanErrorRunning
      }
      return messages[error] || this.$strings.MessageRescanErrorUnexpected
    },
    onRescanProgress(data) {
      // Progress for another folder is not this page's
      if (!data || data.folderId !== this.folderId) return
      const { localLibraryItem, ...progress } = data
      // Keep showing the last item found while the folders after it are checked
      this.scan = { ...(this.scan || {}), ...progress, latestTitle: progress.latestTitle || this.scan?.latestTitle || null }
      // A matched item is already saved, so it is final: show it now (newLocalLibraryItem skips duplicates)
      if (localLibraryItem) this.newLocalLibraryItem(localLibraryItem)
      // A scan this page did not start (e.g. reopened mid-scan) ended: reload the saved items
      const ended = progress.phase === 'complete' || progress.phase === 'failed'
      if (ended && !this.rescanCallPending) this.init()
    },
    async removeFolder() {
      var deleteMessage = 'Are you sure you want to remove this folder? (does not delete anything in your file system)'
      if (this.localLibraryItems.length) {
        deleteMessage = `Are you sure you want to remove this folder and ${this.localLibraryItems.length} items? (does not delete anything in your file system)`
      }
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: deleteMessage
      })
      if (value) {
        this.removingFolder = true
        await AbsFileSystem.removeFolder({ folderId: this.folderId })
        this.removingFolder = false
        this.$router.replace('/localMedia/folders')
      }
    },
    async init() {
      var folder = await this.$db.getLocalFolder(this.folderId)
      this.folder = folder

      var items = (await this.$db.getLocalLibraryItemsInFolder(this.folderId)) || []
      console.log('Init folder', this.folderId, items)
      this.localLibraryItems = items.map((lmi) => {
        console.log('Local library item', JSON.stringify(lmi))
        return {
          ...lmi,
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
    },
    newLocalLibraryItem(item) {
      if (item.folderId == this.folderId) {
        console.log('New local library item', item.id)
        if (this.localLibraryItems.find((li) => li.id == item.id)) {
          console.warn('Item already added', item.id)
          return
        }

        var _item = {
          ...item,
          coverPathSrc: item.coverContentUrl ? Capacitor.convertFileSrc(item.coverContentUrl) : null
        }
        this.localLibraryItems.push(_item)
      }
    }
  },
  async mounted() {
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.init()
    const listener = await AbsFileSystem.addListener('onRescanProgress', this.onRescanProgress)
    // Left the page before the listener was registered
    if (this._isDestroyed) {
      listener?.remove()
      return
    }
    this.rescanProgressListener = listener
    // Reopened while a scan of this folder is still running: show it until its next event fills in the counts
    const running = await AbsFileSystem.isRescanning({ folderId: this.folderId }).catch(() => null)
    if (running?.value && !this.scan) this.scan = { folderId: this.folderId, phase: 'checking', checked: 0, total: -1, found: 0, unmatched: 0, latestTitle: null }
  },
  beforeDestroy() {
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    this.rescanProgressListener?.remove()
  }
}
</script>

<style scoped>
/* The list takes the height left under the header, down to the page bottom (the page fills #content, which ends at the
   collapsed player or the screen), at any font scale; -24px reaches through the page's bottom padding as before */
.media-item-container {
  flex: 1 1 0;
  min-height: 0;
  margin-bottom: -24px;
}
</style>
