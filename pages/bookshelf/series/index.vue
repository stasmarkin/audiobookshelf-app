<template>
  <bookshelf-lazy-bookshelf page="series" />
</template>

<script>
export default {
  watch: {
    // Set by the series page when the series could not be read (not when it is gone); the tab may already be open
    '$route.query.error': {
      immediate: true,
      handler(error) {
        if (!error) return
        this.$toast.error(this.$strings.ToastSeriesLoadFailed)
        // Not again on Back
        this.$router.replace({ path: this.$route.path, query: {} })
      }
    }
  }
}
</script>
