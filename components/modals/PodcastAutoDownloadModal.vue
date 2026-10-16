<template>
  <modals-modal v-model="show" :width="'90%'" :max-width="'420px'" height="100%">
    <template #outer>
      <div class="absolute top-5 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.HeaderPodcastAutoDownload }}</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full rounded-lg bg-primary border border-white border-opacity-20 overflow-y-auto overflow-x-hidden" style="max-height: 80vh" @click.stop>
        <div class="w-full h-full p-4 relative">
          <div v-if="!globalEnabled" class="mb-4">
            <p class="text-sm text-fg-muted">{{ $strings.MessageAutoDownloadDisabledInSettings }}</p>
            <button type="button" class="text-sm text-success underline pt-1" @click="openAppSettings">{{ $strings.ButtonOpenAppSettings }}</button>
          </div>

          <div class="mb-4">
            <p class="pb-0.5 text-sm font-semibold">{{ $strings.LabelAutoDownloadToDevice }}</p>
            <ui-toggle-btns v-model="enabled" name="podcast-auto-download" :items="enabledItems" />
          </div>

          <ui-text-input-with-label v-model="keepCountInput" type="text" inputmode="numeric" :label="$strings.LabelAutoDownloadKeepCount" :placeholder="keepCountPlaceholder" :autofocus="false" class="mb-4" />

          <div class="mb-4">
            <p id="podcast-auto-download-retention" class="pb-1 text-sm font-semibold">{{ $strings.LabelAutoDownloadRetention }}</p>
            <div role="radiogroup" aria-labelledby="podcast-auto-download-retention">
              <button
                v-for="option in retentionItems"
                :key="option.value"
                type="button"
                role="radio"
                :aria-checked="String(retention === option.value)"
                class="w-full flex items-start text-left rounded-md border p-2 mb-2"
                :class="retention === option.value ? 'border-fg bg-bg-toggle-selected' : 'border-border bg-bg-toggle'"
                @click="retention = option.value"
              >
                <span class="material-symbols text-xl mr-2 leading-6" aria-hidden="true">{{ retention === option.value ? 'radio_button_checked' : 'radio_button_unchecked' }}</span>
                <span class="flex-grow min-w-0">
                  <span class="block text-sm">{{ option.text }}</span>
                  <span class="block text-xs text-fg-muted pt-0.5">{{ option.description }}</span>
                </span>
              </button>
            </div>
          </div>

          <ui-btn class="w-full" :disabled="loading || saving" :loading="saving" @click="save">{{ $strings.ButtonSave }}</ui-btn>

          <div v-show="loading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center bg-primary bg-opacity-75">
            <ui-loading-indicator />
          </div>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  props: {
    value: Boolean,
    libraryItemId: String
  },
  data() {
    return {
      loading: false,
      saving: false,
      enabled: 'DEFAULT',
      keepCountInput: '',
      retention: 'AUTO',
      deviceSettings: {}
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
    globalEnabled() {
      return !!this.deviceSettings.autoDownloadEnabled
    },
    enabledItems() {
      return [
        { text: this.deviceSettings.autoDownloadAllPodcasts ? this.$strings.LabelDefaultOn : this.$strings.LabelDefaultOff, value: 'DEFAULT' },
        { text: this.$strings.LabelOn, value: 'ON' },
        { text: this.$strings.LabelOff, value: 'OFF' }
      ]
    },
    retentionItems() {
      return [
        { value: 'AUTO', text: this.$strings.LabelAutoDownloadRetentionAuto, description: this.$strings.MessageAutoDownloadRetentionAuto },
        { value: 'KEEP_ALL', text: this.$strings.LabelAutoDownloadRetentionKeepAll, description: this.$strings.MessageAutoDownloadRetentionKeepAll }
      ]
    },
    keepCountPlaceholder() {
      const globalKeepCount = Number.isInteger(this.deviceSettings.autoDownloadKeepCount) ? this.deviceSettings.autoDownloadKeepCount : 3
      return this.$getString('LabelDefaultValue', [globalKeepCount])
    }
  },
  watch: {
    show(val) {
      if (val) this.init()
    }
  },
  methods: {
    async init() {
      this.loading = true
      this.enabled = 'DEFAULT'
      this.keepCountInput = ''
      this.retention = 'AUTO'
      try {
        const [result, deviceData] = await Promise.all([AbsDownloader.getPodcastAutoDownload({ libraryItemId: this.libraryItemId }), this.$db.getDeviceData()])
        this.deviceSettings = deviceData?.deviceSettings || {}
        this.enabled = result?.enabled || 'DEFAULT'
        this.keepCountInput = result?.keepCount == null ? '' : String(result.keepCount)
        this.retention = result?.retention === 'KEEP_ALL' ? 'KEEP_ALL' : 'AUTO'
      } catch (error) {
        console.error('Failed to load podcast auto download settings', error)
        this.$toast.error(this.$strings.ToastAutoDownloadLoadFailed)
        this.show = false
      } finally {
        this.loading = false
      }
    },
    // Returns undefined when the input is invalid
    parseKeepCount() {
      const raw = String(this.keepCountInput ?? '').trim()
      if (!raw) return null
      if (!/^\d+$/.test(raw)) return undefined
      const num = Number(raw)
      return num >= 1 && num <= 999 ? num : undefined
    },
    openAppSettings() {
      this.show = false
      this.$router.push('/settings')
    },
    async save() {
      const keepCount = this.parseKeepCount()
      if (keepCount === undefined) {
        this.$toast.error(this.$strings.ToastAutoDownloadKeepCountInvalid)
        return
      }
      await this.$hapticsImpact()
      this.saving = true
      try {
        await AbsDownloader.setPodcastAutoDownload({ libraryItemId: this.libraryItemId, enabled: this.enabled, keepCount, retention: this.retention })
        this.$toast.success(this.$strings.ToastAutoDownloadSaved)
        this.show = false
      } catch (error) {
        console.error('Failed to save podcast auto download settings', error)
        this.$toast.error(this.$strings.ToastAutoDownloadSaveFailed)
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
