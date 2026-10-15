<template>
  <div v-if="icon" class="flex h-full items-center px-2">
    <span class="material-symbols text-lg" :class="iconClass" @click="showAlertDialog">{{ icon }}</span>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { indicatorModel } from '@/utils/serverIndicator'

const TONE_CLASS = { ok: 'text-success', warning: 'text-warning', error: 'text-error', muted: 'text-gray-200', metered: 'text-yellow-400' }

export default {
  computed: {
    model() {
      const state = this.$store.state
      return indicatorModel({
        serverState: state.serverState,
        networkConnected: state.networkConnected,
        hasServer: this.$store.getters['getHasActiveServer'],
        signedIn: !!state.user.user,
        socketConnected: state.socketConnected,
        syncState: state.syncState,
        attemptingConnection: state.attemptingConnection,
        networkConnectionType: state.networkConnectionType,
        isNetworkUnmetered: state.isNetworkUnmetered
      })
    },
    icon() {
      return this.model.icon
    },
    iconClass() {
      return TONE_CLASS[this.model.tone]
    }
  },
  methods: {
    async showAlertDialog() {
      const { labelKey, count, action } = this.model
      const title = this.$strings.HeaderConnectionStatus
      const message = count ? this.$getString(labelKey, [count]) : this.$strings[labelKey]
      const confirm = async (okButtonTitle) => (await Dialog.confirm({ title, message, okButtonTitle, cancelButtonTitle: this.$strings.ButtonCancel })).value
      if (action === 'signIn') {
        if (await confirm(this.$strings.ButtonConnectToServer)) this.$router.push('/connect')
      } else if (action === 'retry') {
        if (await confirm(this.$strings.ButtonRetry)) this.$absData.retryServer()
      } else if (action === 'sync') {
        if (await confirm(this.$strings.ButtonSyncNow)) this.$absData.syncNow()
      } else {
        Dialog.alert({ title, message })
      }
    }
  }
}
</script>
