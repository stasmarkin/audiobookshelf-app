const isLocalId = (id) => typeof id === 'string' && id.startsWith('local')

// The device copy's ids of a queue item (what older versions handed to native, and what a page that knows the copy plays)
function deviceQueueItem(item) {
  return {
    libraryItemId: item.localLibraryItem?.id || item.libraryItemId,
    episodeId: item.localLibraryItem ? item.localEpisode?.id || null : item.episodeId || null
  }
}

// The queue as native holds it: server ids, so native plays a downloaded copy when there is one and streams otherwise
// (PlaybackSourceResolver), plus ino and the copy's server to find a copy downloaded through another library. An item
// without a server id (Local Media) keeps its device ids.
export function nativeQueueItems(items, activeServerConfigId = null) {
  return items.map((item) => {
    const hasServerIds = item.libraryItemId && !isLocalId(item.libraryItemId) && !isLocalId(item.episodeId)
    if (!hasServerIds) return deviceQueueItem(item)
    const nativeItem = { libraryItemId: item.libraryItemId, episodeId: item.episodeId || null }
    const ino = item.libraryItem?.ino || item.localLibraryItem?.ino
    if (ino) nativeItem.ino = ino
    const serverConnectionConfigId = item.localLibraryItem?.serverConnectionConfigId || activeServerConfigId
    if (serverConnectionConfigId) nativeItem.serverConnectionConfigId = serverConnectionConfigId
    return nativeItem
  })
}

// Identity of a native queue entry, for every comparison of queue entries
export function queueItemKey(nativeItem) {
  return `${nativeItem.libraryItemId}|${nativeItem.episodeId || ''}`
}

export function queueItemKeyOf(item) {
  return queueItemKey(nativeQueueItems([item])[0])
}

// Whether a native queue entry is this display item: by its native ids, or by the device ids an older version saved
function nativeItemIs(nativeItem, item) {
  const key = queueItemKey(nativeItem)
  return key === queueItemKeyOf(item) || (!!item.localLibraryItem && key === queueItemKey(deviceQueueItem(item)))
}

export function nativeQueueMatches(nativeItems, items) {
  return nativeItems.length === items.length && items.every((item, i) => nativeItemIs(nativeItems[i], item))
}

// JS twin of PlaylistQueueItem.matches (QueueIdentity.kt): the entry names the session's server item or its device
// copy, or the copy the native resolver found by ino for the same account
export function queueItemMatchesSession(item, session) {
  if (!item || !session) return false
  const copy = session.localLibraryItem
  const serverItemId = copy?.libraryItemId || session.libraryItemId || null
  const episodeId = item.episodeId || null
  if (serverItemId && item.libraryItemId === serverItemId) return episodeId === (session.episodeId || null)
  if (copy && item.libraryItemId === copy.id) return episodeId === (session.localEpisodeId || null)
  if (copy && item.ino && item.ino === copy.ino && item.serverConnectionConfigId && item.serverConnectionConfigId === copy.serverConnectionConfigId) {
    return episodeId === (session.episodeId || null)
  }
  return false
}

// Up Next display for a queue the native player holds without this UI having set it: the UI restarted (reattach), or
// a widget/headset/notification Play restored the session and its queue while the UI was already open. The saved
// display copy is used only if it describes exactly the native queue (same items, same order, valid index) and, when
// the session is known, the native current item is that session's item. Returns { queue } or { reason }.
export function queueDisplayFromNative(saved, native, session = null) {
  const items = native?.items
  if (!items?.length) return { reason: 'no-native-queue' }
  const index = native.currentIndex
  if (!Number.isInteger(index) || index < 0 || index >= items.length) return { reason: 'invalid-index' }
  if (!saved?.items?.length) return { reason: 'no-saved-display' }
  if (!nativeQueueMatches(items, saved.items)) return { reason: 'mismatch' }
  if (session && !queueItemMatchesSession(items[index], session)) return { reason: 'session-mismatch' }
  return { queue: { ...saved, currentIndex: index } }
}

