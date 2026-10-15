/**
 * What the app-bar connection indicator shows. `tone` maps to a text color in ConnectionIndicator.vue; `labelKey` is
 * the $strings key of the message shown on tap; `count` is the number of changes waiting to be sent (null when none);
 * `action` is the button the tap dialog offers: 'signIn', 'retry' (server unreachable), 'sync' (server online) or null.
 *
 * @param {{ serverState: string, networkConnected: boolean, hasServer: boolean, signedIn: boolean, socketConnected: boolean, syncState: object|null,
 *   attemptingConnection: boolean, networkConnectionType: string|null, isNetworkUnmetered: boolean }} input
 * @returns {{ icon: string|null, tone: 'ok'|'warning'|'error'|'muted'|'metered', labelKey: string|null, count: number|null, action: string|null }}
 */
export function indicatorModel({ serverState, networkConnected, hasServer, signedIn, socketConnected, syncState, attemptingConnection, networkConnectionType, isNetworkUnmetered }) {
  const pending = syncState?.pendingCount > 0 ? syncState.pendingCount : null
  const settled = networkConnected && !attemptingConnection
  const actionFor = (labelKey) => {
    if (labelKey === 'LabelSignInAgain') return 'signIn'
    if (settled && serverState === 'offline') return 'retry'
    if (settled && serverState === 'online') return 'sync'
    return null
  }
  const show = (icon, tone, labelKey) => ({ icon, tone, labelKey, count: pending, action: icon ? actionFor(labelKey) : null })
  // Offline the message is about what still has to reach the server, not about the connection
  const unsentOr = (labelKey) => (pending ? 'LabelChangesPending' : labelKey)

  if (!hasServer && !attemptingConnection) return show(null, 'muted', null)
  // Signed out on purpose (Disconnect) while the server is fine: nothing to report, the socket is closed on purpose
  if (!signedIn && !attemptingConnection && serverState === 'online') return show(null, 'muted', null)
  if (!networkConnected) return show('wifi_off', 'error', unsentOr('MessageNoNetworkConnection'))
  if (attemptingConnection) return show('cloud_sync', 'warning', 'MessageAttemptingServerConnection')
  if (serverState === 'unauthorized') return show('lock', 'error', 'LabelSignInAgain')
  if (serverState === 'offline') return show('cloud_off', 'warning', unsentOr('LabelServerUnreachable'))
  if (serverState === 'online' && syncState?.running) return show('cloud_sync', 'ok', 'LabelSyncing')
  if (serverState === 'online' && pending) return show('cloud_upload', 'warning', 'LabelChangesPending')

  // online, or unknown while the first probe is pending: the socket tells whether live updates arrive
  if (!socketConnected) return show('cloud_off', 'warning', 'MessageSocketNotConnected')
  const metered = !isNetworkUnmetered
  if (networkConnectionType === 'cellular') {
    return show('signal_cellular_alt', metered ? 'metered' : 'muted', metered ? 'MessageSocketConnectedOverMeteredCellular' : 'MessageSocketConnectedOverUnmeteredCellular')
  }
  return show('cloud_done', metered ? 'metered' : 'ok', metered ? 'MessageSocketConnectedOverMeteredWifi' : 'MessageSocketConnectedOverUnmeteredWifi')
}
