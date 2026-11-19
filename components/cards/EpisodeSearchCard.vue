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
import requiresServer from '@/mixins/requiresServer'

export default {
  mixins: [requiresServer],
  props: {
    episode: {
      type: Object,
      default: () => {}
    },
    libraryItem: {
      type: Object,
      default: () => {}
    },
    /** The podcast's downloaded copy on the active server, if any. */
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
      return this.episode?.title || 'No Title'
    },
    subtitle() {
      return this.mediaMetadata.title
    },
    authorName() {
      return this.mediaMetadata.author
    },
    /** Offline, an episode that is not downloaded cannot be played, even when others of the podcast are. */
    notAvailableOffline() {
      return this.isNotAvailableOffline(this.libraryItem, this.localLibraryItem, this.episode?.id || null)
    }
  },
  methods: {},
  mounted() {}
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
