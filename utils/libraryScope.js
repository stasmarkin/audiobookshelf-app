/**
 * Whether a page showing an entity of [ownLibraryId], loaded for [loadedServerConfigId], has to leave on `library-changed`.
 * The event comes also when nothing changed (a sign-in, a reconnect), without saying which library: the store decides.
 * Another current library, or another active server (two accounts of one server share library ids), takes the page away.
 */
export function leavesOnLibraryChange(state, ownLibraryId, loadedServerConfigId) {
  const current = state?.libraries?.currentLibraryId || null
  if (current && current !== ownLibraryId) return true
  return (state?.activeServerConfigId ?? null) !== (loadedServerConfigId ?? null)
}

/**
 * The same for a downloaded copy's page: it plays from the device whatever library is current, so only another active
 * server takes it away, and only when the copy is of another server than the one now active (Local Media has none).
 * [lastServerConfigId]: the active server at the previous event the page stayed on (the page keeps it current).
 */
export function copyLeavesOnLibraryChange(state, copy, lastServerConfigId) {
  const active = state?.activeServerConfigId ?? null
  // No active server (forgotten): nothing the copy could be confused with
  if (!active || active === (lastServerConfigId ?? null)) return false
  return !!copy?.serverConnectionConfigId && copy.serverConnectionConfigId !== active
}
