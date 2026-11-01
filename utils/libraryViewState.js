// Per-library view memory (bookshelf tab, sort and filter). The global user.settings sort/filter belong to `owner`, the
// library whose view they currently show; switching to another library snapshots them under the owner and applies the
// new library's saved view. Each account (saved server connection + user) has its own memory, kept in `scopes`.

// Add Podcast is an action rather than a section, so it is never restored
export const REMEMBERED_TABS = ['bookshelf', 'bookshelf-latest', 'bookshelf-library', 'bookshelf-series', 'bookshelf-collections', 'bookshelf-authors', 'bookshelf-playlists']

const VIEW_SETTINGS = ['mobileOrderBy', 'mobileOrderDesc', 'mobileFilterBy']

export function tabPath(routeName) {
  return routeName === 'bookshelf' ? '/bookshelf' : `/bookshelf/${routeName.slice('bookshelf-'.length)}`
}

// Must list the same routes, in the same order, as BookshelfNavBar's items (a test compares them)
export function bookshelfTabs({ mediaType, hasPlaylists, isAdmin }) {
  const tabs = mediaType === 'podcast' ? ['bookshelf', 'bookshelf-latest', 'bookshelf-library', ...(isAdmin ? ['bookshelf-add-podcast'] : [])] : ['bookshelf', 'bookshelf-library', 'bookshelf-series', 'bookshelf-collections', 'bookshelf-authors']
  if (hasPlaylists) tabs.push('bookshelf-playlists')
  return tabs
}

export function emptyViewState() {
  return { owner: null, libraries: {} }
}

const isObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value)
const isNonEmptyString = (value) => typeof value === 'string' && value.length > 0

function validEntry(raw) {
  const entry = {}
  if (!isObject(raw)) return entry
  if (REMEMBERED_TABS.includes(raw.tab)) entry.tab = raw.tab
  if (isNonEmptyString(raw.mobileOrderBy)) entry.mobileOrderBy = raw.mobileOrderBy
  if (typeof raw.mobileOrderDesc === 'boolean') entry.mobileOrderDesc = raw.mobileOrderDesc
  if (isNonEmptyString(raw.mobileFilterBy)) entry.mobileFilterBy = raw.mobileFilterBy
  return entry
}

export function parseLibraryViewState(raw) {
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    return emptyViewState()
  }
  if (!isObject(parsed)) return emptyViewState()
  const libraries = {}
  if (isObject(parsed.libraries)) {
    for (const [libraryId, entry] of Object.entries(parsed.libraries)) {
      const valid = validEntry(entry)
      if (Object.keys(valid).length) libraries[libraryId] = valid
    }
  }
  return { owner: isNonEmptyString(parsed.owner) ? parsed.owner : null, libraries }
}

// Path of the library's saved tab when the library still has it, Home otherwise
export function restoreTab(state, libraryId, availableTabs) {
  const tab = state.libraries[libraryId]?.tab
  return tab && availableTabs.includes(tab) ? tabPath(tab) : '/bookshelf'
}

export function recordTab(state, libraryId, routeName) {
  if (!REMEMBERED_TABS.includes(routeName)) return null
  const entry = state.libraries[libraryId] || {}
  if (entry.tab === routeName) return null
  return { ...state, libraries: { ...state.libraries, [libraryId]: { ...entry, tab: routeName } } }
}

// The owner keeps the sort/filter that are currently set
function snapshotOwner(state, currentSettings) {
  const snapshot = validEntry(Object.fromEntries(VIEW_SETTINGS.map((key) => [key, currentSettings?.[key]])))
  const ownerEntry = { ...state.libraries[state.owner] }
  for (const key of VIEW_SETTINGS) delete ownerEntry[key]
  return { ...state.libraries, [state.owner]: { ...ownerEntry, ...snapshot } }
}

