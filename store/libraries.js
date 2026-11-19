import VueRouter from 'vue-router'
import { activateViewScope, bookshelfTabs, emptyViewState, planViewSwitch, recordTab, restoreTab, serializeLibraryViewScopes, viewScopeKey } from '@/utils/libraryViewState'
const { BookCoverAspectRatio } = require('../plugins/constants')

export const state = () => ({
  libraries: [],
  lastLoad: 0,
  currentLibraryId: '',
  showModal: false,
  issues: 0,
  filterData: null,
  numUserPlaylists: 0,
  ereaderDevices: [],
  // The memory of the active account (viewScope); the others wait in viewScopes. legacyViewState is the unscoped memory
  // of the first build with this feature, until one account has taken it over
  viewState: { owner: null, libraries: {} },
  viewScopes: {},
  viewScope: null,
  legacyViewState: null,
  viewStateLoaded: false,
  switching: false,
  tabRecordingSuspended: false,
  // Once per process: the start-up library has opened on its remembered tab (or the chance has passed)
  startupTabRestored: false,
  startupRestorePending: false
})

// Duplicate, cancelled and redirected navigations are expected while pages react to library-changed
async function replaceRoute(router, path) {
  try {
    await router.replace(path)
  } catch (error) {
    if (!VueRouter.isNavigationFailure(error)) console.error('[libraries] Navigation failed', path, error)
  }
}

export const getters = {
  getCurrentLibrary: state => {
    return state.libraries.find(lib => lib.id === state.currentLibraryId)
  },
  getCurrentLibraryName: (state, getters) => {
    return getters.getCurrentLibrary?.name || null
  },
  getCurrentLibraryMediaType: (state, getters) => {
    return getters.getCurrentLibrary?.mediaType || null
  },
  getCurrentLibrarySettings: (state, getters) => {
    return getters.getCurrentLibrary?.settings || null
  },
  getBookCoverAspectRatio: (state, getters) => {
    if (isNaN(getters.getCurrentLibrarySettings?.coverAspectRatio)) return 1
    return getters.getCurrentLibrarySettings.coverAspectRatio === BookCoverAspectRatio.STANDARD ? 1.6 : 1
  },
  getLibraryIsAudiobooksOnly: (state, getters) => {
    return !!getters.getCurrentLibrarySettings?.audiobooksOnly
  },
  getBookshelfTabs: (state, getters, rootState, rootGetters) => {
    return bookshelfTabs({ mediaType: getters.getCurrentLibraryMediaType, hasPlaylists: !!state.numUserPlaylists, isAdmin: !!rootGetters['user/getIsAdminOrUp'] })
  }
}

