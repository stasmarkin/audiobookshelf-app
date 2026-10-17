import { registerPlugin, WebPlugin } from '@capacitor/core'

// Offline-first data bridge. The native side (android/.../sync) tracks whether the active server answers and keeps the
// user's bookmarks and their outbox. This web fallback (browser development) always reports the server online and sends
// no events; in the browser $absData sends bookmarks to the server itself (utils/dataService.js).
class AbsDataWeb extends WebPlugin {
  async getServerState() {
    let serverConfigId = null
    try {
      serverConfigId = JSON.parse(localStorage.getItem('device') || 'null')?.lastServerConnectionConfigId || null
    } catch {
      serverConfigId = null
    }
    return { state: 'online', serverConfigId }
  }

  async retryServer() {}

  async reportServerResponse() {}

  async reportServerUnreachable() {}

  async reportSocketLost() {}

  async getBookmarks() {
    return { bookmarks: [] }
  }

  async syncNow() {}

  async getSyncState() {
    return { running: false, pendingCount: 0, lastError: null, lastSyncAt: null }
  }

  async applyServerEvent() {
    return { applied: false }
  }

  // The catalog is read from the server in the browser (utils/dataService.js); these answer an empty replica
  async getLibraries() {
    return { serverConfigId: null, libraries: [], replica: { reconciledAt: null, complete: false } }
  }

  async getLibrary() {
    return { serverConfigId: null, library: null, filterdata: null, issues: 0, numUserPlaylists: 0, replica: { reconciledAt: null, complete: false } }
  }

  async getLibraryItems({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, replica: { reconciledAt: null, complete: false } }
  }

  async getSeries({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, replica: { reconciledAt: null, complete: false } }
  }

  async getSeriesById() {
    return { serverConfigId: null, series: null, replica: { reconciledAt: null, complete: false } }
  }

  async getAuthors({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, replica: { reconciledAt: null, complete: false } }
  }

  async getCollections({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, replica: { reconciledAt: null, complete: false } }
  }

  async getCollection() {
    return { serverConfigId: null, collection: null, replica: { reconciledAt: null, complete: false } }
  }

  async getCollectionBooks({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, found: false, replica: { reconciledAt: null, complete: false } }
  }

  async getPlaylists({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, replica: { reconciledAt: null, complete: false } }
  }

  async getPlaylist() {
    return { serverConfigId: null, playlist: null, replica: { reconciledAt: null, complete: false } }
  }

  async getPlaylistItems({ limit, page }) {
    return { serverConfigId: null, results: [], total: 0, limit, page, found: false, replica: { reconciledAt: null, complete: false } }
  }

  async getPersonalized({ libraryId }) {
    return { serverConfigId: null, libraryId, shelves: [], replica: { reconciledAt: null, complete: false } }
  }

  async search({ libraryId, q }) {
    const results = { book: [], podcast: [], episodes: [], series: [], authors: [], narrators: [], tags: [], genres: [] }
    return { serverConfigId: null, libraryId, q, results, replica: { reconciledAt: null, complete: false } }
  }

  async getLibraryItem() {
    return { serverConfigId: null, libraryItem: null, detailLevel: null, replica: { reconciledAt: null, complete: false } }
  }

  async getRecentEpisodes({ limit, page }) {
    return { serverConfigId: null, episodes: [], total: 0, limit, page, replica: { reconciledAt: null, complete: false } }
  }

  async setCurrentLibrary() {}
}

const AbsData = registerPlugin('AbsData', {
  web: () => new AbsDataWeb()
})

export { AbsData }
