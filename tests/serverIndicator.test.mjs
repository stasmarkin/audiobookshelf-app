// The connection indicator in the app bar (components/widgets/ConnectionIndicator.vue) as a pure model: which icon,
// tone and message a combination of server reachability, network and socket shows.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../utils/serverIndicator.js', import.meta.url), 'utf8')
const { indicatorModel } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

const live = { serverState: 'online', networkConnected: true, hasServer: true, signedIn: true, socketConnected: true, syncState: null, attemptingConnection: false, networkConnectionType: 'wifi', isNetworkUnmetered: true }
const model = (overrides) => indicatorModel({ ...live, ...overrides })

test('indicator shows offline for a dead server on live Wi-Fi', () => {
  const m = model({ serverState: 'offline', socketConnected: false })
  assert.equal(m.labelKey, 'LabelServerUnreachable')
  assert.equal(m.tone, 'warning')
  assert.equal(m.icon, 'cloud_off')
})

test('indicator shows no network', () => {
  for (const serverState of ['online', 'offline', 'unknown', 'unauthorized']) {
    const m = model({ serverState, networkConnected: false, attemptingConnection: true })
    assert.equal(m.labelKey, 'MessageNoNetworkConnection', serverState)
    assert.equal(m.icon, 'wifi_off')
    assert.equal(m.tone, 'error')
  }
})

test('indicator asks to sign in again', () => {
  const m = model({ serverState: 'unauthorized', socketConnected: false })
  assert.equal(m.labelKey, 'LabelSignInAgain')
  assert.equal(m.tone, 'error')
})

test('indicator is hidden without a configured server', () => {
  assert.equal(model({ hasServer: false, serverState: 'unknown' }).icon, null)
  assert.equal(model({ hasServer: false, serverState: 'unknown', attemptingConnection: true }).icon, 'cloud_sync', 'first connection in progress')
})

test('indicator keeps current online states', () => {
  assert.equal(model({ attemptingConnection: true, socketConnected: false }).icon, 'cloud_sync')
  assert.equal(model({ attemptingConnection: true }).labelKey, 'MessageAttemptingServerConnection')

  const cellular = model({ networkConnectionType: 'cellular' })
  assert.equal(cellular.icon, 'signal_cellular_alt')
  assert.equal(cellular.tone, 'muted')
  assert.equal(cellular.labelKey, 'MessageSocketConnectedOverUnmeteredCellular')
  assert.equal(model({ networkConnectionType: 'cellular', isNetworkUnmetered: false }).labelKey, 'MessageSocketConnectedOverMeteredCellular')

  const metered = model({ isNetworkUnmetered: false })
  assert.equal(metered.tone, 'metered')
  assert.equal(metered.icon, 'cloud_done')
  assert.equal(metered.labelKey, 'MessageSocketConnectedOverMeteredWifi')

  const wifi = model({})
  assert.deepEqual([wifi.icon, wifi.tone, wifi.labelKey], ['cloud_done', 'ok', 'MessageSocketConnectedOverUnmeteredWifi'])
})

test('a live server without its socket still shows the socket warning', () => {
  for (const serverState of ['online', 'unknown']) {
    const m = model({ serverState, socketConnected: false })
    assert.deepEqual([m.icon, m.tone, m.labelKey], ['cloud_off', 'warning', 'MessageSocketNotConnected'], serverState)
  }
})

test('indicator is hidden after a deliberate disconnect while the server is fine', () => {
  assert.equal(model({ signedIn: false, socketConnected: false }).icon, null)
  assert.equal(model({ signedIn: false, socketConnected: false, attemptingConnection: true }).icon, 'cloud_sync')
  assert.equal(model({ signedIn: false, serverState: 'offline', socketConnected: false }).labelKey, 'LabelServerUnreachable', 'offline cold start')
  assert.equal(model({ signedIn: false, serverState: 'unauthorized', socketConnected: false }).labelKey, 'LabelSignInAgain')
})