export const actions = {
  // payload: a library id, or { libraryId, onlyIfCurrent } for a background re-read that must not override a library
  // the user picked while it was in flight. Read through $absData: the replica, or the server while it was never
  // reconciled, so it works without a signed-in user (offline, after a refused sign-in)
  fetch({ state, commit, dispatch, rootState, rootGetters }, payload) {
    const { libraryId, onlyIfCurrent = false } = typeof payload === 'object' && payload ? payload : { libraryId: payload }
    if (!rootGetters.getHasActiveServer) {
      console.error('libraries/fetch - No active server')
      return false
    }
    // Before the request: the memory of the account that is signed in (nothing happens when it is already active)
    dispatch('activateViewScope')

    return this.$absData
      .getLibrary({ id: libraryId })
      .then(async (data) => {
        if (onlyIfCurrent && state.currentLibraryId !== libraryId) return false
        const library = data.library
        const filterData = data.filterdata
        const issues = data.issues || 0
        const numUserPlaylists = data.numUserPlaylists || 0

        commit('addUpdate', library)
        commit('setLibraryIssues', issues)
        commit('setLibraryFilterData', filterData)
        commit('setNumUserPlaylists', numUserPlaylists)
        commit('setCurrentLibrary', libraryId)
        // The current library changes here from every place (connection, first library, the picker): native
        // reconciles it first
        this.$absData.setCurrentLibrary({ libraryId }).catch((error) => console.error('Could not set the current library', error))
        // A library chosen outside start-up (e.g. by opening a book) gets no start-up restore but its tabs are remembered
        if (!state.startupRestorePending) commit('setStartupTabRestored', true)

        // Library, owner and settings change in the same tick (updateUserSettings commits before its first await), so
        // neither an overlapping fetch nor a bookshelf reloading on user-settings sees them out of step
        let settingsApplied = null
        if (state.viewStateLoaded && state.viewScope) {
          const plan = planViewSwitch(state.viewState, libraryId, rootState.user.settings)
          if (plan.state !== state.viewState) {
            commit('setViewState', plan.state)
            dispatch('saveViewState')
          }
          if (plan.settingsUpdate) settingsApplied = dispatch('user/updateUserSettings', plan.settingsUpdate, { root: true })
        }
        // Still in the same tick: after the await another fetch may have made a different library current
        dispatch('user/checkUpdateLibrarySortFilter', library.mediaType, { root: true })
        await settingsApplied
        return data
      })
      .catch((error) => {
        console.error('Failed', error)
        return false
      })
  },
  // Return true if calling load. force: skip the 5-minute cache (after connecting, a catalog change, a server switch)
  load({ state, commit, rootGetters }, { force = false } = {}) {
    if (!rootGetters.getHasActiveServer) {
      console.error('libraries/load - No active server')
      return false
    }

    // Don't load again if already loaded in the last 5 minutes
    var lastLoadDiff = Date.now() - state.lastLoad
    if (!force && lastLoadDiff < 5 * 60 * 1000) {
      // Already up to date
      return false
    }

    return this.$absData
      .getLibraries()
      .then(async (data) => {
        const libraries = data.libraries || []

        // Keep the current library if it is still there, else the last one picked, else the first
        if (libraries.length && (!state.currentLibraryId || !libraries.find((li) => li.id == state.currentLibraryId))) {
          const lastLibraryId = await this.$localStore.getLastLibraryId()
          commit('setCurrentLibrary', libraries.find((li) => li.id === lastLibraryId) ? lastLibraryId : libraries[0].id)
        }

        commit('set', libraries)
        // An empty replica that was never reconciled is no answer: the next load must ask again
        if (libraries.length || data.replica?.reconciledAt != null) commit('setLastLoad', Date.now())
        return true
      })
      .catch((error) => {
        console.error('Failed', error)
        commit('set', [])
        return false
      })
  },
  // Switch from the library button or the libraries menu: opens the library on its remembered tab
  async switchLibrary({ state, getters, commit, dispatch }, libraryId) {
    if (state.switching) return false
    commit('setSwitching', true)
    // Pages redirect on library-changed; those intermediate routes must not be remembered for the new library
    commit('setTabRecordingSuspended', true)
    try {
      if (!(await dispatch('fetch', libraryId))) return false
      // After the fetch: the available tabs depend on the new library's playlists
      const target = restoreTab(state.viewState, libraryId, getters.getBookshelfTabs)
      this.$eventBus.$emit('library-changed', libraryId)
      this.$localStore.setLastLibraryId(libraryId)
      await replaceRoute(this.$router, target)
      commit('setTabRecordingSuspended', false)
      dispatch('rememberTab', this.$router.currentRoute.name)
      return true
    } finally {
      commit('setTabRecordingSuspended', false)
      commit('setSwitching', false)
    }
  },
  async openRememberedTab({ state, getters }) {
    const target = restoreTab(state.viewState, state.currentLibraryId, getters.getBookshelfTabs)
    if (target !== '/bookshelf') await replaceRoute(this.$router, target)
  },
  rememberTab({ state, commit, dispatch }, routeName) {
    if (!state.viewStateLoaded || !state.viewScope || !state.currentLibraryId) return
    const next = recordTab(state.viewState, state.currentLibraryId, routeName)
    if (!next) return
    commit('setViewState', next)
    dispatch('saveViewState')
  },
  async loadViewState({ commit }) {
    const [memory, legacy] = await Promise.all([this.$localStore.getLibraryViewScopes(), this.$localStore.getLegacyLibraryViewState()])
    commit('setViewStateLoaded', true)
    commit('setViewScopes', memory.scopes)
    // The live sort/filter still belong to the account that used the app last: it stays the active one until the account
    // that signs in is known, and a different account then takes over like after a sign-out (see activateViewScope)
    commit('setViewScope', memory.active)
    commit('setViewState', memory.scopes[memory.active] || emptyViewState())
    commit('setLegacyViewState', legacy.owner || Object.keys(legacy.libraries).length ? legacy : null)
  },
  // Makes the memory of the signed-in account the active one. Synchronous on purpose (no await): fetch calls it in the same
  // tick that it applies a library's view.
  activateViewScope({ state, commit, dispatch, rootState }) {
    if (!state.viewStateLoaded) return
    // Offline (before the sign-in, after a refused one) the account is the active server's cached user
    const scope = viewScopeKey(rootState.user.serverConnectionConfig?.id || rootState.activeServerConfigId, (rootState.user.user || rootState.user.cachedUser)?.id)
    if (scope === state.viewScope) return
    // The legacy memory goes to an account only when it is the only saved connection on this device
    const claimLegacy = !!scope && !!state.legacyViewState && rootState.deviceData?.serverConnectionConfigs?.length === 1
    const next = activateViewScope({ scopes: state.viewScopes, scope: state.viewScope, viewState: state.viewState }, scope, rootState.user.settings, { legacy: state.legacyViewState, claimLegacy })
    commit('setViewScopes', next.scopes)
    commit('setViewScope', next.scope)
    commit('setViewState', next.viewState)
    if (next.legacyClaimed) {
      commit('setLegacyViewState', null)
      this.$localStore.removeLegacyLibraryViewState()
    }
    dispatch('saveViewState')
  },
  // Writes only after loading, so an early write can never overwrite the saved memory
  async saveViewState({ state }) {
    if (!state.viewStateLoaded) return
    await this.$localStore.setLibraryViewScopes(serializeLibraryViewScopes(state.viewScopes, state.viewScope, state.viewState))
  }
}