export function planViewSwitch(state, libraryId, currentSettings) {
  if (!state.owner) {
    const saved = state.libraries[libraryId] || {}
    const settingsUpdate = Object.fromEntries(VIEW_SETTINGS.filter((key) => key in saved).map((key) => [key, saved[key]]))
    // Another account's series/author filter would show an empty shelf here
    if (state.resetSettings && !('mobileFilterBy' in settingsUpdate)) settingsUpdate.mobileFilterBy = 'all'
    // First run with this memory: the current sort/filter are taken as the first fetched library's own
    return { state: { owner: libraryId, libraries: state.libraries }, settingsUpdate: Object.keys(settingsUpdate).length ? settingsUpdate : null }
  }
  if (state.owner === libraryId) return { state, settingsUpdate: null }

  const libraries = snapshotOwner(state, currentSettings)

  const saved = state.libraries[libraryId] || {}
  const settingsUpdate = Object.fromEntries(VIEW_SETTINGS.filter((key) => key in saved).map((key) => [key, saved[key]]))
  // Another library's series/author filter would show an empty shelf here
  if (!('mobileFilterBy' in settingsUpdate)) settingsUpdate.mobileFilterBy = 'all'
  return { state: { owner: libraryId, libraries }, settingsUpdate }
}

export function otherLibraryId(libraries, currentId) {
  if (!Array.isArray(libraries) || libraries.length !== 2) return null
  return libraries.find((library) => library.id !== currentId)?.id || null
}

const hasViewContent = (state) => !!state && (!!state.owner || Object.keys(state.libraries || {}).length > 0)

// One memory per account: the saved server connection plus the user on it. The connection id tells servers (and a saved
// connection added again) apart, the user id tells accounts on the same server apart. Without both there is no memory.
export function viewScopeKey(serverConnectionConfigId, userId) {
  return isNonEmptyString(serverConnectionConfigId) && isNonEmptyString(userId) ? `${serverConnectionConfigId}|${userId}` : null
}

// Stored form: { version: 2, active: scopeKey, scopes: { [scopeKey]: viewState } }; anything invalid is dropped. `active` is the
// account the live sort/filter (user.settings) were last used by: after a restart they still belong to it.
export function parseLibraryViewMemory(raw) {
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    return { scopes: {}, active: null }
  }
  const scopes = {}
  if (!isObject(parsed) || parsed.version !== 2) return { scopes, active: null }
  if (isObject(parsed.scopes)) {
    for (const [scope, entry] of Object.entries(parsed.scopes)) {
      const state = isObject(entry) ? parseLibraryViewState(JSON.stringify(entry)) : emptyViewState()
      if (scope && hasViewContent(state)) scopes[scope] = state
    }
  }
  return { scopes, active: isNonEmptyString(parsed.active) ? parsed.active : null }
}

export function parseLibraryViewScopes(raw) {
  return parseLibraryViewMemory(raw).scopes
}

// What is written: every other account's memory as it was, the active one's current memory, and who the active one is
export function serializeLibraryViewScopes(scopes, activeScope, viewState) {
  const all = { ...scopes }
  if (activeScope && hasViewContent(viewState)) all[activeScope] = { owner: viewState.owner, libraries: viewState.libraries }
  return JSON.stringify({ version: 2, active: activeScope || null, scopes: all })
}

// Another account (or none) becomes active. The account that is left keeps what it showed (the owner library's sort/filter
// are snapshotted) and no longer owns the global settings; the new account gets its own memory. `legacy` is the unscoped
// memory of the first build with this feature: it goes to the new account only when `claimLegacy` says the account is
// unambiguous, otherwise it is left alone.
export function activateViewScope({ scopes, scope, viewState }, nextScope, currentSettings, { legacy = null, claimLegacy = false } = {}) {
  const next = { ...scopes }
  const leaving = !!scope && hasViewContent(viewState)
  if (leaving) next[scope] = { owner: null, libraries: viewState.owner ? snapshotOwner(viewState, currentSettings) : viewState.libraries }
  if (!nextScope) return { scopes: next, scope: null, viewState: emptyViewState(), legacyClaimed: false }
  const known = next[nextScope]
  if (known) return { scopes: next, scope: nextScope, viewState: scope ? { ...known, resetSettings: true } : known, legacyClaimed: false }
  if (claimLegacy && hasViewContent(legacy)) return { scopes: next, scope: nextScope, viewState: { owner: legacy.owner, libraries: legacy.libraries }, legacyClaimed: true }
  return { scopes: next, scope: nextScope, viewState: scope ? { ...emptyViewState(), resetSettings: true } : emptyViewState(), legacyClaimed: false }
}
