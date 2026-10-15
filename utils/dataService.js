/** Socket events about the catalog; native applies them to replica.db (`CATALOG_EVENTS` in CatalogEventApplier.kt). */
export const CATALOG_EVENTS = [
  'item_added',
  'item_updated',
  'items_added',
  'items_updated',
  'item_removed',
  'library_added',
  'library_updated',
  'library_removed',
  'series_added',
  'series_updated',
  'series_removed',
  'rss_feed_open',
  'rss_feed_closed',
  'author_updated',
  'backup_applied'
]

/**
 * The `catalog` topics a socket event changes, as native names them (`libraries`, `library:<id>`, `items:<id>`, ...).
 * A feed event has no library: it is taken for the current one. A missing library id sends no topic.
 */
export function catalogTopics(event, payload, currentLibraryId) {
  const topics = new Set()
  const lib = (id) => (typeof id === 'string' && id ? id : null)
  const item = (it) => {
    const libraryId = lib(it?.libraryId)
    if (!libraryId) return
    topics.add(`items:${libraryId}`)
    const series = it?.media?.metadata?.series
    if (Array.isArray(series) && series.length) topics.add(`series:${libraryId}`)
  }
  const family = (event || '').replace(/_(added|updated|removed|open|closed|applied)$/, '')
  if (family === 'items') (Array.isArray(payload) ? payload : [payload]).forEach(item)
  else if (family === 'item') item(payload)
  else if (family === 'library') {
    topics.add('libraries')
    if (lib(payload?.id)) topics.add(`library:${payload.id}`)
  } else if (family === 'series') {
    if (lib(payload?.libraryId)) topics.add(`series:${payload.libraryId}`)
  } else if (family === 'rss_feed') {
    if (payload?.entityType === 'libraryItem' && lib(currentLibraryId)) topics.add(`items:${currentLibraryId}`)
  } else if (family === 'author') {
    if (lib(payload?.libraryId)) topics.add(`authors:${payload.libraryId}`).add(`library:${payload.libraryId}`)
  } else if (family === 'collection') {
    if (lib(payload?.libraryId)) topics.add(`collections:${payload.libraryId}`)
  } else if (family === 'playlist') {
    if (lib(payload?.libraryId)) topics.add(`playlists:${payload.libraryId}`).add(`library:${payload.libraryId}`)
  } else if (family === 'backup') topics.add('libraries')
  return [...topics]
}

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
 * - The catalog (libraries, items) is read from native replica.db, at most 100 entities a call. While a library was
 *   never reconciled (or the filter needs data the replica lacks) and the server is online, the same request goes to
 *   the server instead. `onDataChanged {scope:'catalog'}` keeps the libraries in the store current.
 * - In the browser (`platform: 'web'`, development only) there is no native side: everything goes straight to the
 *   server through $axios, and subscribers get the same `onDataChanged` events.
 *
 * @param {{ plugin: object, store: { commit: Function, dispatch?: Function }, platform?: string, $axios?: object,
 *   eventBus?: { $emit: Function }, nativeHttp?: () => object }} deps `nativeHttp` returns $nativeHttp (injected later)
 */
