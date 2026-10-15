/**
 * When an open catalog shelf reads its loaded pages again, and a debouncer for it.
 * Native tells what changed with `onDataChanged`: `catalog` (ids are topics such as `items:<libraryId>`), `progress`
 * (an action or a server value) and `playback` (a tick while playing).
 */

/** The value of a filter as FilterModal sends it (`$encode`: URI-encoded base64); null when it does not decode. */
export function decodeFilterValue(encoded) {
  try {
    const binary = atob(decodeURIComponent(encoded))
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return null
  }
}

/** A first page from a replica whose reconcile never finished (or was unstable): what it shows may be partial. */
export function isReplicaIncomplete(answer) {
  return !answer?.fromServer && answer?.total > 0 && (answer?.replica?.reconciledAt == null || answer?.replica?.complete === false)
}

const CATALOG_TOPICS = {
  books: ['items', 'state'],
  series: ['series', 'items', 'state'],
  'series-books': ['series', 'items', 'state'],
  authors: ['authors', 'state'],
  collections: ['collections', 'items', 'state'],
  playlists: ['playlists', 'items', 'state']
}

/**
 * @param {{ scope: string, ids?: string[] }} change
 * @param {{ entity: string, libraryId: string, sort?: string|null, filter?: string|null, collapseSeries?: boolean }} shelf
 */
export function shouldRefreshShelf(change, { entity, libraryId, sort = null, filter = null, collapseSeries = false }) {
  if (!change || !libraryId) return false
  const ids = change.ids || []
  const group = filter && filter !== 'all' ? filter.split('.')[0] : null
  const isBooks = entity === 'books' || entity === 'series-books'

  if (change.scope === 'catalog') {
    const topics = [...(CATALOG_TOPICS[entity] || [])]
    // Series membership changes which books a series filter or a collapsed series card holds
    if (entity === 'books' && (group === 'series' || collapseSeries)) topics.push('series')
    return topics.some((topic) => ids.includes(`${topic}:${libraryId}`))
  }
  if (change.scope === 'progress') return isBooks && (!!sort?.startsWith('progress') || group === 'progress')
  if (change.scope === 'playback') {
    if (!isBooks) return false
    if (sort === 'progress') return true
    if (group !== 'progress') return false
    // The first tick moves a book from "not started" to "in progress"
    const value = decodeFilterValue(filter.slice(group.length + 1))
    return value === 'in-progress' || value === 'not-started'
  }
  return false
}

/** Runs the last scheduled function once nothing was scheduled for `delayMs`. */
export function createDebouncer({ delayMs, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let timer = null
  return {
    schedule(fn) {
      if (timer !== null) clearTimer(timer)
      timer = setTimer(() => {
        timer = null
        fn()
      }, delayMs)
    },
    cancel() {
      if (timer !== null) clearTimer(timer)
      timer = null
    }
  }
}
