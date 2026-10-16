<template>
  <div class="flex h-full px-1 overflow-hidden">
    <covers-book-cover class="card-artwork" :class="{ 'opacity-50': notAvailableOffline }" :library-item="libraryItem" :width="coverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" />
    <div class="grow px-2 audiobookSearchCardContent">
      <p class="truncate text-sm">{{ title }}</p>
      <p v-if="subtitle" class="truncate text-xs text-gray-300">{{ subtitle }}</p>
      <p class="text-xs text-gray-200 truncate">{{ $getString('LabelByAuthor', [authorName]) }}</p>
      <p v-if="notAvailableOffline" class="not-offline-badge text-xs text-warning truncate">{{ $strings.LabelNotAvailableOffline }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    libraryItem: {
      type: Object,
      default: () => {}
    },
    search: String,
    /** The downloaded copy of this server item on the active server, if any. */
    localLibraryItem: {
      type: Object,
      default: null
    }
  },
  data() {
    return {}
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    coverWidth() {
      if (this.bookCoverAspectRatio === 1) return 50 * 1.2
      return 50
    },
    media() {
      return this.libraryItem ? this.libraryItem.media || {} : {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    mediaType() {
      return this.libraryItem ? this.libraryItem.mediaType : null
    },
    isPodcast() {
      return this.mediaType === 'podcast'
    },
    title() {
      return this.mediaMetadata.title || 'No Title'
    },
    subtitle() {
      return this.mediaMetadata.subtitle
    },
    authorName() {
      if (this.isPodcast) return this.mediaMetadata.author
      return this.mediaMetadata.authorName
    },
    /** Offline, a server item without a downloaded copy cannot be opened (the replica finds it anyway). */
    notAvailableOffline() {
      if (this.localLibraryItem) return false
      return ['offline', 'unauthorized'].includes(this.$store.state.serverState)
    }
  },
  methods: {
    /** The result's link opens nothing offline without a downloaded copy: say so instead. */
    clickCard(e) {
      if (!this.notAvailableOffline) return
      e?.preventDefault?.()
      e?.stopPropagation?.()
      this.$toast?.info(this.$strings.LabelNotAvailableOffline)
    }
  },
  mounted() {
    // On the root element, before the result's link handles the click (the root stays a plain themed div)
    this.$el.addEventListener('click', this.clickCard)
  },
  beforeDestroy() {
    this.$el.removeEventListener('click', this.clickCard)
  }
}
</script>

<style>
.audiobookSearchCardContent {
  width: calc(100% - 80px);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
</style>