// What a Play from a queue sends: the device copy's ids when the page knows one (as before), the server ids alongside
export function queueItemPayload(item) {
  return {
    ...deviceQueueItem(item),
    serverLibraryItemId: item.libraryItemId,
    serverEpisodeId: item.episodeId || null
  }
}

// Local progress takes priority when it exists, matching the precedence used elsewhere in this
// file (resolveQueueSourceAppendItems, resolvePlaybackQueue's index selection) - falls back to
// server progress so an item with only server-side progress isn't treated as unstarted.
export function resolveItemProgress(context, item) {
  const localProgress = item.localLibraryItem && context.$store.getters['globals/getLocalMediaProgressById'](item.localLibraryItem.id, item.localEpisode?.id)
  return localProgress || context.$store.getters['user/getUserMediaProgress'](item.libraryItemId, item.episodeId)
}

// Prefers a local copy of each book when one exists and is complete, but no longer drops a book
// from the queue just because it isn't downloaded - it's included as a server-only (streamable)
// item instead, so a gap in the downloads doesn't skip that book during continuous playback.
// Books with no audio (e.g. an ebook-only series entry) are excluded entirely - there is nothing
// to play or stream for them. Local matching falls back to `ino` (same convention used for the
// downloaded-badge elsewhere) so a book downloaded via a different library it also belongs to is
// still recognized as local instead of being re-downloaded/streamed.
export function downloadedBookItems(books, localItems, serverConnectionConfigId) {
  return books.flatMap((book) => {
    if (book.isMissing || book.isInvalid) return []
    const expectedTracks = book.media?.numTracks || book.media?.tracks?.length || 0
    if (!expectedTracks) return []
    const localLibraryItem = localItems.find((local) => (local.libraryItemId === book.id || (book.ino && local.ino && local.ino === book.ino)) && local.serverConnectionConfigId === serverConnectionConfigId && local.id?.startsWith('local') && local.mediaType === 'book' && !local.isInvalid && local.media?.tracks?.length)
    const hasCompleteLocalCopy = localLibraryItem && localLibraryItem.media.tracks.length >= expectedTracks
    // Streamable items carry the raw book so the Up Next queue can display a title/cover for
    // them (queueItemDisplay's `item.libraryItem` branch) instead of falling back to "Unknown".
    return [{ libraryItemId: book.id, episodeId: null, localLibraryItem: hasCompleteLocalCopy ? localLibraryItem : null, libraryItem: hasCompleteLocalCopy ? null : book }]
  })
}

export async function fetchSeriesBooks(http, libraryId, seriesId, encode) {
  const books = []
  const ids = new Set()
  const limit = 100
  for (let page = 0; ; page++) {
    const query = new URLSearchParams({ filter: `series.${encode(seriesId)}`, sort: 'sequence', desc: '0', limit: String(limit), page: String(page), minified: '1' })
    const payload = await http.get(`/api/libraries/${libraryId}/items?${query}`)
    if (!Array.isArray(payload?.results) || !Number.isFinite(payload.total)) throw new Error('Failed to load the complete series')
    for (const book of payload.results) {
      if (ids.has(book.id)) throw new Error('Series changed while loading. Please try again.')
      ids.add(book.id)
      books.push(book)
    }
    if (books.length >= payload.total) return books
    if (!payload.results.length) throw new Error('Failed to load the complete series')
  }
}

export async function buildQueueSourceItems(context, source) {
  // Already resolved by the caller (playlist/podcast sources, or a series/collection resolved
  // up front by the Play-prompt flow to decide whether to prompt without a second fetch here).
  if (source.items) return source.items
  if (source.sourceType === 'series' || source.sourceType === 'collection') {
    const books = source.sourceType === 'series' ? await fetchSeriesBooks(context.$nativeHttp, source.libraryId, source.sourceId, context.$encode) : source.books
    const localItems = (await context.$db.getLocalLibraryItems('book')) || []
    return downloadedBookItems(books, localItems, context.$store.getters['user/getServerConnectionConfigId'])
  }
  throw new Error('Unknown playback queue source')
}

