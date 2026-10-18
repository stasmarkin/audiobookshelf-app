/** Covers in the app go through this path on the app's own origin; the native side answers from its cover cache. */
export const COVER_ROUTE_PREFIX = '/_absplus/cover/'

/**
 * The src of an item cover. Native: the cover route (no server address or token: the native side has both); `epoch`
 * counts native's "cover ready" notices, so a refused cover is asked for again once it is stored (native ignores it).
 * Web dev mode: the server URL exactly as before; `versioned: false` is the by-id form without `ts`.
 */
export function coverSrc({ itemId, updatedAt = null, raw = false, native, serverAddress, token = null, tokenRequired = false, versioned = true, epoch = 0, wait = false }) {
  if (native) {
    const query = new URLSearchParams()
    if (updatedAt) query.append('ts', updatedAt)
    if (raw) query.append('raw', '1')
    // The original is streamed from the server anyway: a stored standard copy is no reason to load it again
    if (epoch > 0 && !raw) query.append('e', epoch)
    // A canvas that draws once (Year in Review) waits for a missing cover instead of getting a 404
    if (wait) query.append('wait', '1')
    const qs = query.toString()
    return `${COVER_ROUTE_PREFIX}${encodeURIComponent(itemId)}${qs ? `?${qs}` : ''}`
  }
  if (!serverAddress) return null
  const url = new URL(`${serverAddress}/api/items/${itemId}/cover`)
  if (!versioned) return tokenRequired ? `${url}?token=${token}` : url.toString()
  const query = new URLSearchParams()
  query.append('ts', updatedAt || Date.now())
  if (raw) query.append('raw', '1')
  if (tokenRequired) query.append('token', token)
  return `${url}?${query}`
}

export function isCoverRoute(src) {
  return typeof src === 'string' && src.startsWith(COVER_ROUTE_PREFIX)
}

/** Group covers to draw: a cover route that failed to load (offline, never shown online) is left out. */
export function shownGroupCovers(covers, loaded) {
  return covers.filter((cover, i) => loaded[i] || !isCoverRoute(cover.coverUrl))
}
