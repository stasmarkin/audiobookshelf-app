<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItems.length }})</p>

    <div v-if="!downloadItems.length" class="py-6 text-center text-lg">{{ $strings.MessageNoDownloads }}</div>
    <template v-for="(item, num) in downloadItems">
      <div :key="item.id" class="w-full download-entry" :data-state="stateOf(item)">
        <p class="font-semibold break-words">{{ item.itemTitle }}</p>
        <p v-if="item.isAuto" class="text-xs text-fg-muted">{{ $strings.LabelDownloadAuto }}</p>
        <!-- State in words and an icon, never color alone -->
        <div class="flex items-center mt-1 text-sm" role="status">
          <span class="material-symbols text-lg mr-1" :class="stateIconClass(item)" aria-hidden="true">{{ stateIcon(item) }}</span>
          <span class="text-fg">{{ stateText(item) }}</span>
          <span v-if="item.localFolder && !isAppStorage(item)" class="text-fg-muted truncate ml-2">· {{ item.localFolder.name }}</span>
        </div>
        <p v-if="stateOf(item) === 'folderAccess'" class="text-xs text-fg-muted mt-1">{{ $getString('MessageDownloadChooseFolder', [lostFolderName(item)]) }}</p>
        <p v-else-if="stateOf(item) === 'failed'" class="text-xs text-fg-muted mt-1">{{ $strings.MessageDownloadRetryRestarts }}</p>
        <p v-else-if="stateOf(item) === 'held'" class="text-xs text-fg-muted mt-1">{{ $strings.MessageDownloadHeld }}</p>

        <div class="mt-2 pl-1">
          <div v-for="itemPart in item.downloadItemParts" :key="itemPart.id" class="flex items-start py-0.5 text-sm">
            <div class="w-12 shrink-0">
              <span v-if="itemPart.completed && itemPart.moved" class="material-symbols text-success text-base">check_circle</span>
              <span v-else-if="itemPart.failed" class="material-symbols text-error text-base">error</span>
              <span v-else class="text-fg-muted">{{ Math.round(itemPart.progress) }}%</span>
            </div>
            <div class="flex-grow min-w-0">
              <p class="break-all text-fg-muted">{{ itemPart.filename }}</p>
              <p v-if="stateOf(item) !== 'folderAccess' && itemPart.failed && !itemPart.permissionLost && !itemPart.storageUnavailable && !itemPart.authorizationHold && itemPart.failureReason" class="text-xs text-error mt-0.5">{{ itemPart.failureReason }}</p>
            </div>
          </div>
        </div>

        <div v-if="actionsOf(item).length" class="flex flex-wrap gap-2 mt-3">
          <ui-btn v-for="action in actionsOf(item)" :key="action" small :padding-y="2" class="min-w-16" :color="action === 'retry' || action === 'chooseFolder' ? 'success' : 'primary'" :loading="busyId === item.id" @click="runAction(item, action)">{{ actionLabel(action) }}</ui-btn>
        </div>

        <div v-if="num + 1 < downloadItems.length" class="flex border-t border-border my-4" />
      </div>
    </template>
  </div>
</template>

<script>
import { AbsDownloader, AbsFileSystem } from '@/plugins/capacitor'
import { downloadState, downloadActions, downloadProgress, lostFolder, isWaitingState, DownloadAction, DownloadState } from '@/utils/downloadState'

// Why a download waits can change without a queue event (Wi-Fi joins, the charger is plugged in)
const REFRESH_MS = 10000
import { isAppStorageFolderId } from '@/utils/appStorage'

