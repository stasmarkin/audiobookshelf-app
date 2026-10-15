/**
 * $data: the WebView side of the native AbsData plugin. Mirrors the active server's reachability into the store
 * (`serverState`, `activeServerConfigId`) and forwards what the WebView's own transports saw about the server.
 * Reports are fire-and-forget: a failed report must never fail the request that triggered it.
 *
 * @param {{ plugin: object, store: { commit: Function } }} deps
 */
export function createDataService({ plugin, store }) {
  const listeners = new Set()
  let eventCount = 0

  const apply = (payload) => {
    if (!payload?.state) return
    store.commit('setServerState', payload.state)
    store.commit('setActiveServerConfigId', payload.serverConfigId || null)
  }

  plugin.addListener('onServerState', (payload) => {
    eventCount++
    apply(payload)
    listeners.forEach((cb) => cb(payload))
  })

  const getServerState = async () => {
    const before = eventCount
    const payload = await plugin.getServerState()
    // An event that arrived while we waited is newer than this answer
    if (eventCount === before) apply(payload)
    return payload
  }

  const quietly = (call) => {
    try {
      return Promise.resolve(call()).catch(() => {})
    } catch {
      return Promise.resolve()
    }
  }

  // Without this the indicator stays hidden until something else asks
  getServerState().catch((error) => console.error('[data] Could not read the server state', error))

  return {
    getServerState,
    retryServer: () => plugin.retryServer(),
    reportServerResponse: ({ address, status }) => quietly(() => plugin.reportServerResponse({ address, status })),
    reportSocketLost: () => quietly(() => plugin.reportSocketLost()),
    onServerState(cb) {
      listeners.add(cb)
      return () => listeners.delete(cb)
    }
  }
}
