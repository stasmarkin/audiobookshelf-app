# Downloads

How a book or episode download moves through the app, and what the user's controls do. Native code: `AbsDownloader` (Capacitor plugin), `DownloadServiceHost`, `DownloadService` (foreground service), `DownloadItemManager` (queue), `DownloadControls` (Retry/Cancel/Clear rules), `InternalDownloadManager` (HTTP transfer), `IncompleteDownloadCleanup`. Frontend: `utils/downloadState.js`, `pages/downloading.vue` (the Downloads screen), `components/widgets/DownloadProgressIndicator.vue` (app-bar badge), the item page.

## Lifecycle

1. The item page (or series/episode rows) calls `AbsDownloader.downloadLibraryItem`. If the item is already in the queue the call is a Retry of it; otherwise a `DownloadItem` is built with one part per audio file, ebook and cover (`cover-<server item id>.jpg`) and queued.
2. The queue is persisted (Paper `downloadItems`) and survives navigation, the UI closing and process death. `DownloadService` runs the transfers (up to 3 files at once) into app staging files, then each finished file is moved to its destination: app storage (internal or the SD card, see Download storage), or the chosen device folder (SAF).
   - A queued podcast episode keeps only the podcast's metadata, not its episode list (`DownloadItem.withoutPodcastEpisodes`; native code reads the podcast only through `getLocalCopy()`, JS never reads `media`). Entries saved by older versions are rewritten compact when the queue is restored, and startup validation reads them one at a time (PR #25, contributed by Stanislav Markin).
   - Every read or change of `DownloadItemManager.downloadItemQueue` holds the manager's lock: mutations are `@Synchronized`, `findDownloadItem` serves Retry and new requests, and the `onDownloadItem` replay and the Downloads screen copy the list under the lock, then emit outside it.
3. When every file is in place the item is scanned into a local library item, removed from the queue and reported to the UI (`onItemDownloadComplete`).

## States (as the Downloads screen shows them)

| State | When | Controls |
|---|---|---|
| Queued | waiting for a transfer slot | Cancel |
| Waiting for storage space | not enough free space yet | Cancel |
| Downloading (n%) | a file is transferring, or a finished file is being saved to its folder | Cancel |
| Download failed | a file failed for good (automatic retries used up, server refusal, failed save) | Retry, Clear |
| Folder access lost | the device folder can't be written (access revoked, or the folder was deleted or moved) | Choose folder, Clear |
| SD card not available | the download's SD card is not mounted (removed, unmounted, or another card is in) | Retry (once the card is back), Clear |
| Finishing | every file is in place; becoming a local item | none |

The state comes from the parts' flags (`downloadId`, `completed`, `moved`, `isMoving`, `failed`, `permissionLost`, `storageUnavailable`, `waitingForSpace`); it is always shown in words next to an icon. The item page shows the failed states and links to the Downloads screen, and its Download button opens the Downloads screen while a download exists.

## Retry is not Resume

