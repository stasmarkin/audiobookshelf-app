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
}

const AbsData = registerPlugin('AbsData', {
  web: () => new AbsDataWeb()
})

export { AbsData }
