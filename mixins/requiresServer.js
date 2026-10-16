/** Actions that only the server can carry out: shown offline, but disabled and labeled. */
export default {
  computed: {
    isServerOnline() {
      return !!this.$store.getters.getIsServerOnline
    }
  },
  methods: {
    requiresServerLabel() {
      return this.$strings.LabelRequiresServer
    }
  }
}
