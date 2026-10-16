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
