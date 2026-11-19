/**
 * The downloaded copy of a server item: by `libraryItemId`, or by `ino` when the item was re-created on the server.
 * With an active server only its own copies count, since replicas of two accounts on one server share item ids;
 * without one any copy does.
 */
export function findLocalCopy(localItems, entity, activeServerConfigId) {
  if (!entity || !Array.isArray(localItems)) return null
  return (
    localItems.find((lli) => {
      if (activeServerConfigId && lli.serverConnectionConfigId !== activeServerConfigId) return false
      // Auto delete evicts the files but keeps the record and its history: not a downloaded copy
      if (lli.isInvalid) return false
      return lli.libraryItemId == entity.id || (entity.ino && lli.ino && lli.ino == entity.ino)
    }) || null
  )
}
