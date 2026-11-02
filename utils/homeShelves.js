import { findLocalCopy } from '@/utils/localCopy'

/** Shelf types whose entities are library items, so a downloaded copy can be joined to them. */
const ITEM_SHELF_TYPES = ['book', 'podcast', 'episode']

/** The library media type a shelf belongs to: episodes are a podcast library's. */
const shelfMediaType = (shelf) => (shelf.type === 'episode' ? 'podcast' : shelf.type)

/**
 * The home page's shelves from `$absData.getPersonalized`'s answer and the page's local shelves.
 * The "continue" shelves of downloaded copies (`localOnly`) show only while the replica gives no "continue" of its own
 * (no active server, or a library native never reconciled read offline), so the home is the same online and offline.
 * `catalogNotLoaded`: an active server whose replica has nothing for this library yet.
 */
export function mergeHomeShelves({ answer, localShelves = [], localItems = [], activeServerConfigId = null, mediaType = null }) {
  const answerShelves = Array.isArray(answer?.shelves) ? answer.shelves : []
  const replicaNotRead = !!answer && answer.replica?.reconciledAt == null && !answer.fromServer
  const ofLibrary = (withLocalOnly) => localShelves.filter((s) => shelfMediaType(s) === mediaType && (withLocalOnly || !s.localOnly))

  if (answerShelves.length) {
    const shelves = answerShelves.map((shelf) => {
      if (!ITEM_SHELF_TYPES.includes(shelf.type) || !Array.isArray(shelf.entities)) return shelf
      const entities = shelf.entities.map((entity) => {
        const localLibraryItem = findLocalCopy(localItems, entity, activeServerConfigId)
        return localLibraryItem ? { ...entity, localLibraryItem } : entity
      })
      return { ...shelf, entities }
    })
    return { shelves: [...shelves, ...ofLibrary(replicaNotRead)], catalogNotLoaded: false }
  }
  if (answer && !replicaNotRead) return { shelves: ofLibrary(false), catalogNotLoaded: false }
  return { shelves: [...localShelves], catalogNotLoaded: !!activeServerConfigId }
}

const HOME_CATALOG_TOPICS = ['items', 'series', 'authors', 'episodes', 'state', 'library']

/**
 * Whether a notice of native's `onDataChanged` re-reads the home. A playback tick (every 15 s while playing) does only
 * when the playing item may not be first in "continue" yet: its key (`itemId`, or `itemId:episodeId` while an episode
 * plays) is neither `continueKeys[0]` nor `refreshedForKey`, the key the page already re-read for on a tick (so an
 * item that never gets first, such as one of another library, re-reads once, not every tick).
 */
export function shouldRefreshHome(change, { libraryId, continueKeys = [], playingEpisodeId = null, refreshedForKey = null }) {
  if (!change) return false
  const ids = change.ids || []
  if (change.scope === 'catalog') return !!libraryId && HOME_CATALOG_TOPICS.some((topic) => ids.includes(`${topic}:${libraryId}`))
  if (change.scope === 'progress' || change.scope === 'user') return true
  if (change.scope === 'playback') {
    return ids.some((id) => {
      const key = playingEpisodeId ? `${id}:${playingEpisodeId}` : id
      return key !== continueKeys[0] && key !== refreshedForKey
    })
  }
  return false
}

/** The keys of a "continue" shelf's entities in order, as `shouldRefreshHome` compares them. */
export function continueKeysOf(shelves) {
  const shelf = (shelves || []).find((s) => s.id === 'continue-listening')
  return (shelf?.entities || []).map((e) => (e.recentEpisode?.id ? `${e.id}:${e.recentEpisode.id}` : e.id))
}
