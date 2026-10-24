import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsDownloaderWeb extends WebPlugin {
  constructor() {
    super()
  }

  async getDownloadQueue() {
    return { items: [] }
  }

  async retryDownload() {
    return { errorCode: 'notFound' }
  }

  async removeDownload() {
    return { errorCode: 'notFound' }
  }

  async checkAutoDownloads(_options) {
    return { status: 'disabled' }
  }

  async getDownloadsStorage() {
    return { usedBytes: 0, skippedForLimit: 0 }
  }

  async getPodcastAutoDownload() {
    return { enabled: 'DEFAULT', keepCount: null }
  }

  async setPodcastAutoDownload() {
    return {}
  }

  async getAutoDownloadedEpisodes() {
    return { episodeIds: [] }
  }
}

const AbsDownloader = registerPlugin('AbsDownloader', {
  web: () => new AbsDownloaderWeb()
})

export { AbsDownloader }
