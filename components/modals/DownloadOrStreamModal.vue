<template>
  <modals-dialog v-model="show" :items="items" @action="clickedOption" />
</template>

<script>
import requiresServer from '@/mixins/requiresServer'

export default {
  mixins: [requiresServer],
  props: {
    value: Boolean,
    anyLocal: Boolean
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
    items() {
      return [
        { text: this.anyLocal ? 'Play Now' : 'Stream Now', value: 'play', icon: 'play_arrow' },
        { text: 'Download & Play', value: 'downloadAndPlay', icon: 'download', ...this.serverOnlyItem }
      ]
    }
  },
  methods: {
    clickedOption(action) {
      this.show = false
      this.$emit(action)
    }
  }
}
</script>
