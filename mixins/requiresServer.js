/**
 * Actions that only the server can carry out: shown offline, but disabled and labeled. Also the one "not available
 * offline" rule for server items. The store comes through `$nuxt` too: Lazy cards are built with `new ComponentClass`.
 */
export default {
  computed: {
    isServerOnline() {
      return !!(this.$store || this.$nuxt.$store).getters.getIsServerOnline
    },
    /** A server known to be away; not while the first check is pending ('unknown'). */
    isServerAway() {
      return ['offline', 'unauthorized'].includes((this.$store || this.$nuxt.$store).state.serverState)
    },
    /** Spread into a menu item (modals/Dialog): offline it stays visible, disabled and labeled. */
    serverOnlyItem() {
      return this.isServerOnline ? {} : { disabled: true, subtext: this.requiresServerLabel() }
    }
  },
  methods: {
    requiresServerLabel() {
      return this.$strings.LabelRequiresServer
    },
    /** With `episodeId` only that episode downloaded counts: a copy of the podcast alone does not play it. */
    isNotAvailableOffline(libraryItem, localCopy, episodeId = null) {
      if (!this.isServerAway || libraryItem?.isLocal) return false
      if (episodeId) return !(localCopy?.media?.episodes || []).some((ep) => ep.serverEpisodeId === episodeId)
      return !localCopy
    },
    notAvailableOfflineLabel() {
      return this.$strings.LabelNotAvailableOffline
    }
  }
}
