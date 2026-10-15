/**
 * Where a progress write about this item goes ("finished", reset, e-book position):
 * - a server item, or a downloaded copy linked to a server item: `{ libraryItemId, episodeId, serverConfigId }`, the
 *   server ids and the saved server of the copy (null: the active server). It goes to $absData, which works offline and
 *   also updates the copy's own progress.
 * - a copy without a server link (or an episode without a server episode id): null; only its Paper progress changes.
 * A copy is linked by its saved server and server item id, not by the address of the current session, which is empty
 * after a cold start without a connection.
 *
 * @param {object} libraryItem a server library item, or a local one (`isLocal`)
 * @param {object} [episode] the episode of that item (server or local) for a podcast
 */
export function progressTarget(libraryItem, episode = null) {
  if (!libraryItem) return null
  if (!libraryItem.isLocal) return { libraryItemId: libraryItem.id, episodeId: episode?.id || null, serverConfigId: null }
  if (!libraryItem.libraryItemId || !libraryItem.serverConnectionConfigId) return null
  if (episode && !episode.serverEpisodeId) return null
  return { libraryItemId: libraryItem.libraryItemId, episodeId: episode?.serverEpisodeId || null, serverConfigId: libraryItem.serverConnectionConfigId }
}

/** Whether a downloaded copy belongs to the server that is active now (its server ids are usable for server pages). */
export function isCopyOfActiveServer(libraryItem, activeServerConfigId) {
  return !!libraryItem?.libraryItemId && !!activeServerConfigId && libraryItem.serverConnectionConfigId === activeServerConfigId
}
