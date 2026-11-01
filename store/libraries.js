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
  // the user picked while it was in flight
  fetch({ state, commit, dispatch, rootState }, payload) {
    const { libraryId, onlyIfCurrent = false } = typeof payload === 'object' && payload ? payload : { libraryId: payload }
    if (!rootState.user || !rootState.user.user) {
      console.error('libraries/fetch - User not set')
      return false
    }
    // Before the request: the memory of the account that is signed in (nothing happens when it is already active)
    dispatch('activateViewScope')

    return this.$nativeHttp
      .get(`/api/libraries/${libraryId}?include=filterdata`)
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
  // Return true if calling load
  load({ state, commit, rootState }) {
    if (!rootState.user || !rootState.user.user) {
      console.error('libraries/load - User not set')
      return false
    }

    // Don't load again if already loaded in the last 5 minutes
    var lastLoadDiff = Date.now() - state.lastLoad
    if (lastLoadDiff < 5 * 60 * 1000) {
      // Already up to date
      return false
    }

    return this.$nativeHttp
      .get(`/api/libraries`)
      .then((data) => {
        // TODO: Server release 2.2.9 changed response to an object. Remove after a few releases
        const libraries = data.libraries || data

        // Set current library if not already set or was not returned in results
        if (libraries.length && (!state.currentLibraryId || !libraries.find(li => li.id == state.currentLibraryId))) {
          commit('setCurrentLibrary', libraries[0].id)
        }

        commit('set', libraries)
        commit('setLastLoad', Date.now())
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
    const scope = viewScopeKey(rootState.user.serverConnectionConfig?.id, rootState.user.user?.id)
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