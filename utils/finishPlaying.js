// How long marking an item finished waits for the player's final progress sync before writing anyway
export const FINAL_SYNC_TIMEOUT_MS = 30000

// Whether the playback session plays this book or episode, by server ids or by local (downloaded) ids
export function isSessionOf(session, { libraryItemId, episodeId, localLibraryItemId, localEpisodeId } = {}) {
  if (!session) return false
  if (libraryItemId && session.libraryItemId === libraryItemId && (session.episodeId || null) === (episodeId || null)) return true
  const sessionLocalItemId = session.localLibraryItem?.id
  return !!localLibraryItemId && sessionLocalItemId === localLibraryItemId && (session.localEpisodeId || null) === (localEpisodeId || null)
}

// The player must leave an item before it is marked finished: every progress sync that moves the position clears
// isFinished on the server and in local progress, so the item would come back as unfinished.
// endPlayback (AbsAudioPlayer.endCurrentItem) moves on to the next queue item or closes the player, and resolves once
// the left item's final sync is done; this waits for it, up to the timeout.
export async function endPlaybackOf(session, ids, endPlayback, timeoutMs = FINAL_SYNC_TIMEOUT_MS) {
  if (!isSessionOf(session, ids)) return false
  let timer
  const timeout = new Promise((resolve) => (timer = setTimeout(() => resolve(console.warn('[finishPlaying] Final progress sync timed out, marking anyway')), timeoutMs)))
  try {
    await Promise.race([Promise.resolve().then(endPlayback).catch((error) => console.error('[finishPlaying] Failed to end playback', error)), timeout])
  } finally {
    clearTimeout(timer)
  }
  return true
}
