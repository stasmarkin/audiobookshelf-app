// History screen logic for the per-book listening journal (native MediaEventManager writes it).

const PLAYBACK_NAMES = ['Play', 'Pause', 'Stop', 'Finished']
const CLOSING_NAMES = ['Pause', 'Stop', 'Finished']
export const SLEEP_TIMER_DESCRIPTION = 'sleep_timer'

function dayStart(timestamp) {
  const date = new Date(timestamp)
  date.setHours(0, 0, 0, 0)
  return date.getTime()
}

// Pairs each Play with the next closing event in write order (the native side writes at the transition, so write order
// is transition order even when the device clock moved). Events written before ids existed get no duration.
function listenDurations(events) {
  const durations = new Map()
  let openPlay = null
  events.forEach((evt, index) => {
    if (evt.name === 'Play') {
      openPlay = index
    } else if (CLOSING_NAMES.includes(evt.name) && openPlay !== null) {
      const play = events[openPlay]
      const diff = evt.timestamp - play.timestamp
      durations.set(openPlay, play.id && evt.id && diff > 0 ? diff : null)
      openPlay = null
    }
  })
  return { durations, openPlay }
}

/**
 * Rows for the History screen grouped by local day, newest first. Compact view keeps the playback transitions;
 * `showAll` adds Save/Sync/Seek, collapsing runs of Save and Sync like the screen always did.
 */
export function buildHistoryRows(events, { showAll = false, nowPlaying = false } = {}) {
  const list = events || []
  const { durations, openPlay } = listenDurations(list)
  const rows = list.map((evt, index) => ({
    evt,
    index,
    durationMs: durations.get(index) ?? null,
    isNow: nowPlaying && index === openPlay,
    isSleepTimer: evt.name === 'Pause' && evt.description === SLEEP_TIMER_DESCRIPTION
  }))
  const visible = showAll ? rows : rows.filter((row) => PLAYBACK_NAMES.includes(row.evt.name))
  visible.sort((a, b) => b.evt.timestamp - a.evt.timestamp || b.index - a.index)

  const groups = []
  let collapse = null
  visible.forEach((row) => {
    const start = dayStart(row.evt.timestamp)
    let group = groups[groups.length - 1]
    if (!group || group.dayStart !== start) {
      group = { dayStart: start, rows: [] }
      groups.push(group)
      collapse = null
    }
    const kind = row.evt.name === 'Save' ? `Save-${row.evt.serverSyncAttempted}-${row.evt.serverSyncSuccess}` : row.evt.name === 'Sync' ? 'Sync' : null
    if (kind && collapse && collapse.kind === kind) {
      // "+N" counts the collapsed repeats after the shown one
      collapse.row.num = (collapse.row.num || 0) + 1
      return
    }
    const { index, ...shown } = row
    group.rows.push(shown)
    collapse = kind ? { kind, row: shown } : null
  })
  return groups
}

export function formatListenDuration(ms) {
  if (ms < 60000) return '<1 min'
  const minutes = Math.floor(ms / 60000)
  if (minutes < 60) return `${minutes} min`
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`
}

/**
 * What a tap on a journal entry plays: the same choice as a book page's Play (a usable local copy, or the server
 * item while casting), except the server address comes from the journal, which is newer than the copy's.
 * Returns null when the book is neither on this device nor on the connected server.
 */
export function pickPlayTarget({ history, localCopy, isCasting, currentServerAddress, currentUserId }) {
  if (!history) return null
  const serverItemId = history.isLocal ? null : history.libraryItemId || null
  const recordAddress = normalizeAddress(history.serverAddress || localCopy?.serverAddress)
  const current = normalizeAddress(currentServerAddress)
  // One server reached over two addresses (LAN and HTTPS) keeps its user id
  const sameServer = (!!recordAddress && recordAddress === current) || (!!history.serverUserId && history.serverUserId === currentUserId)
  const serverUsable = !!serverItemId && !!current && sameServer
  const hasLocal = history.isLocal || (!!localCopy?.id && !localCopy.isInvalid)

  const server = { libraryItemId: serverItemId, episodeId: history.episodeId || null, serverLibraryItemId: null }
  if (isCasting && serverUsable) return server
  if (hasLocal) {
    return {
      libraryItemId: history.isLocal ? history.libraryItemId : localCopy.id,
      episodeId: history.isLocal ? history.episodeId || null : null,
      serverLibraryItemId: serverUsable ? serverItemId : null
    }
  }
  return serverUsable ? server : null
}

function normalizeAddress(address) {
  if (!address) return null
  try {
    // Keep the path: two servers can sit behind one host under different paths
    const url = new URL(address)
    return `${url.protocol}//${url.host}${url.pathname.replace(/\/+$/, '')}`.toLowerCase()
  } catch {
    return String(address).replace(/\/+$/, '').toLowerCase()
  }
}

export function acceptsHistoryUpdate(routeId, incoming) {
  return !!incoming && !!routeId && incoming.id === routeId
}
