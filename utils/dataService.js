/**
 * $absData: the WebView side of the native AbsData plugin.
 * - Mirrors the active server's reachability into the store (`serverState`, `activeServerConfigId`) and forwards what
 *   the WebView's own transports saw about the server. Reports are fire-and-forget: a failed report must never fail
 *   the request that triggered it.
 * - Bookmarks live in native userdata.db and work offline: writes go to native, which queues them for the server, and
 *   `onDataChanged {scope:'bookmarks'}` reloads them into `user/setBookmarks`. The sync state goes to `setSyncState`.
 * - Progress and the last known user object live there too: `getUserData` fills `user/setUserData` and
 *   `user/setCachedUser`; `onDataChanged {scope:'progress'|'playback', ids}` re-reads only those items. 'playback' is a
 *   tick (every 15 s while playing) and touches nothing else; 'progress' (an action, a server value) also re-reads the
 *   Paper progress of downloaded copies. Writes ("finished", reset, e-book position) go to native and work offline.
 * - Another saved server becoming active clears the user's data in the store and reads that server's; an answer that
 *   was asked for the previous server is dropped.
 * - In the browser (`platform: 'web'`, development only) there is no native side: everything goes straight to the
 *   server through $axios, and subscribers get the same `onDataChanged` events.
 *
 * @param {{ plugin: object, store: { commit: Function, dispatch?: Function }, platform?: string, $axios?: object }} deps
 */
