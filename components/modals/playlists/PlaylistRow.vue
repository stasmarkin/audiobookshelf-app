<template>
  <div :key="playlist.id" :id="`playlist-row-${playlist.id}`" class="flex items-center px-3 py-2 justify-start relative border-y border-white/5" :class="inPlaylist ? 'bg-primary/20' : ''">
    <div v-if="inPlaylist" class="membership-marker absolute top-0 left-0 h-full w-1 bg-success z-10" />
    <div class="w-14 min-w-[56px] text-center" @click.stop="clickCover">
      <covers-playlist-cover class="card-artwork" :items="items" :width="52" :height="52" />
    </div>
    <div class="flex-grow overflow-hidden">
      <p class="px-2 truncate text-sm">{{ playlist.name }}</p>
    </div>
    <div class="w-24 min-w-[96px] px-1">
      <ui-btn v-if="inPlaylist" small :disabled="disabled" class="w-full" @click.stop="click">{{ $strings.ButtonRemove }}</ui-btn>
      <ui-btn v-else small :disabled="disabled" class="w-full" @click.stop="click">{{ $strings.ButtonAdd }}</ui-btn>
      <p v-if="disabled" class="requires-server-label text-xxs text-fg-muted text-center truncate">{{ $strings.LabelRequiresServer }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    playlist: {
      type: Object,
      default: () => {}
    },
    inPlaylist: Boolean,
    /** Offline: adding and removing need the server. */
    disabled: Boolean
  },
  data() {
    return {}
  },
  computed: {
    items() {
      return this.playlist.items || []
    }
  },
  methods: {
    click() {
      if (this.disabled) return
      this.$emit('click', this.playlist)
    },
    clickCover() {
      this.$emit('close')
      this.$router.push(`/playlist/${this.playlist.id}`)
    }
  },
  mounted() {}
}
</script>