export function createDataService({ plugin, store, platform = null, $axios = null, eventBus = null, nativeHttp = null }) {
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
      switchLibraries().catch((error) => console.error('[data] Could not read the libraries', error))
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

  const currentLibraryId = () => store.state?.libraries?.currentLibraryId || null
  const canDispatch = () => typeof store.dispatch === 'function'

  /** Another saved server became active: its libraries, and pages follow its current one. */
  const switchLibraries = async () => {
    if (!canDispatch()) return
    await store.dispatch('libraries/load', { force: true })
    if (currentLibraryId()) await store.dispatch('libraries/fetch', currentLibraryId())
    eventBus?.$emit('library-changed')
  }

  const catalogChanged = async (ids) => {
    if (!canDispatch()) return
    if (ids.includes('libraries')) {
      const before = currentLibraryId()
      await store.dispatch('libraries/load', { force: true })
      // The current library went away: filterdata, issues and playlists of the new one, and pages follow it
      if (currentLibraryId() && currentLibraryId() !== before) {
        await store.dispatch('libraries/fetch', currentLibraryId())
        eventBus?.$emit('library-changed')
        return
      }
    }
    if (currentLibraryId() && ids.includes(`library:${currentLibraryId()}`)) {
      await store.dispatch('libraries/fetch', { libraryId: currentLibraryId(), onlyIfCurrent: true })
    }
  }

  const dataChanged = (payload) => {
    const fail = (what) => (error) => console.error(`[data] Could not reload ${what}`, error)
    if (payload?.scope === 'catalog') {
      catalogChanged(payload.ids || []).catch(fail('the catalog'))
    } else if (payload?.scope === 'bookmarks') {
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
    if (current && !(progress.lastUpdate > current.lastUpdate)) return { applied: false, protected: false, currentTime: current.currentTime }
    store.commit('user/upsertMediaProgress', progress)
    dataListeners.forEach((cb) => cb({ scope: 'progress', ids: [progress.libraryItemId] }))
    return { applied: true, protected: false, currentTime: progress.currentTime }
  }

  // ---- Catalog reads
  const webReplica = () => ({ reconciledAt: Date.now(), complete: true })
  const libraryShape = (data) => ({ library: data?.library ?? null, filterdata: data?.filterdata ?? null, issues: data?.issues || 0, numUserPlaylists: data?.numUserPlaylists || 0 })
  const librariesShape = (data) => ({ libraries: data?.libraries || (Array.isArray(data) ? data : []) })
  // The filter is already in the URL form FilterModal makes ($encode), so it goes in as it is
  const itemsQuery = ({ sort, desc, filter, collapseseries, limit, page }) => {
    const parts = []
    if (filter && filter !== 'all') parts.push(`filter=${filter}`)
    if (sort) parts.push(`sort=${encodeURIComponent(sort)}`, `desc=${desc ? 1 : 0}`)
    if (collapseseries) parts.push('collapseseries=1')
    parts.push(`limit=${limit}`, `page=${page}`, 'minified=1', 'include=rssfeed,numEpisodesIncomplete')
    return parts.join('&')
  }
  const itemsPath = (args) => `/api/libraries/${encodeURIComponent(args.libraryId)}/items?${itemsQuery(args)}`

  /** Native's answer, asked again once when it was for a server that is no longer active. */
  const fromNative = async (call) => {
    const answer = await call()
    if ((answer?.serverConfigId ?? null) !== activeServer()) return call()
    return answer
  }

  /**
   * A library never reconciled (`replica.reconciledAt` null) or a filter the replica cannot answer reads the server
   * while it is online, as before the replica; the server's answer keeps native's `replica` and says `fromServer`.
   * Before the sign-in $nativeHttp has no server address, so native's answer stands; it must be signed in to the
   * active server, and an answer that arrives after a switch is dropped and the read [retry]-ed for the new server.
   */
  const withServerFallback = async (answer, path, shape, retry) => {
    const needsServer = answer?.replica?.reconciledAt == null || answer?.unsupportedFilter === true
    const http = typeof nativeHttp === 'function' ? nativeHttp() : null
    const server = activeServer()
    const signedInHere = !!server && store.state?.user?.serverConnectionConfig?.id === server
    if (!needsServer || !http || store.state?.serverState !== 'online' || !signedInHere) return answer
    try {
      const data = await http.get(path)
      if (activeServer() !== server) return retry()
      return { ...shape(data), serverConfigId: server, replica: answer?.replica ?? null, fromServer: true }
    } catch (error) {
      if (activeServer() !== server) return retry()
      console.warn('[data] Server read failed, showing the replica', error)
      return answer
    }
  }

  const getLibraries = async () => {
    if (isWeb) return { ...librariesShape(await $axios.$get('/api/libraries')), serverConfigId: activeServer(), replica: webReplica() }
    const answer = await fromNative(() => plugin.getLibraries())
    return withServerFallback(answer, '/api/libraries', librariesShape, getLibraries)
  }

  /** Rejects 'not found' when neither the replica nor (online) the server has the library. */
  const getLibrary = async ({ id }) => {
    const path = `/api/libraries/${encodeURIComponent(id)}?include=filterdata`
    let answer
    if (isWeb) {
      try {
        answer = { ...libraryShape(await $axios.$get(path)), serverConfigId: activeServer(), replica: webReplica() }
      } catch (error) {
        if (error?.response?.status !== 404) throw error
        answer = { ...libraryShape(null), serverConfigId: activeServer(), replica: webReplica() }
      }
    } else {
      answer = await withServerFallback(await fromNative(() => plugin.getLibrary({ id })), path, libraryShape, () => fromNative(() => plugin.getLibrary({ id })))
    }
    if (!answer?.library) throw new Error('not found')
    return answer
  }

  const getLibraryItems = async (args) => {
    const { libraryId, sort = null, desc = false, filter = null, collapseseries = false, limit, page = 0, sortingIgnorePrefix = false } = args
    if (isWeb) {
      const params = { limit, page, minified: 1, include: 'rssfeed,numEpisodesIncomplete' }
      if (filter && filter !== 'all') params.filter = filter
      if (sort) Object.assign(params, { sort, desc: desc ? 1 : 0 })
      if (collapseseries) params.collapseseries = 1
      const data = await $axios.$get(`/api/libraries/${encodeURIComponent(libraryId)}/items`, { params })
      return { ...data, serverConfigId: activeServer(), replica: webReplica() }
    }
    const request = { libraryId, sort, desc: !!desc, filter: filter && filter !== 'all' ? filter : null, collapseseries: !!collapseseries, limit, page, sortingIgnorePrefix: !!sortingIgnorePrefix }
    const answer = await fromNative(() => plugin.getLibraryItems(request))
    return withServerFallback(answer, itemsPath(request), (data) => data || {}, () => getLibraryItems(args))
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
    /**
     * A server progress record (the value fetched on refocus), merged by native's rule. Resolves `{ applied, protected,
     * currentTime }`: the position after the merge, and whether the device keeps its own (unsent change or listening).
     */
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
      if (isWeb && CATALOG_EVENTS.includes(event)) {
        // Nothing to apply in the browser: the shelves read the server again, as native's notice would make them
        dataChanged({ scope: 'catalog', ids: catalogTopics(event, payload, currentLibraryId()) })
        return { applied: false }
      }
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
    /** `{ serverConfigId, libraries, replica: { reconciledAt, complete } }`. */
    getLibraries,
    /** `{ serverConfigId, library, filterdata, issues, numUserPlaylists, replica }`. */
    getLibrary,
    /**
     * A page of a library's items in the server's shape, plus `replica` (and `fromServer`, `unsupportedFilter`).
     * `limit` at most 100.
     */
    getLibraryItems,
    /** The library native reconciles first. */
    async setCurrentLibrary({ libraryId }) {
      if (isWeb || !plugin.setCurrentLibrary) return
      await plugin.setCurrentLibrary({ libraryId })
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
