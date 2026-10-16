import { AbsDownloader } from '@/plugins/capacitor'
import { appStorageDownloadFolder, isAppStorageFolderId, safFoldersFor } from '@/utils/appStorage'

// Never shows the folder picker, so quick actions (Download & Play) stay fast: a lone SAF folder
// without app storage alongside is the only unambiguous choice, anything else lets native decide.
export async function resolveDownloadFolder(context, mediaType) {
  const folders = (await context.$db.getLocalFolders()) || []
  const saf = safFoldersFor(folders, mediaType)
  const hasAppStorage = folders.some((f) => f && f.mediaType == mediaType && isAppStorageFolderId(f.id))
  if (saf.length === 1 && !hasAppStorage) return saf[0]
  return appStorageDownloadFolder(mediaType, '')
}

// Downloads whichever of the given (already sequence-ordered) queue items don't have a local
// copy, in array order, without waiting for one to finish before starting the next - the native
// download queue already prioritizes by insertion order, so this alone is enough to make sure
// the next-needed item gets download priority.
export async function downloadMissingItems(context, items) {
  const missingItems = items.filter((item) => !item.localLibraryItem)
  if (!missingItems.length) return

  const folderByMediaType = {}
  for (const item of missingItems) {
    const mediaType = item.episodeId ? 'podcast' : 'book'
    if (!folderByMediaType[mediaType]) {
      folderByMediaType[mediaType] = await resolveDownloadFolder(context, mediaType)
    }
    AbsDownloader.downloadLibraryItem({
      libraryItemId: item.libraryItemId,
      episodeId: item.episodeId || undefined,
      localFolderId: folderByMediaType[mediaType].id
    })
      .then((result) => {
        // downloadLibraryItem resolves (does not reject) with an `error` field for expected
        // failures (e.g. lost folder permission) - the promise-level .catch below never sees these.
        if (result?.error) {
          console.error('[bulkDownload] Failed to start download for', item.libraryItemId, result.error)
          context.$toast.error(result.error)
        }
      })
      .catch((error) => {
        console.error('[bulkDownload] Failed to start download for', item.libraryItemId, error)
        context.$toast.error(error.message || 'Failed to start download')
      })
  }
}
