<template>
  <bookshelf-lazy-bookshelf page="series" />
</template>

<script>
export default {
  data() {
    return {}
  },
  computed: {},
  methods: {},
  mounted() {
    // Set by the series page when the series could not be read (not when it is gone)
    if (this.$route.query.error) this.$toast.error(this.$strings.ToastSeriesLoadFailed)
  }
}
</script>