export default {
  data() {
    return {
      busyId: null,
      refreshTimer: null
    }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    }
  },
  methods: {
    stateOf(item) {
      return downloadState(item)
    },
    actionsOf(item) {
      return downloadActions(this.stateOf(item))
    },
    isAppStorage(item) {
      return isAppStorageFolderId(item.localFolder?.id)
    },
    lostFolderName(item) {
      return lostFolder(item)?.name || item.localFolder?.name || ''
    },
    stateText(item) {
      switch (this.stateOf(item)) {
        case DownloadState.DOWNLOADING:
          return this.$getString('LabelDownloadDownloading', [Math.round(downloadProgress(item) * 100)])
        case DownloadState.QUEUED:
          return this.$strings.LabelDownloadQueued
        case DownloadState.WAITING_FOR_STORAGE:
          return this.$strings.LabelDownloadWaitingForStorage
        case DownloadState.FAILED:
          return this.$strings.LabelDownloadFailed
        case DownloadState.FOLDER_ACCESS:
          return this.$strings.LabelDownloadFolderAccessLost
        case DownloadState.STORAGE_UNAVAILABLE:
          return this.$strings.LabelDownloadStorageUnavailable
        case DownloadState.HELD:
          return this.$strings.LabelDownloadHeld
        case DownloadState.WAITING_FOR_CARD:
          return this.$strings.LabelDownloadWaitingForCard
        case DownloadState.WAITING_FOR_NETWORK:
          return this.$strings.LabelDownloadWaitingForNetwork
        case DownloadState.WAITING_FOR_WIFI:
          return this.$strings.LabelDownloadWaitingForWifi
        case DownloadState.WAITING_FOR_CHARGING:
          return this.$strings.LabelDownloadWaitingForCharging
        case DownloadState.WAITING_FOR_BACKGROUND:
          return this.$strings.LabelDownloadWaitingForApp
        default:
          return this.$strings.LabelDownloadFinishing
      }
    },
    stateIcon(item) {
      const state = this.stateOf(item)
      if (state === DownloadState.FAILED || state === DownloadState.STORAGE_UNAVAILABLE) return 'error'
      if (state === DownloadState.FOLDER_ACCESS) return 'folder_off'
      if (state === DownloadState.HELD) return 'pause_circle'
      if (isWaitingState(state)) return 'schedule'
      return 'downloading'
    },
    stateIconClass(item) {
      const state = this.stateOf(item)
      return state === DownloadState.FAILED || state === DownloadState.FOLDER_ACCESS || state === DownloadState.STORAGE_UNAVAILABLE || state === DownloadState.HELD ? 'text-error' : 'text-fg-muted'
    },
    actionLabel(action) {
      return {
        [DownloadAction.CANCEL]: this.$strings.ButtonCancel,
        [DownloadAction.RETRY]: this.$strings.ButtonRetry,
        [DownloadAction.CHOOSE_FOLDER]: this.$strings.ButtonReselectFolder,
        [DownloadAction.CLEAR]: this.$strings.ButtonClear
      }[action]
    },
    async refresh() {
      const result = await AbsDownloader.getDownloadQueue().catch(() => null)
      if (!result || !Array.isArray(result.items)) return
      this.$store.commit(
        'globals/setItemDownloads',
        result.items.map((downloadItem) => ({ ...downloadItem, itemProgress: downloadProgress(downloadItem), episodes: downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode) }))
      )
    },
    async runAction(item, action) {
      if (this.busyId) return
      this.busyId = item.id
      try {
        if (action === DownloadAction.CANCEL || action === DownloadAction.CLEAR) await this.remove(item, action)
        else if (action === DownloadAction.RETRY) await this.retry(item)
        else if (action === DownloadAction.CHOOSE_FOLDER) await this.chooseFolder(item)
      } finally {
        this.busyId = null
      }
    },
    async remove(item, action) {
      const result = await AbsDownloader.removeDownload({ downloadItemId: item.id, action })
      if (result?.errorCode === 'moving') return this.$toast.info(this.$strings.MessageDownloadBusy)
      if (result?.errorCode === 'finishing') return this.$toast.info(this.$strings.MessageDownloadFinishing)
      // Removed (or already gone): the queue refresh confirms it; drop it now so the list updates at once
      this.$store.commit('globals/removeItemDownload', item.id)
    },
    async retry(item) {
      const result = await AbsDownloader.retryDownload({ downloadItemId: item.id })
      if (result?.errorCode === 'folderAccess') {
        await this.refresh()
        return this.$toast.error(this.$getString('MessageDownloadChooseFolder', [this.lostFolderName(item)]))
      }
      if (result?.errorCode === 'notFound') return this.$store.commit('globals/removeItemDownload', item.id)
      if (result?.errorCode === 'storageUnavailable') return this.$toast.error(this.$strings.LabelDownloadStorageUnavailable)
      if (result?.errorCode === 'held') return this.$toast.error(this.$strings.MessageDownloadHeld)
      if (result?.errorCode) return this.$toast.error(this.$strings.MessageDownloadBusy)
    },
    async chooseFolder(item) {
      const lost = lostFolder(item) || item.localFolder
      // The picker re-grants access; the download continues only into the folder it was started in
      const folder = await AbsFileSystem.selectFolder({ mediaType: item.mediaType })
      if (folder?.errorCode === 'canceled') return
      if (!folder || folder.error) {
        if (folder?.error) this.$toast.error(folder.error)
        return
      }
      if (lost && folder.id !== lost.id) return this.$toast.error(this.$getString('MessageDownloadWrongFolder', [lost.name]))
      // Document IDs alone are not unique across providers. Never redirect this download to a
      // different tree, even when that provider happens to return the same document ID.
      if (folder.contentUrl !== item.localFolder.contentUrl) return this.$toast.error(this.$getString('MessageDownloadWrongFolder', [lost.name]))
      // selectFolder uses the existing persisted-grant path. Renewal is not consent to retry.
      await this.refresh()
    }
  },
  mounted() {
    this.refresh()
    this.refreshTimer = setInterval(() => this.refresh(), REFRESH_MS)
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer)
  }
}
</script>
