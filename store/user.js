import { Browser } from '@capacitor/browser'
import { AbsLogger } from '@/plugins/capacitor'

export const state = () => ({
  user: null,
  accessToken: null,
  serverConnectionConfig: null,
  // Visible bookmarks of the active server from native userdata.db ($absData), also without a signed-in user
  bookmarks: [],
  // Progress of the active server from native userdata.db ($absData), in the server's MediaProgress shape
  mediaProgress: [],
  // The last known user object of the active server (permissions, settings) for offline use; never a live session
  cachedUser: null,
  settings: {
    mobileOrderBy: 'addedAt',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    playbackRate: 1,
    collapseSeries: false,
    collapseBookSeries: false,
    podcastEpisodesOrderBy: 'publishedAt',
    podcastEpisodesOrderDesc: null,
    podcastEpisodesFilterBy: 'incomplete',
    podcastEpisodesOrderOverrides: {}
  }
})

export const getters = {
  getIsRoot: (state) => {
    const user = state.user || state.cachedUser
    return !!user && user.type === 'root'
  },
  getIsAdminOrUp: (state) => {
    const user = state.user || state.cachedUser
    return !!user && (user.type === 'admin' || user.type === 'root')
  },
  getToken: (state) => {
    return state.accessToken || null
  },
  getServerConnectionConfigId: (state) => {
    return state.serverConnectionConfig?.id || null
  },
  getServerAddress: (state) => {
    return state.serverConnectionConfig?.address || null
  },
  getServerConfigName: (state) => {
    return state.serverConnectionConfig?.name || null
  },
  getUserMediaProgress:
    (state) =>
    (libraryItemId, episodeId = null) => {
      return (state.mediaProgress || []).find((li) => {
        if (episodeId && li.episodeId !== episodeId) return false
        return li.libraryItemId == libraryItemId
      })
    },
  getUserBookmarksForItem: (state) => (libraryItemId) => {
    return (state.bookmarks || []).filter((bm) => bm.libraryItemId === libraryItemId)
  },
  getUserSetting: (state) => (key) => {
    return state.settings?.[key] ?? null
  },
  getUserCanUpdate: (state) => {
    return !!(state.user || state.cachedUser)?.permissions?.update
  },
  getUserCanDelete: (state) => {
    return !!(state.user || state.cachedUser)?.permissions?.delete
  },
  getUserCanDownload: (state) => {
    return !!(state.user || state.cachedUser)?.permissions?.download
  },
  getUserCanAccessExplicitContent: (state) => {
    return !!(state.user || state.cachedUser)?.permissions?.accessExplicitContent
  }
}

