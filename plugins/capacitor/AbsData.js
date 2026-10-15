import { registerPlugin, WebPlugin } from '@capacitor/core'

// Offline-first data bridge. The native side (android/.../sync) tracks whether the active server answers; this web
// fallback (browser development) always reports it online and sends no events.
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
}

const AbsData = registerPlugin('AbsData', {
  web: () => new AbsDataWeb()
})

export { AbsData }
