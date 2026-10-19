import { AbsDatabase } from './capacitor/AbsDatabase'
import { createAuthRenewal } from '@/utils/authRenewal'

// Shared access-token renewal for $nativeHttp, $axios and the PDF reader (utils/authRenewal.js, docs/authentication.md)
export default function ({ store, $socket }, inject) {
  /**
   * The server refused the refresh token: only the live session ends (user/revokeSession). The app stays where it is
   * with the library, progress and bookmarks kept on the device; the indicator asks to sign in again, which opens the
   * connect form for this server.
   */
  const signInAgain = async (serverConnectionConfigId) => {
    const activeId = store.getters['user/getServerConnectionConfigId']
    // A newer session for another server must not be signed out by an old server's failure.
    if (activeId && activeId !== serverConnectionConfigId) return
    if (activeId) await store.dispatch('user/revokeSession')
    store.commit('setServerState', 'unauthorized')
  }

  const authRenewal = createAuthRenewal({
    refresh: (serverConnectionConfigId) => AbsDatabase.refreshAccessToken({ serverConnectionConfigId }),
    getSession: () => ({
      serverConnectionConfigId: store.getters['user/getServerConnectionConfigId'],
      token: store.getters['user/getToken']
    }),
    onRenewed: (serverConnectionConfigId, accessToken, isActive) => {
      if (!isActive) return
      store.commit('user/setAccessToken', accessToken)
      // Re-authenticate socket if necessary
      if ($socket?.connected && !$socket.isAuthenticated) {
        $socket.sendAuthenticate()
      }
    },
    onRejected: (serverConnectionConfigId) => {
      signInAgain(serverConnectionConfigId).catch((error) => console.error('[authRenewal] Failed to sign out', error))
    }
  })

  // Logout or a switch to another server invalidates renewals that are still running.
  let activeId = store.getters['user/getServerConnectionConfigId']
  store.subscribe((mutation) => {
    if (mutation.type === 'user/logout') {
      activeId = null
      authRenewal.invalidate()
    } else if (mutation.type === 'user/setServerConnectionConfig') {
      const id = mutation.payload?.id || null
      if (activeId && id !== activeId) authRenewal.invalidate()
      activeId = id
    }
  })

  authRenewal.signInAgain = signInAgain
  inject('authRenewal', authRenewal)
}
