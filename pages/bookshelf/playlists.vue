<template>
  <bookshelf-lazy-bookshelf page="playlists" />
</template>

<script>
export default {
  watch: {
    // Set by the playlist page when the playlist could not be read (not when it is gone); the tab may already be open
    '$route.query.error': {
      immediate: true,
      handler(error) {
        if (!error) return
        this.$toast.error(this.$strings.ToastPlaylistLoadFailed)
        // Not again on Back
        this.$router.replace({ path: this.$route.path, query: {} })
      }
    }
  }
}
</script>
