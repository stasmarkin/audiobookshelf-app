import { AbsData } from '@/plugins/capacitor/AbsData'
import { createDataService } from '@/utils/dataService'

export default ({ store }, inject) => {
  inject('data', createDataService({ plugin: AbsData, store }))
}
