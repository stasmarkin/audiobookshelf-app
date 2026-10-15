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
        socketConnected: state.socketConnected,
        syncState: null,
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
      const { labelKey } = this.model
      const title = this.$strings.HeaderConnectionStatus
      const message = this.$strings[labelKey]
      if (labelKey === 'LabelSignInAgain') {
        const { value } = await Dialog.confirm({ title, message, okButtonTitle: this.$strings.ButtonConnectToServer, cancelButtonTitle: this.$strings.ButtonCancel })
        if (value) this.$router.push('/connect')
      } else if (labelKey === 'LabelServerUnreachable' || labelKey === 'MessageSocketNotConnected') {
        const { value } = await Dialog.confirm({ title, message, okButtonTitle: this.$strings.ButtonRetry, cancelButtonTitle: this.$strings.ButtonCancel })
        if (value) this.$absData.retryServer()
      } else {
        Dialog.alert({ title, message })
      }
    }
  }
}
</script>