export const actions = {
  // When changing libraries make sure sort and filter is still valid
  checkUpdateLibrarySortFilter({ state, dispatch, commit }, mediaType) {
    const settingsUpdate = {}
    if (mediaType == 'podcast') {
      if (state.settings.mobileOrderBy == 'media.metadata.authorName' || state.settings.mobileOrderBy == 'media.metadata.authorNameLF') {
        settingsUpdate.mobileOrderBy = 'media.metadata.author'
      }
      if (state.settings.mobileOrderBy == 'media.duration') {
        settingsUpdate.mobileOrderBy = 'media.numTracks'
      }
      if (state.settings.mobileOrderBy == 'media.metadata.publishedYear') {
        settingsUpdate.mobileOrderBy = 'media.metadata.title'
      }
      const invalidFilters = ['series', 'authors', 'narrators', 'languages', 'progress', 'issues']
      const filterByFirstPart = (state.settings.mobileFilterBy || '').split('.').shift()
      if (invalidFilters.includes(filterByFirstPart)) {
        settingsUpdate.mobileFilterBy = 'all'
      }
    } else {
      if (state.settings.mobileOrderBy == 'media.metadata.author') {
        settingsUpdate.mobileOrderBy = 'media.metadata.authorName'
      }
      if (state.settings.mobileOrderBy == 'media.numTracks') {
        settingsUpdate.mobileOrderBy = 'media.duration'
      }
    }
    if (Object.keys(settingsUpdate).length) {
      dispatch('updateUserSettings', settingsUpdate)
    }
  },
  async updateUserSettings({ state, commit }, payload) {
    if (!payload) return false

    let hasChanges = false
    const existingSettings = { ...state.settings }
    for (const key in existingSettings) {
      if (payload[key] !== undefined && existingSettings[key] !== payload[key]) {
        hasChanges = true
        existingSettings[key] = payload[key]
      }
    }
    if (hasChanges) {
      commit('setSettings', existingSettings)
      await this.$localStore.setUserSettings(existingSettings)
      this.$eventBus.$emit('user-settings', state.settings)
    }
  },
  async loadUserSettings({ state, commit }) {
    const userSettingsFromLocal = await this.$localStore.getUserSettings()

    if (userSettingsFromLocal) {
      const userSettings = { ...state.settings }
      for (const key in userSettings) {
        if (userSettingsFromLocal[key] !== undefined) {
          userSettings[key] = userSettingsFromLocal[key]
        }
      }
      commit('setSettings', userSettings)
      this.$eventBus.$emit('user-settings', state.settings)
    }
  },
  async openWebClient({ getters }, path = null) {
    const serverAddress = getters.getServerAddress
    if (!serverAddress) {
      console.error('openWebClient: No server address')
      return
    }
    try {
      let url = serverAddress.replace(/\/$/, '') // Remove trailing slash
      if (path?.startsWith('/')) url += path

      await Browser.open({ url })
    } catch (error) {
      console.error('Error opening browser', error)
    }
  },
  async logout({ state, commit }, logoutFromServer = false) {
    // Renewals still running must not reach this session while the steps below run (docs/authentication.md)
    this.$authRenewal.invalidate()
    // Logging out from server deletes the session so the refresh token is no longer valid
    // Currently this is not being used to support switching servers without logging back in (assuming refresh token is still valid)
    // We may want to make this change in the future
    if (state.serverConnectionConfig && logoutFromServer) {
      const refreshToken = await this.$db.getRefreshToken(state.serverConnectionConfig.id)
      const options = {}
      if (refreshToken) {
        // Refresh token is used to delete the session on the server
        options.headers = {
          'x-refresh-token': refreshToken
        }
      }
      // Logout from server
      await this.$nativeHttp.post('/logout', null, options).catch((error) => {
        console.error('Failed to logout', error)
      })
    }

    await this.$db.logout()
    this.$socket.logout()
    this.$localStore.removeLastLibraryId()
    commit('logout')
    commit('libraries/setCurrentLibrary', null, { root: true })
    commit('libraries/setStartupTabRestored', false, { root: true })
    await AbsLogger.info({ tag: 'user', message: `Logged out from server ${state.serverConnectionConfig?.name || 'Not connected'}` })
  },
  /**
   * The server refused the session (its refresh token): only the live session ends. The server, the selected library,
   * cached user, progress, bookmarks and the outbox stay, so the app keeps working until the user signs in again.
   * An explicit sign-out is `logout`.
   */
  async revokeSession({ state, commit }) {
    this.$authRenewal.invalidate()
    this.$socket.logout()
    commit('setUser', null)
    commit('setAccessToken', null)
    await AbsLogger.info({ tag: 'user', message: `Session revoked by server ${state.serverConnectionConfig?.name || 'Not connected'}` })
  },
  /**
   * Renews the access token after a 401, sharing one renewal per server with every other request ($authRenewal).
   * Resolves with the new access token, or null when it could not be renewed (a refused refresh token signs out).
   * @param {string} [failedToken] the token the failed request was sent with (defaults to the current one)
   */
  async refreshToken({ getters }, failedToken) {
    return this.$authRenewal.renew({ serverConnectionConfigId: getters.getServerConnectionConfigId, token: failedToken || getters.getToken }).catch((error) => {
      console.error('[user] Token renewal failed:', error.message)
      return null
    })
  }
}

export const mutations = {
  logout(state) {
    state.user = null
    state.accessToken = null
    state.serverConnectionConfig = null
  },
  setUser(state, user) {
    state.user = user
  },
  setAccessToken(state, accessToken) {
    state.accessToken = accessToken
  },
  /** Progress and bookmarks of the active server as native holds them. */
  setUserData(state, { mediaProgress, bookmarks } = {}) {
    state.mediaProgress = Array.isArray(mediaProgress) ? mediaProgress : []
    state.bookmarks = Array.isArray(bookmarks) ? bookmarks : []
  },
  setMediaProgress(state, mediaProgress) {
    state.mediaProgress = Array.isArray(mediaProgress) ? mediaProgress : []
  },
  /** Replaces the progress of just these items (every episode of a podcast) with what native holds now. */
  setMediaProgressForItems(state, { libraryItemIds, mediaProgress }) {
    const ids = new Set(libraryItemIds || [])
    state.mediaProgress = (state.mediaProgress || []).filter((mp) => !ids.has(mp.libraryItemId)).concat(Array.isArray(mediaProgress) ? mediaProgress : [])
  },
  upsertMediaProgress(state, row) {
    if (!row?.libraryItemId) return
    const same = (mp) => mp.libraryItemId === row.libraryItemId && (mp.episodeId || null) === (row.episodeId || null)
    const index = (state.mediaProgress || []).findIndex(same)
    if (index >= 0) state.mediaProgress.splice(index, 1, row)
    else state.mediaProgress.push(row)
  },
  setCachedUser(state, user) {
    state.cachedUser = user || null
  },
  /** Another saved server became active: nothing of the previous one may show. */
  clearUserData(state) {
    state.mediaProgress = []
    state.bookmarks = []
    state.cachedUser = null
  },
  setServerConnectionConfig(state, serverConnectionConfig) {
    state.serverConnectionConfig = serverConnectionConfig
  },
  setBookmarks(state, bookmarks) {
    state.bookmarks = Array.isArray(bookmarks) ? bookmarks : []
  },
  setSettings(state, settings) {
    if (!settings) return
    state.settings = settings
  }
}