// Items to append to an already-playing queue: same source resolution as resolvePlaybackQueue,
// but filtered to unfinished items and deduped against what's already queued.
export async function resolveQueueSourceAppendItems(context, source, existingItems) {
  const items = await buildQueueSourceItems(context, source)
  const unfinished = items.filter((item) => {
    const localProgress = item.localLibraryItem && context.$store.getters['globals/getLocalMediaProgressById'](item.localLibraryItem.id, item.localEpisode?.id)
    const progress = localProgress || context.$store.getters['user/getUserMediaProgress'](item.libraryItemId, item.episodeId)
    return !progress?.isFinished
  })
  const existingKeys = new Set(existingItems.map(queueItemKeyOf))
  return unfinished.filter((item) => !existingKeys.has(queueItemKeyOf(item)))
}

// Shapes the active playback session as a queue item, for synthesizing a fresh queue
// when the user adds to queue while playing something with no existing playbackQueue.
export function sessionToQueueItem(session) {
  return {
    libraryItemId: session.libraryItemId,
    episodeId: session.episodeId || null,
    localLibraryItem: session.localLibraryItem || null,
    localEpisode: session.localEpisodeId ? { id: session.localEpisodeId } : null,
    displayTitle: session.displayTitle,
    displayAuthor: session.displayAuthor,
    coverPath: session.coverPath
  }
}

// Normalizes the three queue item shapes (session-synthesized, playlist-sourced with a
// nested .libraryItem/.episode, series/collection-sourced with only .localLibraryItem)
// into a consistent display shape for the queue UI.
export function queueItemDisplay(item) {
  if (item.displayTitle) {
    return { title: item.displayTitle, subtitle: item.displayAuthor || '', libraryItemForCover: item.localLibraryItem || item.libraryItem || null, coverPath: item.coverPath || null }
  }
  if (item.libraryItem) {
    const metadata = item.libraryItem.media?.metadata || {}
    return { title: item.episode?.title || metadata.title || 'Unknown', subtitle: metadata.authorName || metadata.author || '', libraryItemForCover: item.localLibraryItem || item.libraryItem, coverPath: null }
  }
  if (item.localLibraryItem) {
    const metadata = item.localLibraryItem.media?.metadata || {}
    return { title: metadata.title || 'Unknown', subtitle: metadata.authorName || metadata.author || '', libraryItemForCover: item.localLibraryItem, coverPath: null }
  }
  return { title: 'Unknown', subtitle: '', libraryItemForCover: null, coverPath: null }
}

export async function resolvePlaybackQueue(context, payload) {
  const source = payload.queueSource
  if (!source || context.$platform !== 'android') return { payload, queue: null }
  const items = await buildQueueSourceItems(context, source)
  let currentIndex
  if (payload.libraryItemId) {
    currentIndex = items.findIndex((item) => {
      const deviceItem = deviceQueueItem(item)
      const serverMatch = item.libraryItemId === (payload.serverLibraryItemId || payload.libraryItemId) && (item.episodeId || null) === (payload.serverEpisodeId || payload.episodeId || null)
      const localMatch = deviceItem.libraryItemId === payload.libraryItemId && deviceItem.episodeId === (payload.episodeId || null)
      return serverMatch || localMatch
    })
    // Undownloaded books are queue members that stream (see downloadedBookItems), so this only
    // happens when the selected item isn't a playable member of the source: it plays alone.
    if (currentIndex < 0) return { payload, queue: null }
  } else {
    currentIndex = items.findIndex((item) => {
      const localProgress = item.localLibraryItem && context.$store.getters['globals/getLocalMediaProgressById'](item.localLibraryItem.id, item.localEpisode?.id)
      const progress = localProgress || context.$store.getters['user/getUserMediaProgress'](item.libraryItemId, item.episodeId)
      return !progress?.isFinished
    })
    if (currentIndex < 0) throw new Error(items.length ? 'All downloaded books are finished' : 'No downloaded audiobooks available')
  }
  return {
    payload: { ...payload, ...queueItemPayload(items[currentIndex]) },
    queue: { sourceType: source.sourceType, sourceId: source.sourceId, items, currentIndex }
  }
}
