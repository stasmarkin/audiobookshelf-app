/** Tracks of a book: `numTracks` of a minified item (the replica), `tracks` of an expanded one (the server). */
export function trackCount(libraryItem) {
  const media = libraryItem?.media
  return media?.numTracks ?? media?.tracks?.length ?? 0
}

/** Books of a collection asked a page at a time; `$absData` answers at most 100 a call. */
export const COLLECTION_BOOKS_PAGE = 100

/**
 * Every book of a collection in its order ($absData: the replica, or the server before it was read); null when the
 * collection is gone. `serverConfigId`: the server the collection was read from. A list that moved while it was read (a
 * book twice, another count, an answer of another server or source) is read again once, then the read fails. A book
 * replaced by another keeps the count and is not caught here; the page reads again on the catalog change.
 */
export async function fetchCollectionBooks(absData, id, { serverConfigId } = {}, attempt = 0) {
  const books = []
  const ids = new Set()
  let server = serverConfigId
  let first = null
  const again = (message) => {
    if (attempt === 0) return fetchCollectionBooks(absData, id, { serverConfigId }, 1)
    throw new Error(message)
  }
  for (let page = 0; ; page++) {
    const payload = await absData.getCollectionBooks({ id, limit: COLLECTION_BOOKS_PAGE, page })
    if (payload?.found === false) return null
    if (!Array.isArray(payload?.results) || !Number.isFinite(payload.total)) throw new Error('Failed to load the collection')
    if (server === undefined) server = payload.serverConfigId
    if (payload.serverConfigId !== server) return again('The server changed while loading. Please try again.')
    // An offset into a list that grew, shrank or came from elsewhere skips or repeats books
    if (!first) first = payload
    else if (payload.total !== first.total || !!payload.fromServer !== !!first.fromServer) return again('Collection changed while loading. Please try again.')
    for (const book of payload.results) {
      if (ids.has(book.id)) return again('Collection changed while loading. Please try again.')
      ids.add(book.id)
      books.push(book)
    }
    if (books.length >= payload.total) return books
    if (!payload.results.length) return again('Failed to load the complete collection')
  }
}
