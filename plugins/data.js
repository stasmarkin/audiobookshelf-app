import { Capacitor } from '@capacitor/core'
import { AbsData } from '@/plugins/capacitor/AbsData'
import { createDataService } from '@/utils/dataService'

// Injected as $absData: Vue 2 already owns this.$data (component data), and Nuxt's inject would not override it
// $nativeHttp is injected by a later plugin, so it is looked up when a catalog read needs the server
export default ({ app, store, $axios }, inject) => {
  inject(
    'absData',
    createDataService({ plugin: AbsData, store, platform: Capacitor.getPlatform(), $axios, eventBus: app.$eventBus, nativeHttp: () => app.$nativeHttp })
  )
}
