<template>
  <div class="w-full h-16 bg-primary relative z-20">
    <div id="appbar" class="absolute top-0 left-0 w-full h-full flex items-center px-2">
      <nuxt-link v-show="!showBack" to="/" class="mr-3">
        <img src="/Logo.png" class="h-10 w-10" />
      </nuxt-link>
      <a v-if="showBack" @click="back" aria-label="Back" class="rounded-full h-10 w-10 flex items-center justify-center mr-2 cursor-pointer">
        <span class="material-symbols text-3xl text-fg">arrow_back</span>
      </a>
      <div v-if="(user || hasActiveServer) && currentLibrary">
        <button type="button" aria-label="Show library modal" class="library-selector pl-1.5 pr-2.5 py-2 bg-bg bg-opacity-30 rounded-md flex items-center" @click="clickLibraryButton" @touchstart.passive="libraryButtonTouchStart" @touchmove.passive="libraryButtonTouchMove" @touchend="cancelLibraryLongPress" @touchcancel="cancelLibraryLongPress" @contextmenu.prevent>
          <ui-library-icon :icon="currentLibraryIcon" :size="4" font-size="base" />
          <p class="text-sm leading-4 ml-2 mt-0.5 max-w-24 truncate">{{ currentLibraryName }}</p>
        </button>
      </div>

      <widgets-connection-indicator />

      <div class="flex-grow" />

      <widgets-download-progress-indicator />

      <!-- Must be connected to a server to cast, only supports media items on server -->
      <button type="button" aria-label="Cast" v-show="isCastAvailable && user" class="mx-2 cursor-pointer flex items-center" @click="castClick">
        <span class="material-symbols text-2xl leading-none">
          {{ isCasting ? 'cast_connected' : 'cast' }}
        </span>
      </button>

      <nuxt-link v-if="user || hasActiveServer" class="mx-1.5 flex items-center h-10" to="/search" aria-label="Search">
        <span class="material-symbols text-2xl leading-none">search</span>
      </nuxt-link>

      <button type="button" aria-label="Toggle side drawer" class="h-7 mx-1.5" @click="clickShowSideDrawer">
        <span class="material-symbols" style="font-size: 1.75rem">menu</span>
      </button>
    </div>
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { otherLibraryId } from '@/utils/libraryViewState'

const LONG_PRESS_MS = 500
const LONG_PRESS_MOVE_TOLERANCE_PX = 10

export default {
  data() {
    return {
      onCastAvailableUpdateListener: null,
      longPressTimer: null,
      longPressStart: null,
      longPressed: false
    }
  },
  computed: {
    isCastAvailable: {
      get() {
        return this.$store.state.isCastAvailable
      },
      set(val) {
        this.$store.commit('setCastAvailable', val)
      }
    },
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryName() {
      return this.currentLibrary?.name || ''
    },
    currentLibraryIcon() {
      return this.currentLibrary?.icon || 'database'
    },
    // With exactly two libraries a tap switches between them and a long press opens the menu
    quickSwitchTargetId() {
      return otherLibraryId(this.$store.state.libraries.libraries, this.$store.state.libraries.currentLibraryId)
    },
    showBack() {
      if (!this.$route.name) return true
      return this.$route.name !== 'index' && !this.$route.name.startsWith('bookshelf')
    },
    user() {
      return this.$store.state.user.user
    },
    hasActiveServer() {
      return this.$store.getters.getHasActiveServer
    },
    username() {
      return this.user?.username || 'err'
    },
    isCasting() {
      return this.$store.state.isCasting
    }
  },
  methods: {
    castClick() {
      if (this.$store.getters['getIsCurrentSessionLocal']) {
        this.$eventBus.$emit('cast-local-item')
        return
      }
      AbsAudioPlayer.requestSession()
    },
    clickShowSideDrawer() {
      this.$store.commit('setShowSideDrawer', true)
    },
    clickShowLibraryModal() {
      this.$store.commit('libraries/setShowModal', true)
    },
    async clickLibraryButton() {
      if (this.longPressed) {
        this.longPressed = false
        return
      }
      if (!this.quickSwitchTargetId) {
        this.clickShowLibraryModal()
        return
      }
      await this.$hapticsImpact()
      this.$store.dispatch('libraries/switchLibrary', this.quickSwitchTargetId)
    },
    libraryButtonTouchStart(event) {
      // The WebView may not send a click after a long press, so a stale flag must not swallow the next tap
      this.longPressed = false
      this.cancelLibraryLongPress()
      if (!this.quickSwitchTargetId) return
      const touch = event.touches[0]
      this.longPressStart = { x: touch.clientX, y: touch.clientY }
      this.longPressTimer = setTimeout(async () => {
        this.longPressTimer = null
        this.longPressed = true
        await this.$hapticsImpact()
        this.clickShowLibraryModal()
      }, LONG_PRESS_MS)
    },
    libraryButtonTouchMove(event) {
      if (!this.longPressTimer) return
      const touch = event.touches[0]
      if (Math.hypot(touch.clientX - this.longPressStart.x, touch.clientY - this.longPressStart.y) > LONG_PRESS_MOVE_TOLERANCE_PX) this.cancelLibraryLongPress()
    },
    cancelLibraryLongPress() {
      clearTimeout(this.longPressTimer)
      this.longPressTimer = null
    },
    back() {
      window.history.back()
    },
    onCastAvailableUpdate(data) {
      this.isCastAvailable = data && data.value
    }
  },
  async mounted() {
    AbsAudioPlayer.getIsCastAvailable().then((data) => {
      this.isCastAvailable = data && data.value
    })
    this.onCastAvailableUpdateListener = await AbsAudioPlayer.addListener('onCastAvailableUpdate', this.onCastAvailableUpdate)
  },
  beforeDestroy() {
    this.cancelLibraryLongPress()
    this.onCastAvailableUpdateListener?.remove()
  }
}
</script>

<style>
#appbar {
  box-shadow: 0px 5px 5px #11111155;
}
.loader-dots div {
  animation-timing-function: cubic-bezier(0, 1, 1, 0);
}
.loader-dots div:nth-child(1) {
  left: 0px;
  animation: loader-dots1 0.6s infinite;
}
.loader-dots div:nth-child(2) {
  left: 0px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(3) {
  left: 10px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(4) {
  left: 20px;
  animation: loader-dots3 0.6s infinite;
}
@keyframes loader-dots1 {
  0% {
    transform: scale(0);
  }
  100% {
    transform: scale(1);
  }
}
@keyframes loader-dots3 {
  0% {
    transform: scale(1);
  }
  100% {
    transform: scale(0);
  }
}
@keyframes loader-dots2 {
  0% {
    transform: translate(0, 0);
  }
  100% {
    transform: translate(10px, 0);
  }
}
</style>
