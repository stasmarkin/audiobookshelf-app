/**
 * What the app-bar connection indicator shows. `tone` maps to a text color in ConnectionIndicator.vue; `labelKey` is
 * the $strings key of the message shown on tap; `count` is reserved for pending sync work (null for now).
 *
 * @param {{ serverState: string, networkConnected: boolean, hasServer: boolean, socketConnected: boolean, syncState: object|null,
 *   attemptingConnection: boolean, networkConnectionType: string|null, isNetworkUnmetered: boolean }} input
 * @returns {{ icon: string|null, tone: 'ok'|'warning'|'error'|'muted'|'metered', labelKey: string|null, count: number|null }}
 */
export function indicatorModel({ serverState, networkConnected, hasServer, socketConnected, attemptingConnection, networkConnectionType, isNetworkUnmetered }) {
  const show = (icon, tone, labelKey) => ({ icon, tone, labelKey, count: null })

  if (!hasServer && !attemptingConnection) return show(null, 'muted', null)
  if (!networkConnected) return show('wifi_off', 'error', 'MessageNoNetworkConnection')
  if (attemptingConnection) return show('cloud_sync', 'warning', 'MessageAttemptingServerConnection')
  if (serverState === 'unauthorized') return show('lock', 'error', 'LabelSignInAgain')
  if (serverState === 'offline') return show('cloud_off', 'warning', 'LabelServerUnreachable')

  // online, or unknown while the first probe is pending: the socket tells whether live updates arrive
  if (!socketConnected) return show('cloud_off', 'warning', 'MessageSocketNotConnected')
  const metered = !isNetworkUnmetered
  if (networkConnectionType === 'cellular') {
    return show('signal_cellular_alt', metered ? 'metered' : 'muted', metered ? 'MessageSocketConnectedOverMeteredCellular' : 'MessageSocketConnectedOverUnmeteredCellular')
  }
  return show('cloud_done', metered ? 'metered' : 'ok', metered ? 'MessageSocketConnectedOverMeteredWifi' : 'MessageSocketConnectedOverUnmeteredWifi')
}
