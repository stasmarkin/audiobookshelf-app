/**
 * $absData: the WebView side of the native AbsData plugin.
 * - Mirrors the active server's reachability into the store (`serverState`, `activeServerConfigId`) and forwards what
 *   the WebView's own transports saw about the server. Reports are fire-and-forget: a failed report must never fail
 *   the request that triggered it.
 * - Bookmarks live in native userdata.db and work offline: writes go to native, which queues them for the server, and
 *   `onDataChanged {scope:'bookmarks'}` reloads them into `user/setBookmarks`. The sync state goes to `setSyncState`.
 * - In the browser (`platform: 'web'`, development only) there is no native side: bookmarks go straight to the server
 *   through $axios, and subscribers get the same `onDataChanged` events.
 *
 * @param {{ plugin: object, store: { commit: Function }, platform?: string, $axios?: object }} deps
 */
export function createDataService({ plugin, store, platform = null, $axios = null }) {
  const isWeb = platform === 'web'
  const listeners = new Set()
  const dataListeners = new Set()
  const syncListeners = new Set()
  let eventCount = 0

  const apply = (payload) => {
    if (!payload?.state) return
    store.commit('setServerState', payload.state)
    store.commit('setActiveServerConfigId', payload.serverConfigId || null)
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

  const getBookmarks = async () => {
    if (isWeb) {
      const user = await $axios.$get('/api/me')
      return user?.bookmarks || []
    }
    const { bookmarks } = await plugin.getBookmarks()
    return bookmarks || []
  }

  const reloadBookmarks = async () => {
    const bookmarks = await getBookmarks()
    store.commit('user/setBookmarks', bookmarks)
    return bookmarks
  }

  const dataChanged = (payload) => {
    if (payload?.scope === 'bookmarks') {
      reloadBookmarks().catch((error) => console.error('[data] Could not reload bookmarks', error))
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

    /** Visible bookmarks of the active server: [{ libraryItemId, time, title, createdAt }]. */
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
        if (event !== 'user_updated' || !Array.isArray(payload?.bookmarks)) return { applied: false }
        store.commit('user/setBookmarks', payload.bookmarks)
        dataListeners.forEach((cb) => cb({ scope: 'bookmarks', ids: [] }))
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