- **Automatic retries** (up to 5 per file, inside one service session) continue a partial file with an HTTP `Range` request where the server allows it.
- **Retry** (the user's button, or the Download button on a failed item) and **app/process restart** start every unfinished file again from byte 0. Files already finished in their destination are kept and are not downloaded again. The button is therefore called Retry, and the Downloads screen says so.
- A failed download stays failed across restarts with its reason (including lost folder access); it is never retried silently. A download whose SD card is missing is not retried automatically and keeps the episode counted as auto-downloaded; Retry is refused until the card is back.

## Cancel and Clear

Both remove the download: they stop its transfers, drop it from the queue and its saved record, and delete the app's own partial (staging) files. Cancel is offered while a download is active or queued; Clear when it has stopped (failed or lost folder access). Neither is possible while a finished file is being saved to its folder (a moment; "try again") or once the download is finishing.

Finished files a removed download already placed in a **device folder** are kept there (a later download of the same item reuses them; Rescan Folder can link them). Finished files in **app storage** (internal or SD card) are deleted, since nothing else can reach them. A file a saved local item uses, or one that existed before the download (`reusedExistingFile`), is never deleted. A completed download is not in the queue, so Clear can't reach it; deleting a downloaded book stays with the local item's existing delete. Finished books can also be deleted automatically, and the space downloads take can be capped; see [auto-delete.md](auto-delete.md).

The download notification's Cancel cancels every download that can be removed, with the same rules.

## Folder access

Before a file is started and before it is saved to a device folder, the downloader checks that it can still write there (`FolderAccess`, the same rule as the Local Folders permission check): SimpleStorage access for the folder's file path (or the persisted tree grant), and that the folder exists and is writable. Without it the files fail at once as "Folder access lost" (no retry loop), and Retry is refused until access is back. **Choose folder** opens the folder picker; picking the same folder restores the grant (the same folder keeps its id) and retries the download. Picking a different folder doesn't move the download; the user is told to choose the original folder, or can Clear and download again.

Before this was fixed, the access check was given the folder's `content://` URL, which SimpleStorage reads as a path, so it always answered "no access": every download into a device folder failed at once as "Lost access" (introduced in `ba008570`).

## Download storage

Settings → Data settings → **Download storage** chooses where the app keeps its own downloads: **Internal** (the app's private storage) or **SD card** (the app's own directory on the first removable volume, `Android/data/<package>/files`; no folder picker or extra permission). It covers books and podcasts, manual downloads and podcast auto download. Device folders picked in Local Folders are not affected: their downloads stay where they are and are never moved. Native code: `AppStorage`, `DownloadFolderResolver`, `AbsDatabase.setDownloadStorage`, `StorageMoveService`, `StorageMovePlanner`, `StorageMover`.

- **Where a new download goes.** An item that is already downloaded, or already has a download in the queue, gets its new files in its current folder, so one item's files are always on one volume. A new item goes to the storage from the setting. An item still waiting to be moved therefore keeps getting new episodes in its old place, and moves with them later.
- **Changing the setting moves everything.** Before switching, the app checks that the target has room for every app-storage download not already there plus 200 MB; otherwise it shows "Not enough space: need X, free Y" and keeps the setting. "SD card" can't be chosen while no writable SD card is mounted ("SD card not available"). After a switch a background pass with its own notification ("Moving downloads to SD card" / "Moving downloads to internal storage", n / m) moves the items one at a time: copy to a temporary folder on the target, check the sizes, point the item's record at the new files, delete the originals. Item, episode and file ids stay the same, so progress, listening history and the play queue are untouched, and open item pages switch to the new paths. If the app is killed mid-move, the next pass finishes or cleans up; the originals are kept until the record points at the copy.
- **Status under the setting.** The SD card and its free space ("SD card 79EC-9B33 · 103 GB free"), the running move ("Moving 3 of 7 items · 1.2 GB / 2.9 GB"), and items that can't be moved yet, by reason:

| Status | Why the item waits | Moved when |
|---|---|---|
| n items waiting: currently playing | the player has the item open (paused counts) | the next pass after the player moves on or closes |
| n items waiting: downloads in progress | the item has a download in the queue in any state, including failed or "SD card not available" | the download finishes, or is retried and finishes, or is cleared |
| n items on unavailable SD card | the item's card, or the target card, is not mounted | the card is back (the next pass after the app is opened) |
| n items waiting: not enough space | the item doesn't fit on the target with the 200 MB margin; it and the items after it wait | space is freed, at the next pass |
| n items waiting: will try again later | the item changed twice while it was being copied (an episode downloaded or deleted, playback started), or its copy failed in this pass | the next pass |
| Couldn't move downloads: … | a copy failed; that item waits for the next pass while the others still move, and its originals are intact | the next pass tries once more |

A pass starts when the setting changes and when the app comes to the foreground with something to move; a finished download or a closed player re-checks at once if the app is open (otherwise the next time it is opened) and starts a pass only if there is something to move. Each pass also removes leftover app-storage directories that no item and no queued download refers to (on mounted volumes only).

- **SD card not available.** When the card is removed or unmounted, or another card is inserted, items on it keep their records and progress (the startup cleanup skips them) but can't be played: playing one says "SD card not available" (there is no fallback to streaming), and a queue that reaches one stops. Downloads into it fail with "SD card not available"; auto download skips such episodes and keeps counting them as downloaded; queued downloads show the "SD card not available" state (see States: Retry once the card is back, or Clear). A download whose files were all in place when the card went away waits for it and is finished the next time the app is opened with the card inserted, or sooner if another download runs. These items wait for their card even after switching to Internal. Choosing "SD card" while a different card is inserted makes that card the new target; items on the old card wait for it. Deleting an item whose card is missing removes only its record; its files stay on the card until the next move pass that runs while the card is inserted (after a change of the setting, or once there are items to move) removes them as leftovers.
- **Leftover folders.** An app-storage folder that is not the current setting and holds no items is hidden from Local Folders.
- **Builds without this feature.** Only builds with Download storage understand item records on the SD card; a build without it (Android does not install an older versionCode over a newer one, so this means a future build without the support) treats such items as missing. Before installing such a build, switch Download storage back to Internal and wait for the move to finish.
- **Uninstalling the app** deletes its files on the SD card too ("Files on the SD card are removed when the app is uninstalled."), like everything in internal storage. Downloads are replaceable: download them again after reinstalling; listening progress is kept on the server.

## Diagnostics

The log records each user action ("User chose Retry/Cancel/Clear"), and for each: the item id and title, the destination folder, the state it was in, how many unfinished files restart from byte 0 and finished files are kept (Retry), and how many app-owned files were deleted and finished files kept (Cancel/Clear), plus refused retries for lost folder access. Failures log their reason per file. No tokens or credentials are logged.
