# Auto delete and the downloads limit

Downloads of finished books (including ones in a device folder that Rescan Folder linked to the server) can be removed from the device automatically, and automatic downloads can be held to a best-effort space budget (not a hard limit on storage; see below). Settings live in the "Auto delete and storage" block of the Settings page (Android). Native code: `AutoDeletePolicy` (pure decisions), `AutoDeleteManager` (the cleanup pass), `LocalItemDeleter` (removes an item or a track from internal storage, the SD card or a device folder; shared with the manual delete), `FolderFileDeletion` (the device-folder part of it: registered regular files only, never folders), `AutoDownloadManager` (checks the limit before queueing an auto download), `AutoDeleteWorker` / `AutoDeleteScheduler` (periodic background cleanup, separate from the podcast auto download work).

Podcast episodes are not touched by this feature. They are managed by each podcast's own auto download settings ("Automatically manage downloads" or "Keep all downloads", see the podcast auto download help in the app): that retention decides which automatic episode downloads stay, and nothing here deletes an episode, neither after a delay nor to make room.

## Settings

| Setting | Values | Default |
|---|---|---|
| Delete finished books | on / off | off |
| Delete after | right away, 1, 3 or 7 days after finishing (shown while the toggle is on) | 1 day |
| Downloads limit | off, 1, 2, 5, 10 or 20 GB | off |

The block sits right below Data settings, next to Download storage. Under the settings the app shows how much space downloads take on the internal storage and the SD card together, and how much is free on the storage new downloads go to (the Download storage setting; the free space is left out while that SD card is not available), plus how many auto downloads were skipped because they didn't fit the limit, if any.

## What gets deleted

Only books downloaded from a server are considered, and a book is deleted as a whole local item. Items not linked to a server item (added from a device folder and never matched) are never deleted; items a folder rescan linked to the server count as downloads. Podcasts are never deleted by this feature. In a device folder only the files the app registered for the item are removed, each only while it is a regular file; folders are never deleted (an emptied book or author folder stays), nothing is matched by name, and the user's own files there stay (the manual delete does the same). A device folder the app can no longer write to is treated like a missing SD card. If the saved link to the book's folder or to one of its files cannot be resolved, or the app has no access to the folder, the download is skipped without deleting anything and tried again at the next check; a file that is no longer there counts as already deleted only while the device folder can be reached; an unreachable folder deletes nothing and keeps the records. A book with audio and an ebook is deleted whole, ebook included; a book with only an ebook is never deleted. Both manual and auto downloads are deleted. A registered entry that turns out not to be a regular file (the path was replaced by a folder) is never deleted: the manual delete fails and keeps the record, the automatic one skips the book.

A book is deleted when the toggle is on, it is finished, and the delay has passed since it was finished. Two triggers run the cleanup: its own periodic background work (every 6 hours while Delete finished books is on, with no network or charging requirement; the podcast auto download work keeps its own limits and does not clean up) and the app coming to the foreground. The limit alone needs no run, because it deletes nothing without Delete finished books.

## When a book counts as finished

- The server's progress (`isFinished`, `finishedAt`) and the device's own progress are both used; the device's progress counts even if it was recorded offline.
- When they disagree, the record updated more recently wins. Example: the server says finished, but the device has a newer record that isn't finished (the user started listening again) - not finished, not deleted.
- Without a connection to the server, and for downloads from another server than the current one, only the device's progress is used.
- The delay counts from `finishedAt` of the winning record (or its last update if the finish time is missing).
- A download remembers when it finished downloading. Progress finished before that moment came from the server together with the files (the book was finished, deleted and downloaded again), so it does not count: the download waits until it is finished again. Downloads from before this was recorded have no such limit.

## Listening progress is never lost

Deleting a download also drops its device progress the next time the app starts (`DbManager.cleanLocalMediaProgress`). So a book is deleted only once its progress is safe on the server:

- If a playback session recorded offline for it hasn't been sent to the server yet, it is not deleted.
- If the device's progress is newer than the server's and the server doesn't have it as finished yet, it is not deleted now; the regular sync on app open sends the progress, and a later cleanup deletes the download.
- Without a connection (or for another server), a book is deleted only if an earlier online cleanup saw its current device progress on the server as finished, for the same server, account and server item. If that cleanup found the server's record newer and not finished, the permission is withdrawn. A book marked finished offline is therefore deleted after the next cleanup with a connection.

The sync itself is not changed by this feature.

## Storages

- Downloads on the SD card (the app's own directory there, see [downloads.md](downloads.md)) are deleted like internal ones.
- While the SD card is removed, unmounted or another card than the registered one, the books on it are not deleted and are not counted in the used space, because they take no room on a usable volume. Their records and progress stay. This is not an error; the log says how many were left alone. They count again, and can be deleted, once the card is back.
- A book that the storage move has in its journal (waiting for or being moved between Internal and the SD card) is not deleted; it can be deleted after the move. Books already on the target storage are deleted as usual.
- Before an auto download frees room, the app checks that the download could start: a failed download must be retryable (storage and folder access present, nothing running), a new one needs a usable destination. A retry counts only the files not downloaded yet.
- A delete runs under the item's lock, the same one the mover and the manual delete use, and the protections, the progress, the account and the reason (delay passed, or still over the limit) are checked again inside it, the listening progress once more right before the first file goes.

## Never deleted

- What is loaded in the player now, and the items of the current playback queue (including the saved queue a widget or headset Play would resume).
- Anything with a download in progress or queued.
- Anything not finished - not even to make room.

## Downloads limit

**A best-effort budget, not a storage cap.** The setting decides whether an automatic download may start; it does not enforce physical space. It counts the registered files of server downloads (`LocalFile.size`, per reference, so a file shared by two episodes counts twice), not the bytes on disk. It does not count staging and partial files, unregistered leftovers, copies kept during an SD card move, or items on a storage that is not available. Manual downloads are never refused and can take the total over it, and an episode whose size the server does not report is admitted and counted as 0 until it is downloaded. Free space on internal storage does not make a full SD card writable, and the reverse.

The limit covers the total size of the files of all downloads from a server (`LocalFile.size`), books and podcast episodes, manual and automatic.

- Before an auto download is queued, it must be downloadable at all (the episode has its audio metadata, the destination can take it); only then is room made for it. Its expected size (`audioFile.metadata.size`) is added to what downloads already take, including auto downloads queued in the same run. If that exceeds the limit and Delete finished books is on, finished books are deleted, longest finished first, without waiting for the delay (the protections above still apply). If finished books cannot free enough room, nothing is deleted and the episode is not queued; the reason goes to the diagnostic log and the skipped count to Settings.
- Manual downloads are never blocked by the limit.
- If manual downloads push the total over the limit and Delete finished books is on, the periodic cleanup also deletes finished books without waiting for the delay, longest finished first, until the total fits.
- With Delete finished books off, the limit only stops auto downloads that don't fit; nothing is deleted to make room. Episodes are never deleted to make room.

## After a background delete

The app reloads its list of downloads when a cleanup deleted something, so the item pages and the downloads list don't show files that are gone.

## Diagnostics

Each deleted book is logged with its title, the reason (finished and due, or to make room) and the size freed; skipped deletions log why (in the player, downloading, progress not yet on the server). Auto downloads skipped for the limit are logged with their size and the remaining room. No tokens or credentials are logged.
