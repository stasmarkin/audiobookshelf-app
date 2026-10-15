import { Capacitor } from '@capacitor/core'
import { AbsData } from '@/plugins/capacitor/AbsData'
import { createDataService } from '@/utils/dataService'

// Injected as $absData: Vue 2 already owns this.$data (component data), and Nuxt's inject would not override it
export default ({ store, $axios }, inject) => {
  inject('absData', createDataService({ plugin: AbsData, store, platform: Capacitor.getPlatform(), $axios }))
}
