/** Tracks of a book: `numTracks` of a minified item (the replica), `tracks` of an expanded one (the server). */
export function trackCount(libraryItem) {
  const media = libraryItem?.media
  return media?.numTracks ?? media?.tracks?.length ?? 0
}

/** Books of a collection and items of a playlist are asked a page at a time; `$absData` answers at most 100 a call. */
export const COLLECTION_BOOKS_PAGE = 100
export const PLAYLIST_ITEMS_PAGE = 100

/**
 * Every entry of a paged list in its order; null when the list is gone (`found: false`). `serverConfigId`: the server
 * the list was read from. A list that moved while it was read (an entry twice, another count, an answer of another
 * server or source) is read again once, then the read fails. An entry replaced by another keeps the count and is not
 * caught here; the page reads again on the catalog change.
 */
async function fetchEveryPage(readPage, keyOf, noun, serverConfigId, attempt = 0) {
  const entries = []
  const keys = new Set()
  let server = serverConfigId
  let first = null
  const again = (message) => {
    if (attempt === 0) return fetchEveryPage(readPage, keyOf, noun, serverConfigId, 1)
    throw new Error(message)
  }
  for (let page = 0; ; page++) {
    const payload = await readPage(page)
    if (payload?.found === false) return null
    if (!Array.isArray(payload?.results) || !Number.isFinite(payload.total)) throw new Error(`Failed to load the ${noun}`)
    if (server === undefined) server = payload.serverConfigId
    if (payload.serverConfigId !== server) return again('The server changed while loading. Please try again.')
    // An offset into a list that grew, shrank or came from elsewhere skips or repeats entries
    if (!first) first = payload
    else if (payload.total !== first.total || !!payload.fromServer !== !!first.fromServer) return again(`${noun[0].toUpperCase()}${noun.slice(1)} changed while loading. Please try again.`)
    for (const entry of payload.results) {
      const key = keyOf(entry)
      if (keys.has(key)) return again(`${noun[0].toUpperCase()}${noun.slice(1)} changed while loading. Please try again.`)
      keys.add(key)
      entries.push(entry)
    }
    if (entries.length >= payload.total) return entries
    if (!payload.results.length) return again(`Failed to load the complete ${noun}`)
  }
}

/** Every book of a collection in its order ($absData: the replica, or the server before it was read); null when it is gone. */
export function fetchCollectionBooks(absData, id, { serverConfigId } = {}) {
  return fetchEveryPage((page) => absData.getCollectionBooks({ id, limit: COLLECTION_BOOKS_PAGE, page }), (book) => book.id, 'collection', serverConfigId)
}

/** Every item (book or episode) of a playlist in its order ($absData); null when it is gone. */
export function fetchPlaylistItems(absData, id, { serverConfigId } = {}) {
  return fetchEveryPage(
    (page) => absData.getPlaylistItems({ id, limit: PLAYLIST_ITEMS_PAGE, page }),
    (item) => `${item.libraryItemId}|${item.episodeId || ''}`,
    'playlist',
    serverConfigId
  )
}