test('indicator shows pending changes', () => {
  const offline = model({ serverState: 'offline', socketConnected: false, syncState: { running: false, pendingCount: 3 } })
  assert.deepEqual([offline.labelKey, offline.count, offline.icon], ['LabelChangesPending', 3, 'cloud_off'])

  const airplane = model({ networkConnected: false, socketConnected: false, syncState: { running: false, pendingCount: 2 } })
  assert.deepEqual([airplane.labelKey, airplane.count], ['LabelChangesPending', 2])

  const syncing = model({ syncState: { running: true, pendingCount: 1 } })
  assert.deepEqual([syncing.labelKey, syncing.icon], ['LabelSyncing', 'cloud_sync'])

  const waiting = model({ syncState: { running: false, pendingCount: 1 } })
  assert.equal(waiting.labelKey, 'LabelChangesPending', 'online with changes held back (backoff)')

  assert.equal(model({ syncState: { running: false, pendingCount: 0 } }).count, null)
  assert.equal(model({ serverState: 'offline', socketConnected: false, syncState: { pendingCount: 0 } }).labelKey, 'LabelServerUnreachable')
})

test('the tap dialog offers Sync now while online, Retry while unreachable, nothing without a network', () => {
  assert.equal(model({}).action, 'sync')
  assert.equal(model({ syncState: { running: true, pendingCount: 1 } }).action, 'sync')
  assert.equal(model({ serverState: 'offline', socketConnected: false }).action, 'retry')
  assert.equal(model({ serverState: 'offline', socketConnected: false, syncState: { pendingCount: 2 } }).action, 'retry', 'pending changes still offer Retry')
  assert.equal(model({ networkConnected: false, syncState: { pendingCount: 2 } }).action, null)
  assert.equal(model({ attemptingConnection: true }).action, null)
  assert.equal(model({ serverState: 'unauthorized', socketConnected: false }).action, 'signIn')
  assert.equal(model({ hasServer: false, serverState: 'unknown' }).action, null)
})

async function indicator(state) {
  const vm = await import('node:vm')
  const component = (await readFile(new URL('../components/widgets/ConnectionIndicator.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const dialogs = []
  const sandbox = {
    indicatorModel,
    Dialog: {
      confirm: async (opts) => (dialogs.push(['confirm', opts.okButtonTitle, opts.message]), { value: true }),
      alert: async (opts) => dialogs.push(['alert', opts.message])
    }
  }
  vm.runInNewContext(component.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  const calls = []
  const strings = { HeaderConnectionStatus: 'Status', ButtonSyncNow: 'Sync now', ButtonRetry: 'Retry', ButtonCancel: 'Cancel', LabelChangesPending: '{0} pending', LabelServerUnreachable: 'down', MessageSocketConnectedOverUnmeteredWifi: 'fine' }
  const ctx = {
    $store: { state: { ...live, user: { user: { id: 'u' } }, ...state }, getters: { getHasActiveServer: true } },
    $strings: strings,
    $getString: (key, subs) => strings[key].replace('{0}', subs[0]),
    $absData: { syncNow: async () => calls.push('syncNow'), retryServer: async () => calls.push('retryServer') },
    $router: { push: (path) => calls.push(path) }
  }
  Object.defineProperty(ctx, 'model', { get: () => sandbox.component.computed.model.call(ctx) })
  await sandbox.component.methods.showAlertDialog.call(ctx)
  return { calls, dialogs }
}

test('indicator dialog sync button calls syncNow', async () => {
  const online = await indicator({ syncState: { running: false, pendingCount: 0 } })
  assert.deepEqual(online.calls, ['syncNow'])
  assert.deepEqual(online.dialogs, [['confirm', 'Sync now', 'fine']])

  const offline = await indicator({ serverState: 'offline', socketConnected: false, syncState: { running: false, pendingCount: 3 } })
  assert.deepEqual(offline.calls, ['retryServer'], 'no Sync now while the server is unreachable')
  assert.deepEqual(offline.dialogs, [['confirm', 'Retry', '3 pending']])
})