export function createDataService({ plugin, store, platform = null, $axios = null }) {
  const isWeb = platform === 'web'
  const listeners = new Set()
  const dataListeners = new Set()
  const syncListeners = new Set()
  let eventCount = 0

  const activeServer = () => store.state?.activeServerConfigId ?? null

  const apply = (payload) => {
    if (!payload?.state) return
    const before = activeServer()
    store.commit('setServerState', payload.state)
    store.commit('setActiveServerConfigId', payload.serverConfigId || null)
    // Not on the first answer (nothing of another server is shown yet): only a switch between saved servers
    if (before && before !== activeServer()) {
      store.commit('user/clearUserData')
      reloadUserData().catch((error) => console.error('[data] Could not read the user data', error))
    }
  }

  plugin.addListener('onServerState', (payload) => {
    eventCount++
    apply(payload)
    listeners.forEach((cb) => cb(payload))
  })

  const getServerState = async () => {
    const before = eventCount
    const payload = await plugin.getServerState()
    // An event that arrived while we waited is newer than this answer
    if (eventCount === before) apply(payload)
    return payload
  }

  const quietly = (call) => {
    try {
      return Promise.resolve(call()).catch(() => {})
    } catch {
      return Promise.resolve()
    }
  }

  const getBookmarks = async ({ serverConfigId } = {}) => {
    if (isWeb) {
      const user = await $axios.$get('/api/me')
      return user?.bookmarks || []
    }
    const { bookmarks } = await plugin.getBookmarks(withServer({}, serverConfigId))
    return bookmarks || []
  }

  const reloadBookmarks = async () => {
    const serverBefore = store.state?.activeServerConfigId ?? null
    const bookmarks = await getBookmarks()
    // The active server changed while native answered: that list belongs to the previous one, so ask again
    if ((store.state?.activeServerConfigId ?? null) !== serverBefore) return reloadBookmarks()
    store.commit('user/setBookmarks', bookmarks)
    return bookmarks
  }

  /** The user's data of the active server: `{ user, mediaProgress, bookmarks }` (user: the last known object or null). */
  const getUserData = async ({ serverConfigId } = {}) => {
    if (isWeb) {
      const { mediaProgress = [], bookmarks = [], ...user } = (await $axios.$get('/api/me')) || {}
      return { user: user.id ? user : null, mediaProgress, bookmarks }
    }
    const data = await plugin.getUserData(withServer({}, serverConfigId))
    return { user: data?.user || null, mediaProgress: data?.mediaProgress || [], bookmarks: data?.bookmarks || [] }
  }

  const reloadUserData = async () => {
    const serverBefore = activeServer()
    const data = await getUserData()
    // Asked for the server that was active before a switch: not this one's data
    if (activeServer() !== serverBefore) return reloadUserData()
    store.commit('user/setUserData', { mediaProgress: data.mediaProgress, bookmarks: data.bookmarks })
    store.commit('user/setCachedUser', data.user)
    return data
  }

  const reloadCachedUser = async () => {
    const serverBefore = activeServer()
    const { user } = await getUserData()
    if (activeServer() === serverBefore) store.commit('user/setCachedUser', user)
  }

  /** Native sends at most this many ids for a partial re-read; more (or none) re-read everything. */
  const PARTIAL_PROGRESS_LIMIT = 100

  const reloadProgressOf = async (libraryItemIds) => {
    if (isWeb || !libraryItemIds?.length || libraryItemIds.length > PARTIAL_PROGRESS_LIMIT) return reloadUserData()
    const serverBefore = activeServer()
    const { mediaProgress } = await plugin.getMediaProgress({ libraryItemIds })
    if (activeServer() !== serverBefore) return reloadUserData()
    store.commit('user/setMediaProgressForItems', { libraryItemIds, mediaProgress: mediaProgress || [] })
  }

  const reloadPaperProgress = () => {
    if (isWeb || typeof store.dispatch !== 'function') return Promise.resolve()
    return store.dispatch('globals/loadLocalMediaProgress')
  }

  const dataChanged = (payload) => {
    const fail = (what) => (error) => console.error(`[data] Could not reload ${what}`, error)
    if (payload?.scope === 'bookmarks') {
      reloadBookmarks().catch(fail('bookmarks'))
    } else if (payload?.scope === 'progress' || payload?.scope === 'playback') {
      reloadProgressOf(payload.ids).catch(fail('progress'))
      // A tick is already in the downloaded copy's Paper progress the player keeps; an action or a server value is not
      if (payload.scope === 'progress') reloadPaperProgress().catch(fail('downloaded progress'))
    } else if (payload?.scope === 'user') {
      reloadCachedUser().catch(fail('the user'))
    }
    dataListeners.forEach((cb) => cb(payload))
  }

  const applySyncState = (syncState) => {
    if (!syncState) return
    store.commit('setSyncState', syncState)
    syncListeners.forEach((cb) => cb(syncState))
  }

  plugin.addListener('onDataChanged', dataChanged)
  plugin.addListener('onSyncState', applySyncState)

  // In the browser the writes go to the server directly, and the store follows the server's list
  const webBookmarkWrite = async (request) => {
    await request()
    dataChanged({ scope: 'bookmarks', ids: [] })
  }
  const bookmarkPath = (libraryItemId) => `/api/me/item/${encodeURIComponent(libraryItemId)}/bookmark`
  const progressPath = (libraryItemId, episodeId) => `/api/me/progress/${encodeURIComponent(libraryItemId)}` + (episodeId ? `/${encodeURIComponent(episodeId)}` : '')
  const webProgressWrite = async (request) => {
    await request()
    dataChanged({ scope: 'progress', ids: [] })
  }
  const storedProgress = (libraryItemId, episodeId) =>
    (store.state?.user?.mediaProgress || []).find((mp) => mp.libraryItemId === libraryItemId && (mp.episodeId || null) === (episodeId || null))
  /** The browser's version of the native rule: only a strictly newer server value replaces the shown one. */
  const webApplyProgress = (progress) => {
    if (!progress?.libraryItemId || typeof progress.lastUpdate !== 'number') return { applied: false }
    const current = storedProgress(progress.libraryItemId, progress.episodeId)
    if (current && !(progress.lastUpdate > current.lastUpdate)) return { applied: false }
    store.commit('user/upsertMediaProgress', progress)
    dataListeners.forEach((cb) => cb({ scope: 'progress', ids: [progress.libraryItemId] }))
    return { applied: true }
  }

  // Without this the indicator stays hidden until something else asks
  getServerState().catch((error) => console.error('[data] Could not read the server state', error))
  if (!isWeb && plugin.getSyncState) {
    Promise.resolve(plugin.getSyncState())
      .then(applySyncState)
      .catch((error) => console.error('[data] Could not read the sync state', error))
  }

  return {
    getServerState,
    retryServer: () => plugin.retryServer(),
    reportServerResponse: ({ address, status }) => quietly(() => plugin.reportServerResponse({ address, status })),
    reportSocketLost: () => quietly(() => plugin.reportSocketLost()),
    onServerState(cb) {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },

    /** Visible bookmarks of the active server, or of `serverConfigId`: [{ libraryItemId, time, title, createdAt }]. */
    getBookmarks,
    reloadBookmarks,
    /**
     * Bookmark writes. `serverConfigId` is the server a downloaded copy belongs to when it is not the active one;
     * native queues the write for that server. Defaults to the active server.
     */
    addBookmark({ libraryItemId, time, title, serverConfigId }) {
      if (isWeb) return webBookmarkWrite(() => $axios.$post(bookmarkPath(libraryItemId), { time, title }))
      return plugin.addBookmark(withServer({ libraryItemId, time, title }, serverConfigId))
    },
    renameBookmark({ libraryItemId, time, title, serverConfigId }) {
      if (isWeb) return webBookmarkWrite(() => $axios.$patch(bookmarkPath(libraryItemId), { time, title }))
      return plugin.renameBookmark(withServer({ libraryItemId, time, title }, serverConfigId))
    },
    removeBookmark({ libraryItemId, time, serverConfigId }) {
      if (isWeb) return webBookmarkWrite(() => $axios.$delete(`${bookmarkPath(libraryItemId)}/${time}`))
      return plugin.removeBookmark(withServer({ libraryItemId, time }, serverConfigId))
    },

    getUserData,
    reloadUserData,
    /**
     * Progress writes; they work offline and reach the server through native's outbox. `serverConfigId`: the server a
     * downloaded copy belongs to, the active one by default.
     */
    markFinished({ libraryItemId, episodeId = null, isFinished, serverConfigId }) {
      if (isWeb) return webProgressWrite(() => $axios.$patch(progressPath(libraryItemId, episodeId), { isFinished }))
      return plugin.markFinished(withServer({ libraryItemId, episodeId, isFinished }, serverConfigId))
    },
    resetProgress({ libraryItemId, episodeId = null, serverConfigId }) {
      if (isWeb) {
        const id = storedProgress(libraryItemId, episodeId)?.id
        return id ? webProgressWrite(() => $axios.$delete(`/api/me/progress/${encodeURIComponent(id)}`)) : Promise.resolve()
      }
      return plugin.resetProgress(withServer({ libraryItemId, episodeId }, serverConfigId))
    },
    updateEbookProgress({ libraryItemId, ebookLocation, ebookProgress, serverConfigId }) {
      if (isWeb) return webProgressWrite(() => $axios.$patch(progressPath(libraryItemId), { ebookLocation, ebookProgress }))
      return plugin.updateEbookProgress(withServer({ libraryItemId, ebookLocation, ebookProgress }, serverConfigId))
    },
    /** A server progress record (the value fetched on refocus), merged by native's rule; resolves `{ applied }`. */
    async applyServerProgress({ progress }) {
      if (isWeb) return webApplyProgress(progress)
      return plugin.applyServerProgress({ progress })
    },

    /** Pull and push now; `reason`: 'manual' (default) or 'socket-init'. Nothing to sync in the browser. */
    async syncNow({ reason = 'manual' } = {}) {
      if (isWeb) return
      await plugin.syncNow({ reason })
    },
    async getSyncState() {
      if (isWeb) return { running: false, pendingCount: 0, lastError: null, lastSyncAt: null }
      const syncState = await plugin.getSyncState()
      applySyncState(syncState)
      return syncState
    },
    /**
     * A server event, with the server that was active when it arrived (native drops it if another one is active now).
     * In the browser a `user_updated` goes straight into the store.
     */
    async applyServerEvent({ event, payload, serverConfigId }) {
      if (isWeb) {
        if (event === 'user_item_progress_updated') return webApplyProgress(payload?.data)
        const hasBookmarks = Array.isArray(payload?.bookmarks)
        const hasProgress = Array.isArray(payload?.mediaProgress)
        if (event !== 'user_updated' || !(hasBookmarks || hasProgress)) return { applied: false }
        if (hasBookmarks) store.commit('user/setBookmarks', payload.bookmarks)
        if (hasProgress) store.commit('user/setMediaProgress', payload.mediaProgress)
        if (hasBookmarks) dataListeners.forEach((cb) => cb({ scope: 'bookmarks', ids: [] }))
        if (hasProgress) dataListeners.forEach((cb) => cb({ scope: 'progress', ids: [] }))
        return { applied: true }
      }
      return plugin.applyServerEvent({ event, payload, serverConfigId })
    },
    onDataChanged(cb) {
      dataListeners.add(cb)
      return () => dataListeners.delete(cb)
    },
    onSyncState(cb) {
      syncListeners.add(cb)
      return () => syncListeners.delete(cb)
    }
  }
}

function withServer(args, serverConfigId) {
  return serverConfigId ? { ...args, serverConfigId } : args
}