export const mutations = {
  setShowModal(state, val) {
    state.showModal = val
  },
  setLastLoad(state, val) {
    state.lastLoad = val
  },
  reset(state) {
    state.lastLoad = 0
    state.currentLibraryId = null
    state.libraries = []
  },
  setCurrentLibrary(state, val) {
    state.currentLibraryId = val
  },
  set(state, libraries) {
    state.libraries = libraries
  },
  addUpdate(state, library) {
    var index = state.libraries.findIndex(a => a.id === library.id)
    if (index >= 0) {
      state.libraries.splice(index, 1, library)
    } else {
      state.libraries.push(library)
    }
  },
  remove(state, library) {
    state.libraries = state.libraries.filter(a => a.id !== library.id)
  },
  setLibraryIssues(state, val) {
    state.issues = val
  },
  setNumUserPlaylists(state, numUserPlaylists) {
    state.numUserPlaylists = numUserPlaylists
  },
  setLibraryFilterData(state, filterData) {
    state.filterData = filterData
  },
  updateFilterDataWithAudiobook(state, audiobook) {
    if (!audiobook || !audiobook.book || !state.filterData) return
    if (state.currentLibraryId !== audiobook.libraryId) return
    /*
    var filterdata = {
      authors: [],
      genres: [],
      tags: [],
      series: [],
      narrators: []
    }
    */

    if (audiobook.book.authorFL) {
      audiobook.book.authorFL.split(', ').forEach((author) => {
        if (author && !state.filterData.authors.includes(author)) {
          state.filterData.authors.push(author)
        }
      })
    }
    if (audiobook.book.narratorFL) {
      audiobook.book.narratorFL.split(', ').forEach((narrator) => {
        if (narrator && !state.filterData.narrators.includes(narrator)) {
          state.filterData.narrators.push(narrator)
        }
      })
    }
    if (audiobook.book.series && !state.filterData.series.includes(audiobook.book.series)) {
      state.filterData.series.push(audiobook.book.series)
    }
    if (audiobook.tags && audiobook.tags.length) {
      audiobook.tags.forEach((tag) => {
        if (tag && !state.filterData.tags.includes(tag)) state.filterData.tags.push(tag)
      })
    }
    if (audiobook.book.genres && audiobook.book.genres.length) {
      audiobook.book.genres.forEach((genre) => {
        if (genre && !state.filterData.genres.includes(genre)) state.filterData.genres.push(genre)
      })
    }
  },
  setEReaderDevices(state, ereaderDevices) {
    state.ereaderDevices = ereaderDevices
  },
  setViewState(state, viewState) {
    state.viewState = viewState
  },
  setViewScopes(state, val) {
    state.viewScopes = val
  },
  setViewScope(state, val) {
    state.viewScope = val
  },
  setLegacyViewState(state, val) {
    state.legacyViewState = val
  },
  setViewStateLoaded(state, val) {
    state.viewStateLoaded = val
  },
  setSwitching(state, val) {
    state.switching = val
  },
  setTabRecordingSuspended(state, val) {
    state.tabRecordingSuspended = val
  },
  setStartupTabRestored(state, val) {
    state.startupTabRestored = val
  },
  setStartupRestorePending(state, val) {
    state.startupRestorePending = val
  }
}