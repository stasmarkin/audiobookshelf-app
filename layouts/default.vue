<template>
  <div class="w-full layout-wrapper bg-bg">
    <app-appbar />
    <div id="content" class="overflow-hidden relative" :class="isPlayerOpen ? 'playerOpen' : ''">
      <Nuxt :key="currentLang" />
    </div>
    <app-audio-player-container ref="streamContainer" />
    <modals-libraries-modal />
    <modals-playlists-add-create-modal />
    <modals-select-local-folder-modal />
    <modals-rssfeeds-rss-feed-modal />
    <app-side-drawer :key="currentLang" />
    <readers-reader />
  </div>
</template>

<script>
import { CapacitorHttp } from '@capacitor/core'
import { AbsLogger } from '@/plugins/capacitor'

export default {
  data() {
    return {
      inittingLibraries: false,
      initLibrariesRun: null,
      hasMounted: false,
      initialSessionsSynced: false,
      disconnectTime: 0,
      socketDisconnectedTime: 0,
      timeLostFocus: 0,
      currentLang: null
    }
  },
  watch: {
    networkConnected: {
      handler(newVal) {
        // Reconnecting follows the server (serverState), not the network: a live network says nothing about the server
        if (!newVal) {
          console.log(`[default] lost network connection`)
          this.disconnectTime = Date.now()
          return
        }
        // Native can report the server online before Android reports the network, and attemptConnection needs both
        if (this.hasMounted && this.serverState === 'online' && !this.user) this.connectWhenReachable()
      }
    },
    serverState: {
      handler(newVal, oldVal) {
        if (!this.hasMounted) {
          // watcher runs before mount, handling libraries/connection should be handled in mount
          return
        }
        if (newVal === 'online' && oldVal !== 'online') {
          console.log(`[default] server reachable again (${oldVal} -> ${newVal})`)
          this.serverCameOnline()
        }
      }
    },
    socketConnected: {
      handler(newVal, oldVal) {
        if (!this.hasMounted) {
          // watcher runs before mount, handling libraries/connection should be handled in mount
          return
        }
        if (newVal) {
          // if we havent been receiving socket events then external progress updates may have been missed
          const timeSinceDisconnect = Date.now() - this.socketDisconnectedTime
          if (timeSinceDisconnect > 30000 && this.isPlayerOpen) {
            console.log('[default] socket reconnected after ' + timeSinceDisconnect + 'ms and player is open, triggering server media progress sync')
            // used for triggering a server media progress sync if local media item is open in player
            this.$eventBus.$emit('socket-reconnected')
          } else {
            console.log('[default] socket reconnected after ' + timeSinceDisconnect + 'ms')
          }
        } else {
          console.log('[default] socket disconnected')
          this.socketDisconnectedTime = Date.now()
        }
      }
    }
  },
  computed: {
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    routeName() {
      return this.$route.name
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    serverState() {
      return this.$store.state.serverState
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    user() {
      return this.$store.state.user.user
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    currentLibraryName() {
      return this.$store.getters['libraries/getCurrentLibraryName']
    },
    attemptingConnection: {
      get() {
        return this.$store.state.attemptingConnection
      },
      set(val) {
        this.$store.commit('setAttemptingConnection', val)
      }
    }
  },
  methods: {
    initialStream(stream) {
      if (this.$refs.streamContainer?.audioPlayerReady) {
        this.$refs.streamContainer.streamOpen(stream)
      }
    },
    async loadSavedSettings() {
      const userSavedServerSettings = await this.$localStore.getServerSettings()
      if (userSavedServerSettings) {
        this.$store.commit('setServerSettings', userSavedServerSettings)
      }

      await this.$store.dispatch('user/loadUserSettings')
    },
    async attemptConnection() {
      console.warn('[default] attemptConnection')
      if (!this.networkConnected) {
        console.warn('[default] No network connection')
        AbsLogger.info({ tag: 'default', message: 'attemptConnection: No network connection' })
        return
      }
      if (this.attemptingConnection) {
        return
      }
      this.attemptingConnection = true
      // Tabs that need the signed-in user (series, collections, playlists, authors) are empty until it is there
      const wasSignedIn = !!this.user

      const deviceData = await this.$db.getDeviceData()
      let serverConfig = null
      if (deviceData) {
        this.$store.commit('globals/setHapticFeedback', deviceData.deviceSettings?.hapticFeedback)

        if (deviceData.lastServerConnectionConfigId && deviceData.serverConnectionConfigs.length) {
          serverConfig = deviceData.serverConnectionConfigs.find((scc) => scc.id == deviceData.lastServerConnectionConfigId)
        }
      }

      if (!serverConfig) {
        // No last server config set
        this.attemptingConnection = false
        AbsLogger.info({ tag: 'default', message: 'attemptConnection: No last server config set' })
        return
      }

      AbsLogger.info({ tag: 'default', message: `attemptConnection: Got server config, attempt authorize (${serverConfig.name})` })

      const nativeHttpOptions = {
        headers: {
          Authorization: `Bearer ${serverConfig.token}`
        },
        connectTimeout: 6000,
        serverConnectionConfig: serverConfig
      }
      const authRes = await this.$nativeHttp.post(`${serverConfig.address}/api/authorize`, null, nativeHttpOptions).catch((error) => {
        AbsLogger.error({ tag: 'default', message: `attemptConnection: Server auth failed (${serverConfig.name})` })
        return false
      })

      if (!authRes) {
        this.attemptingConnection = false
        return
      }

      const { user, userDefaultLibraryId, serverSettings, ereaderDevices } = authRes
      this.$store.commit('setServerSettings', serverSettings)
      this.$store.commit('libraries/setEReaderDevices', ereaderDevices)

      if (this.$isValidVersion(serverSettings.version, '2.26.0')) {
        // Check if the server is using the new JWT auth and is still using an old token in the server config
        // If so, redirect to /connect and request to re-login
        if (serverConfig.token === user.token || user.isOldToken) {
          this.attemptingConnection = false
          AbsLogger.info({ tag: 'default', message: `attemptConnection: Server is using new JWT auth but config is still using an old token (server version: ${serverSettings.version}) (${serverConfig.name})` })
          // Clear last server config
          await this.$store.dispatch('user/logout')
          this.$router.push(`/connect?error=oldAuthToken&serverConnectionConfigId=${serverConfig.id}`)
          return
        }

        // Token may have been renewed during the authorize call (utils/authRenewal.js); save the renewed one
        serverConfig.token = this.$authRenewal.latestToken(serverConfig.id) || serverConfig.token
      }

      // Set library - Use last library if set and available fallback to default user library
      const libraryBefore = this.currentLibraryId
      const lastLibraryId = await this.$localStore.getLastLibraryId()
      if (lastLibraryId && (!user.librariesAccessible.length || user.librariesAccessible.includes(lastLibraryId))) {
        this.$store.commit('libraries/setCurrentLibrary', lastLibraryId)
      } else if (userDefaultLibraryId) {
        this.$store.commit('libraries/setCurrentLibrary', userDefaultLibraryId)
      }
      serverConfig.version = serverSettings.version
      const serverConnectionConfig = await this.$db.setServerConnectionConfig(serverConfig)

      this.$store.commit('user/setUser', user)
      this.$store.commit('user/setAccessToken', serverConnectionConfig.token)
      this.$store.commit('user/setServerConnectionConfig', serverConnectionConfig)
      this.forwardUserToNative(user, serverConnectionConfig.id)

      this.$socket.connect(serverConnectionConfig.address, serverConnectionConfig.token)

      AbsLogger.info({ tag: 'default', message: `attemptConnection: Successful connection to last saved server config (${serverConnectionConfig.name})` })
      // The replica may have been read before the sign-in (or be empty): read again, now able to fall back to the server
      await this.initLibraries({ force: true, notify: !wasSignedIn || this.currentLibraryId !== libraryBefore })
      this.attemptingConnection = false
    },
    serverCameOnline() {
      if (!this.user) {
        this.connectWhenReachable()
        return
      }
      if (!this.currentLibraryId) {
        this.initLibraries()
      } else {
        // Libraries come from the replica; the reconcile this return starts updates them (catalog notice)
        this.$eventBus.$emit('server-reachable')
      }
      setTimeout(() => {
        this.syncLocalSessions(false)
      }, 4000)
    },
    async connectWhenReachable() {
      if (this.attemptingConnection) return
      await this.attemptConnection()
      if (!this.user) return
      // An offline cold start skipped the startup sync (no user then), so this one is the first
      const isFirstSync = !this.initialSessionsSynced
      this.initialSessionsSynced = true
      await this.syncLocalSessions(isFirstSync)
    },
    itemRemoved(libraryItem) {
      if (this.$route.name.startsWith('item')) {
        if (this.$route.params.id === libraryItem.id) {
          this.$router.replace(`/bookshelf`)
        }
      }
    },
    socketConnectionFailed(err) {
      this.$toast.error('Socket connection error: ' + err.message)
    },
    /** force: read the libraries again (skip the 5-minute cache), also after a read that is still running. */
    async initLibraries({ notify = true, force = false } = {}) {
      if (this.inittingLibraries) {
        if (!force) return
        await this.initLibrariesRun
        return this.initLibraries({ notify, force })
      }
      this.inittingLibraries = true
      const run = (async () => {
        const libraryBefore = this.currentLibraryId
        await this.$store.dispatch('libraries/load', { force })

        AbsLogger.info({ tag: 'default', message: `initLibraries loading library ${this.currentLibraryName}` })
        // An empty replica before the first sign-in has no library to read yet
        if (this.currentLibraryId) await this.$store.dispatch('libraries/fetch', notify ? this.currentLibraryId : { libraryId: this.currentLibraryId, onlyIfCurrent: true })
        // load switches away from a library that was removed or became inaccessible; pages must follow it
        // With the library's id: a page of that library stays (a sign-in or reconnect is no library switch)
        if (notify || this.currentLibraryId !== libraryBefore) this.$eventBus.$emit('library-changed', this.currentLibraryId)
      })()
      this.initLibrariesRun = run
      try {
        await run
      } finally {
        this.inittingLibraries = false
      }
    },
    async syncLocalSessions(isFirstSync) {
      if (!this.user) {
        console.log('[default] No need to sync local sessions - not connected to server')
        return
      }

      AbsLogger.info({ tag: 'default', message: 'Calling syncLocalSessions' })
      const response = await this.$db.syncLocalSessionsWithServer(isFirstSync)
      if (response?.error) {
        await AbsLogger.error({ tag: 'default', message: `syncLocalSessions: Failed to sync local sessions: ${response.error}` })
      } else {
        await AbsLogger.info({ tag: 'default', message: 'syncLocalSessions: Successfully synced local sessions' })
        // Reload local media progresses
        await this.$store.dispatch('globals/loadLocalMediaProgress')
      }
    },
    userUpdated(user) {
      if (this.user?.id == user.id) {
        this.$store.commit('user/setUser', user)
        this.queueUserForNative(user)
      }
    },
    /** The user object carries the server's progress and bookmarks; native merges them into userdata.db. */
    forwardUserToNative(user, serverConfigId) {
      this.$absData.applyServerEvent({ event: 'user_updated', payload: user, serverConfigId }).catch((error) => console.error('[default] Could not hand the user to native', error))
    },
    /**
     * Socket user_updated arrives in bursts (one per change on the server) and each carries the whole user, so only the
     * last one within 500 ms crosses the bridge. The server is captured on arrival: native drops it if another one is
     * active by then, and a switch sends what was waiting for the previous server right away.
     */
    queueUserForNative(user) {
      const serverConfigId = this.$store.state.activeServerConfigId
      if (this.pendingNativeUser && this.pendingNativeUser.serverConfigId !== serverConfigId) this.flushUserForNative()
      this.pendingNativeUser = { user, serverConfigId }
      clearTimeout(this.nativeUserTimer)
      this.nativeUserTimer = setTimeout(this.flushUserForNative, 500)
    },
    flushUserForNative() {
      clearTimeout(this.nativeUserTimer)
      const pending = this.pendingNativeUser
      this.pendingNativeUser = null
      if (pending) this.forwardUserToNative(pending.user, pending.serverConfigId)
    },
    /**
     * A socket progress event, already merged natively by $socket (`applied`: the server value won over the device's).
     * A paused player of that item follows it only then: a value that lost to an unsent local change must not move it.
     */
    async userMediaProgressUpdated(payload) {
      const prog = payload.data // MediaProgress
      if (!prog || payload.applied !== true) return
      const itemLabel = `${prog.libraryItemId}${prog.episodeId ? ` episodeId: ${prog.episodeId}` : ''}`
      const isMediaOpenInPlayer = this.$store.getters['getIsMediaStreaming'](prog.libraryItemId, prog.episodeId)
      if (isMediaOpenInPlayer && this.$store.getters['getCurrentPlaybackSessionId'] !== payload.sessionId && !this.$store.state.playerIsPlaying) {
        await AbsLogger.info({ tag: 'default', message: `userMediaProgressUpdate: Updating paused player playback time to ${prog.currentTime} (${itemLabel}, mediaProgressId: ${payload.id})` })
        this.$eventBus.$emit('playback-time-update', prog.currentTime)
      }
    },
    async visibilityChanged() {
      if (document.visibilityState === 'visible') {
        const elapsedTimeOutOfFocus = Date.now() - this.timeLostFocus
        console.log(`✅ [default] device visibility: has focus (${elapsedTimeOutOfFocus}ms out of focus)`)
        // If device out of focus for more than 30s then reload local media progress
        if (elapsedTimeOutOfFocus > 30000) {
          console.log(`✅ [default] device visibility: reloading local media progress`)
          // Reload local media progresses
          await this.$store.dispatch('globals/loadLocalMediaProgress')
        }
        if (document.visibilityState === 'visible') {
          this.$eventBus.$emit('device-focus-update', true)
        }
      } else {
        console.log('⛔️ [default] device visibility: does NOT have focus')
        this.timeLostFocus = Date.now()
        this.$eventBus.$emit('device-focus-update', false)
      }
    },
    changeLanguage(code) {
      console.log('Changed lang', code)
      this.currentLang = code
      document.documentElement.lang = code
    }
  },
  async mounted() {
    this.$eventBus.$on('change-lang', this.changeLanguage)
    document.addEventListener('visibilitychange', this.visibilityChanged)

    this.$socket.on('user_updated', this.userUpdated)
    this.$socket.on('user_media_progress_updated', this.userMediaProgressUpdated)

    if (this.$store.state.isFirstLoad) {
      AbsLogger.info({ tag: 'default', message: `mounted: initializing first load (${this.$platform} v${this.$config.version})` })
      this.$store.commit('setIsFirstLoad', false)

      this.loadSavedSettings()

      const deviceData = await this.$db.getDeviceData()
      this.$store.commit('setDeviceData', deviceData)

      this.$setOrientationLock(this.$store.getters['getOrientationLockSetting'])

      await this.$store.dispatch('setupNetworkListener')

      // Native may already know the server is down; then the serverState watcher connects once it answers again
      await this.$absData.getServerState().catch((error) => console.error('[default] Could not read the server state', error))
      // Progress, bookmarks and the last known user (rights) are kept on the device, so they show before (and without)
      // a connection; the live user still comes from authorize
      await this.$absData.reloadUserData().catch((error) => console.error('[default] Could not read the user data', error))

      // The catalog of the saved server is on the device: the tabs show it before (and without) a connection
      const hasActiveServer = !!this.$store.getters.getHasActiveServer
      if (hasActiveServer) {
        AbsLogger.info({ tag: 'default', message: 'mounted: init libraries from the replica' })
        await this.initLibraries()
      }

      if (this.$store.state.user.serverConnectionConfig) {
        AbsLogger.info({ tag: 'default', message: `mounted: Server connected, init libraries (${this.$store.getters['user/getServerConfigName']})` })
        if (!hasActiveServer) await this.initLibraries()
      } else if (this.serverState === 'offline') {
        AbsLogger.info({ tag: 'default', message: 'mounted: Server unreachable, waiting for it before connecting' })
      } else {
        AbsLogger.info({ tag: 'default', message: `mounted: Server not connected, attempt connection` })
        await this.attemptConnection()
      }

      await this.syncLocalSessions(true)
      this.initialSessionsSynced = !!this.user

      AbsLogger.info({ tag: 'default', message: 'mounted: fully initialized' })
      this.$eventBus.$emit('abs-ui-ready')
    }
    // Also on a remount (back from /connect), or the watchers above never react again
    this.hasMounted = true
  },
  beforeDestroy() {
    this.flushUserForNative()
    this.$eventBus.$off('change-lang', this.changeLanguage)
    document.removeEventListener('visibilitychange', this.visibilityChanged)
    this.$socket.off('user_updated', this.userUpdated)
    this.$socket.off('user_media_progress_updated', this.userMediaProgressUpdated)
  }
}
</script>
