// Per-library view memory (bookshelf tab, sort and filter). The global user.settings sort/filter belong to `owner`, the
// library whose view they currently show; switching to another library snapshots them under the owner and applies the
// new library's saved view.

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

export function planViewSwitch(state, libraryId, currentSettings) {
  // First run with this memory: the current sort/filter are taken as the first fetched library's own
  if (!state.owner) return { state: { ...state, owner: libraryId }, settingsUpdate: null }
  if (state.owner === libraryId) return { state, settingsUpdate: null }

  const snapshot = validEntry(Object.fromEntries(VIEW_SETTINGS.map((key) => [key, currentSettings?.[key]])))
  const ownerEntry = { ...state.libraries[state.owner] }
  for (const key of VIEW_SETTINGS) delete ownerEntry[key]
  const libraries = { ...state.libraries, [state.owner]: { ...ownerEntry, ...snapshot } }

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
