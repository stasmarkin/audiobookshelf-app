# Offline-first (phase 1)

Design for making Audiobookshelf+ fully usable without a reachable server: everything on the device plays, the whole catalogue is browsable, and progress, "finished" and bookmarks are saved locally and reach the server later.

**Status:** delivered as a series of PRs (see [Releases](#releases)); each PR updates its row in [Status](#status) and this document when it lands.

## Status

| Release | PR | What works without the server | Status |
|---|---|---|---|
| 0 | 0-doc | This document | This release |
| 1 | 1-reachability | "Server unreachable" is told apart from "no network"; the app reconnects by itself when the server returns | Planned |
| 2 | 2a-bookmarks | Bookmarks are created, renamed and deleted offline and sent later | Planned |
| 2 | 2b-progress | Progress and "finished" are kept offline and sent later; a refused sign-in keeps the data | Planned |
| 2 | 2c-sessions | Listening time of a stream that lost the server is kept and sent once | Planned |
| 3 | 3-player | Player: downloaded items never stream, a stream waits for the server instead of failing | Planned |
| 4 | 4a-library | A copy of the catalog on the phone (no visible change yet) | Planned |
| 4 | 4a-ui | Library tab | Planned |
| 4 | 4b-series | Series tab and series pages | Planned |
| 4 | 4c-authors | Authors tab | Planned |
| 4 | 4d-collections | Collections tab and collection pages | Planned |
| 4 | 4e-playlists | Playlists; an offline queue waits for the server at an item that isn't downloaded | Planned |
| 5 | 5a-native | Home shelves, search and podcast episodes computed from the replica (native side) | Planned |
| 5 | 5b-ui | Home and search | Planned |
| 6 | 6-item | Full item details are cached when an item is opened online | Planned |
| 6 | 6-ui | Book, podcast and episode pages; server-only actions disabled with a reason | Planned |
| 6 | 6b-awaiting-player | The player opens on the book a queue waits for | Planned |
| 7 | 7-covers | Covers | Planned |
| 8 | 8a-auto-replica | Android Auto browses the catalog copy | Planned |
| 8 | 8b-auto-offline | Android Auto: offline marks, lists that follow changes, cover icons | Planned |

## Goal

The app works the same with or without the server. The server stays the source of the catalogue and the audio for now; in the long run it should become only a sync channel between devices.

The chosen approach is a local replica in SQLite (Room) with a sync engine in the native layer; the web app reads the replica through a data service. It is the only option considered that gives the full catalogue offline, sync while the WebView is throttled, a single source of truth for the UI, the player, the widget and Android Auto, a 10,000-item library, and a path to "server optional" with one design. It costs more up front and then grows linearly. The rejected options are listed under [Alternatives considered](#alternatives-considered).

## Scope

**Phase 1 (this document).** The server is still needed to sign in and to download; everything else works without it.

- **One active server.** The replica, progress and outbox are tagged with the active connection's `serverConnectionConfigId`. Switching servers switches the active replica; the previous one is kept, never merged. An action on a downloaded copy is recorded for the server that copy came from (the active one by default); outbox rows of an inactive server wait until it is active again. Removing a server connection keeps its rows and outbox; they are sent if that server becomes active again.
- **Target size:** 10,000 library items. Larger libraries work, without speed guarantees.
- **Written offline:** progress and "finished" for any book, including streamed books with no local copy; bookmarks (create, rename, delete).
- **Not written offline:** playlists and collections are read-only offline. Settings and Up Next stay local to the device, as today: they work offline and are not synced between devices.
- **Out of scope:** iOS; metadata editing; starting a download without the server; searching for and adding podcasts; listening stats and the account page; syncing playlists and settings; several servers at once; Local Media folders in the library tabs (they stay on the home page, as today).

**Phase 2, "server optional"** (sized separately once phase 1 ships): a library built from local folders with the full experience (series, authors, bookmarks), moving downloaded items from Paper into the new database, no longer deleting local-only items at startup (inherited from upstream, see below), and ids of the form `local_<uuid>` with no server connection.

## Today

- **No data layer.** Pages call the server directly (about 60 HTTP calls across the web app, plus socket.io). The user object, libraries, series, collections and filter data live only in Vuex memory. The native layer keeps only downloaded content in PaperDB: local items, their progress, unsent listening sessions, server connections and settings. There is no Room or SQLite.
- **Works without the server:** the home page with shelves built from local items; downloaded items (chapters, cover, queue, widget, the Android Auto "Downloads" root); Local Media from device folders.
- **Doesn't work without the server:** the Library, Series, Collections and Authors tabs are empty (`components/bookshelf/LazyBookshelf.vue` resets to nothing when there is no signed-in user); playlists, search (hidden in the app bar without a user), stats. A stream that loses the network fails with a "Playback Failed" toast and closes the player.
- **Bookmarks** live only in the in-memory user object (`store/user.js`). Since v0.16.0 they can be viewed offline, but only while that object is in memory: after a cold start without the server there are none. Creating, renaming or deleting one offline goes straight to the server (`components/modals/BookmarksModal.vue`) and is lost with an error toast.
- **"Online" means "Wi-Fi or cellular is up",** not "the server answers" (`networkConnected` in `store/index.js`). With Wi-Fi up and the server down, `layouts/default.vue` makes one 6-second connection attempt and then waits for a network change or a restart.
- **Progress lives in two worlds** with different keys (`local_*` for downloaded items, the server id for streamed ones) and three merge rules based on device clocks. Only listening sessions have an outbox (`/api/session/local-all`). For a downloaded copy of the active server, "finished" and e-book progress are written to Paper `LocalMediaProgress` and sent to the server at the same time; if that request fails, the old local-progress sync sends them later, but only for items that already have a progress record on the server (it walks the server's `mediaProgress` list), so local progress without a server record is never sent. For an item that isn't downloaded, "finished", progress resets and e-book progress go straight to the server and are lost if the request fails; so do bookmarks and playlist edits for every item. The `local-all` response is logged per session, but the whole batch is removed from the queue afterwards.
- **Local-only items are deleted at startup.** Upstream removes local items with no server link on every start (`DbManager.cleanLocalLibraryItems`). This blocks phase 2, not phase 1.

**Server constraints** (checked against server v2.37.1): there is no change log or "changes since X" query, and deletions leave no trace; the socket replays nothing after a reconnect. Lists are paged by offset without a secondary key; items, series and authors have `updatedAt`. `PATCH /api/me/progress` always overwrites and trusts the client's `lastUpdate`. `local-all` resolves conflicts by client time and overwrites a server session if given its id. Bookmarks have no id; their key is `libraryItemId + time`. `/api/me/progress` and `/api/me/bookmarks` exist from 2.36.0; older servers only have the whole `/api/me`.

## User-facing behaviour

1. **Open the app without the server: everything is there at once.** Home, Library, Series, Authors, Collections and Playlists look as they did the last time the server was reachable. Items that aren't downloaded are shown and marked as not available offline; they can't be played or downloaded.
2. **Listening, bookmarks and "finished" are saved immediately** and reach the server when it's back. If another device changed the same thing, normally the later change wins; a change made offline on this phone is applied when it's sent. Conflicts are never shown to the user.
3. **A status indicator in the app bar replaces error toasts:** offline, syncing, N changes waiting to be sent, sign in again. Tapping it shows details with Retry (and Sync now when online). No toasts about being offline.
4. **Downloaded copies always play; streaming is only the fallback when there is no local copy.** This holds for every entry point: cards, the item page, the Continue shelf, the queue, Android Auto, the widget. If the server disappears mid-stream, playback pauses with "waiting for server", the player stays open and resumes by itself when the server returns. When a download of the current book finishes, playback switches to the file at the same position.
5. **The server comes back: nothing to do.** The app refreshes the catalogue and sends what it saved.
6. **Signed out by the server.** Nothing is lost; the indicator asks to sign in again, and sending resumes after sign-in. Logout works as today: the server's data stays on the device.
7. **First start after the update, or the first connection to a server.** Tabs show what has synced so far and the indicator says the catalogue is syncing; an empty tab says the catalogue isn't loaded yet and to connect to the server once.
8. **What doesn't work offline is said upfront:** starting a download, editing playlists and collections, finding new podcasts, stats. These buttons are disabled with "requires server" instead of failing after a tap.

**Search offline** runs on the replica: title, author, series, podcast. **Podcast episodes offline:** the episode list of every podcast in the library is available offline (fetched during sync, see [Sync and merge](#sync-and-merge)).

## Architecture

**Rule: the UI reads only the local database and never waits for the server.**

| Piece | What it is |
|---|---|
| `replica.db` (new, Room) | Rebuildable copy of the active server's catalogue. Reset and re-synced if its schema changes. |
| `userdata.db` (new, Room) | Irreplaceable user data: progress, bookmarks, outbox, sync state. Versioned migrations only, never destructive. |
| `SyncEngine` (new, Kotlin, coroutines) | Process-wide singleton. `reconcile` (pull the catalogue and user data), `applyEvent` (a socket event forwarded from JS), `pushOutbox`. Called by the plugin, network callbacks and `MediaProgressSyncer`. |
| `ServerReachability` (new, Kotlin) | The only place that decides whether the server is reachable: `unknown / online / offline / unauthorized`. Notifies native code and JS. |
| `ActiveServer` (new, Kotlin) | Remembers the active server connection across logout and a refused refresh, so the replica and outbox still belong to a server on a cold start without a session. |
| `SyncRuntime.ensureInit` (new) | Lazy, idempotent start of the singletons. There is no `Application` class and the process can start without an Activity (Android Auto, the widget, media buttons), so `MainActivity`, `PlayerNotificationService` and the `AbsData` plugin all call it. Also holds the foreground flag. |
| `AbsData` (new Capacitor plugin) | Kept separate so `AbsDatabase` doesn't grow. Reads: items with filter, sort and page; item; series; authors; collections; playlists; home shelves; search; progress; bookmarks. Writes: progress, "finished", bookmarks. Control: `syncNow`, `getSyncState`, `applyServerEvent`, server state. Events: `onDataChanged {scope, ids}`, `onSyncState`, `onServerState`. |
| `$absData` (new, `plugins/data.js`) | Thin JS wrapper over `AbsData`. Returns JSON shaped like the server responses so pages change as little as possible. The name `$absData` is used because Vue 2 reserves `$data` on every component (Nuxt `inject` skips that key). In browser development it proxies to `$axios`. |
| `MediaManager` (Android Auto) | Moves from server requests to reading the replica (release 8). |

**Data flow.**

- **Read:** page → `$absData` → `AbsData` → Room.
- **Write:** `$absData.setX` → Room row and an `outbox` row in one transaction → `onDataChanged` → the page re-reads; `SyncEngine` sends the outbox while `online`.
- **From the server:** `reconcile` or a socket event → Room → `onDataChanged`. The socket stays in JS (`plugins/server.js`); its events are forwarded to `applyServerEvent`. Anything missed in the background is caught by a reconcile once the app is back in the foreground (see [Sync and merge](#sync-and-merge) for when it runs).

**Unchanged:** the stream request itself (`/play` through `ApiHandler.playLibraryItem`, used only when there is no local copy), downloads, authentication (`TokenRefresher` and `$authRenewal`, see [authentication.md](authentication.md)), the socket in JS. No WorkManager in phase 1: sync runs only inside the app process.

## Data model

Two Room databases, because their data has different value.

**`replica.db`** (every table has `serverConfigId`; primary keys are (`serverConfigId`, server id), so two accounts on the same server keep separate replicas):

- `libraries`.
- `library_items`: keyed by server id; columns for sorting and filtering (`libraryId`, `mediaType`, `title`, `titleIgnorePrefix`, `authorName`, `seriesName`, `seriesSequence`, `addedAt`, `updatedAt`, `duration`, `genres`, `tags`, `numEpisodes`, and so on); `json` of the minified item; `detailLevel` (`minified` or `expanded`); `expandedJson` (chapters, tracks, episodes) when expanded.
- `series`, `authors`, `collections` + `collection_items` (ordered), `playlists` + `playlist_items`.
- **Expanded copies.** A book's expanded copy is fetched when it is opened online (`GET /api/items/:id?expanded=1&include=progress`, one at a time — not `batch/get`, which since server 2.34 answers 403 for the whole batch if one item is inaccessible) and refreshed the next time it is opened online if `updatedAt` changed (never during reconcile). A podcast's expanded copy (its episode list) is fetched during sync for every podcast whose `updatedAt` changed. Downloaded items already have their expanded copy in Paper. Offline, a book without an expanded copy shows its metadata without chapters.
- **Covers:** a disk cache keyed by `itemId + updatedAt`, filled lazily when shown online, size-limited with eviction of the oldest (release 7).

**`userdata.db`:**

- `media_progress`: key (`serverConfigId`, `libraryItemId`, `episodeId`), where `episodeId` is empty for a book (the key can't hold null); `serverProgressId` (the server's progress id, needed to delete it); the server's `MediaProgress` fields (`currentTime`, `duration`, `progress`, `isFinished`, `hideFromContinueListening`, `ebookLocation`, `ebookProgress`, `lastUpdate`, `startedAt`, `finishedAt`); `lastUpdate` by the device clock; `source` (`device` or `server`); `pending` (has unsent changes). One row per item replaces today's two worlds: downloaded items point at the server `libraryItemId` that `LocalLibraryItem` already has. The e-book readers (EPUB, PDF, comics) write through `$absData` from release 2b.
- `bookmarks`: key (`serverConfigId`, `libraryItemId`, `time`); `title`, `createdAt`, `deletedAt` (a local tombstone until the delete is sent), `pending`.
- `outbox`: `id`, `serverConfigId`, `kind` (`progress_patch`, `progress_delete`, `bookmark_add`, `bookmark_update`, `bookmark_remove`; a plain string, so new kinds don't change the schema), `payload`, `createdAt`, `attempts`, `lastError`.
- `sync_state`: per library `lastReconcileAt`, `lastReconcileOk`, `lastError`; per server `lastMeAt`, `userJson` (the last known user object without progress and bookmarks: permissions, settings), `schemaVersion`.

**The `userdata.db` schema v1 ships with every phase 1 table** (including the per-library sync state that is only used from release 4) and its version is not raised during phase 1, so every release can roll back one version. If a bump becomes unavoidable, that release says in `CHANGELOG.md` that it can't be rolled back.

Listening sessions stay in Paper (`playbackSession`) and are sent through `local-all` as today. The outbox is for explicit actions (finished, reset, bookmarks) that sessions can't express.

**Ids:** the catalogue uses server ids, nothing of its own. `local_<uuid>` with no server connection is reserved for phase 2.

**Size:** 10,000 items × ~1.5 KB ≈ 15 MB with indexes (a minified item on a real server is 1.2–1.4 KB).

## Sync and merge

**One merge rule replaces three: the larger `lastUpdate` wins, but a local row with `pending` is never overwritten by the server until it has been sent.** The server itself trusts the client's `lastUpdate`, so the rule matches its model. In practice an unsent local action takes priority over a newer server value and, once sent, overwrites the server. Clock skew between devices is accepted as it is on the server. Conflicts aren't shown.

- **Explicit actions** ("finished", reset, e-book progress, bookmarks) set `pending` and add an outbox row.
- **Playback ticks** write `media_progress` without an outbox row and without setting `pending`: listening progress reaches the server through sessions (`/sync` and `local-all`). A tick never clears a `pending` that is already set.
- **Unsent listening sessions count as pending.** While an item has listening sessions in Paper waiting for `local-all`, its row is treated as `pending` for merging, so offline listening to a downloaded book is not rolled back by the server.

**Pull (`reconcile`):** per server, single-flight, interruptible, recorded per library in `sync_state`.

1. `GET /api/libraries` → upsert; libraries no longer on the server are deleted.
2. Per library, page through `items?limit=500&sort=addedAt`: compare `updatedAt`, upsert changed items, delete items the server no longer has. Downloaded copies in Paper are never touched by these deletes.
3. Series and authors page by page; collections and playlists whole.
4. Progress and bookmarks: `/api/me/progress` and `/api/me/bookmarks` on servers ≥ 2.36.0, otherwise `/api/me`. Progress merges by the rule above; a local row with no server record (progress deleted by another client) is deleted unless it is `pending`, together with the Paper `LocalMediaProgress` of a downloaded copy (or the next import would bring it back). Bookmarks merge as a set keyed by item + time, and tombstones hide deleted ones until the delete is sent.
5. Book expanded copies are not part of reconcile; podcast expanded copies (episodes) are fetched for podcasts whose `updatedAt` changed, one request per podcast.

**Reconcile is the safety net, not the main channel:** while the socket is connected, changes arrive as events. Reconcile runs on app start, on the socket's `init` after every (re)connect (including going `online`), and on a manual "Sync now". There is no periodic timer. **Reconcile runs only while the app is in the foreground;** in the background (Android Auto, the player service) going `online` only pushes the outbox, and the skipped reconcile is marked as deferred. Returning to the foreground runs a reconcile if one was deferred, or if the socket dropped while in the background and the last successful reconcile is older than 15 minutes; otherwise it only pushes the outbox.

**Cost** (measured on a real server): a minified item is 1.2–1.4 KB and compresses about 5× with br/gzip, so 10,000 items is ≈ 13 MB raw and ≈ 2.5–3 MB over the network, plus podcast episodes. Requests must send `Accept-Encoding: gzip` (OkHttp does by default). Conditional requests don't help: the server sends a weak `ETag` but answers `If-None-Match` with 200, so nothing relies on 304. Reconcile also runs on mobile data, with no new setting.

**Socket events:** JS forwards `item_added/updated/removed`, `items_added/updated`, `series_*`, `author_*`, `collection_*`, `playlist_*`, `library_*`, `backup_applied` (triggers a full reconcile), `user_item_progress_updated` and `user_updated` to `applyServerEvent`. `user_updated` carries the whole user object (up to ~2 MB with 5,000 progress entries) and is merged off the main thread.

**Pushing the outbox:** sequential per server, while `online` and after every write; a row is deleted only after a 2xx.

- **Backoff:** 1 s, 5 s, 30 s, 5 min.
- **401:** handed to the shared `TokenRefresher`, then retried. New code never refreshes on its own, and a response to a request sent before a logout or server switch is not applied.
- **404 and other 4xx:** the row is dropped and logged to diagnostics so it can't block the queue, and the related `pending` is cleared (a pending bookmark row is removed).
- **Rows of an inactive server** stay queued until that server is active again.
- **`progress_patch`** stores which fields the action changed. When sent, the body is those fields read from the current `media_progress` row, plus `lastUpdate`, to `PATCH /api/me/progress/:libraryItemId` (or `/:libraryItemId/:episodeId`). So "finished" or e-book progress never overwrites an audio position set elsewhere. If the row changes while the `PATCH` is in flight, another `PATCH` is queued after the 2xx; `pending` is cleared only once the latest value has been sent.
- **`progress_delete`** (a progress reset, as today) sends `DELETE /api/me/progress/:serverProgressId`; a 404 counts as success. If the item was played locally after the reset was queued, the `progress_delete` stays and a `progress_patch` with the row's current state is queued after it; once the `DELETE` is answered, the row is checked again, so playback that started after the `DELETE` was sent also gets a `PATCH`. `pending` is cleared after that `PATCH` gets a 2xx. A single `PATCH` isn't enough: when `isFinished` goes from true to false, the server resets the position itself.
- **Coalescing:** `progress_patch` rows of one item (server, item, episode) merge into one with the union of changed fields, but never across a `progress_delete` of that item; queuing a `progress_delete` removes the item's unsent `progress_patch` rows (one already in flight stays). `bookmark_add` and `bookmark_remove` of the same bookmark cancel out only if the add was never attempted; otherwise the remove is queued after it. A row already in flight is never coalesced; a new action is queued after it.

**Listening sessions**, fixes to the existing path:

- An offline segment of a streamed item gets its own uuid instead of the server session's id, so `local-all` can't overwrite the server session, and the same listening time is not sent through both `/sync` and `local-all`.
- The `local-all` response is handled per session: successful sessions are removed, failed ones stay queued for next time, except sessions the server rejected because the item or library no longer exists — those are dropped and logged, like a 404 in the outbox, or the queue would never empty. Only the snapshot that was sent is removed: a session updated by a tick while `local-all` was in flight stays and goes next time.
- A session the server accepts without taking its position (`success` with `progressSynced: false`, because the server's `lastUpdate` is newer) doesn't silently lift the unsent-session protection: a `progress_patch` with the row's position, progress and `lastUpdate` is queued, and `pending` is cleared after its 2xx.

**Paper mirror and import.** `MediaProgressSyncer` writes `media_progress` after every tick. Every accepted progress change of a downloaded copy — a tick, a user action or a server value — is also mirrored to Paper `LocalMediaProgress` from one place during phase 1. In the other direction, on every start Paper progress newer than the Room row is imported into Room (this covers time spent on a rolled-back version); each server seen in the import is remembered until its first successful user reconcile; on that reconcile every imported row of that server whose `lastUpdate` is newer than the server's, or that has no server record at all, gets `pending` and a `progress_patch` with its audio and e-book fields. This happens before the rule that deletes rows missing on the server, so imported rows are never deleted by it. Only then is that server forgotten (other servers wait for their own connection). The old local-progress sync is removed in release 2b only together with this replacement.

**Consistency:** writes are transactional per page, and a user action and its outbox row share one transaction; `onDataChanged` is coalesced to one event per scope per 500 ms; catalogue reads over the bridge are paged, at most 100 items per call (progress and bookmarks are returned whole, as the user object does today).

**Edge cases:**

1. **Bookmark tombstone vs. the server's copy.** A bookmark deleted offline stays hidden when reconcile brings it back from the server, until the delete gets a 2xx or 404; then the row is removed.
2. **The total changes during a catalogue walk.** The walk restarts once. If the total changes again, what was seen is upserted, no deletes are made, and the library is marked as not fully reconciled; the next reconcile closes the gap.
3. **404 or another 4xx in the outbox.** The row is dropped and logged to diagnostics, the related `pending` is cleared (a pending bookmark row is removed), and the queue moves on.
4. **`pending` vs. `user_updated`.** A server event with a newer `lastUpdate` doesn't overwrite a local row with `pending`; once the row is sent, the next server value applies by the normal rule.
5. **Offline segment of a stream.** Unsent time from a streamed server session collects in a Paper session with its own uuid; the same time is never sent through both `/sync` and `local-all`.

## Server reachability

`ServerReachability` states: `unknown` (no server configured, or not checked yet; a probe starts as soon as a server is configured), `online`, `offline`, `unauthorized`.

**Inputs:**

- **Active server:** configured or forgotten (`ActiveServer`). On a cold start it remembers the last server even without a signed-in session.
- **Network:** `ConnectivityManager` callbacks. Without a network nothing is probed.
- **Real requests to the active server's address** from native code (`ApiHandler`) and JS (`$nativeHttp`). Success (any HTTP status below 500, including 4xx) → `online`. Only a network failure — connect error, timeout, unknown host, TLS failure — counts as `offline`; other request errors don't. A 5xx doesn't change the state. Requests to other addresses (for example, the ping from the server connect form) are ignored; a server under a sub-path is matched by its full base address.
- **The socket:** connecting counts as a server answer. Losing it while the network is up triggers an immediate probe, so a server that went down is noticed even without HTTP traffic. A deliberate disconnect (logout) doesn't probe.
- **A refused refresh** (`rejected` in `$authRenewal`, including when there is no refresh token, as with old-style tokens) → `unauthorized`. It stays there until the user signs in or the server is removed.

**Probing:** in `offline` with the network up, `GET /ping` with a 5 s timeout and backoff 5 s → 10 s → 30 s → 60 s → 5 min. A probe succeeds only on a 2xx whose body has the `success` field; a 502 or 503 from a proxy is a failure. The backoff resets on a network change, on returning to the foreground and on a manual retry.

**Indicator** (`components/widgets/ConnectionIndicator.vue`): shown when there is an active server, not only when a user is signed in. States: server unreachable, no network, sign in again, plus today's online, cellular and metered states. "REST answers, socket doesn't" keeps today's look and is not shown as all-good. Tapping opens a dialog with Retry; for `unauthorized` it goes to sign-in.

**JS:** the store gets `serverState` for every data decision; `networkConnected` stays only for downloads. The reconnect logic in `layouts/default.vue` moves from the network watcher to `serverState`: going `online` retries authorization when there is no user and syncs listening sessions when there is, and the home shelves are reloaded. The "no signed-in user means no data" branches go away as pages move to `$absData`.

**Sign-out behaviour.** In release 1, `unauthorized` still signs out and opens `/connect` as today. From release 2b, when data no longer depends on a signed-in `user`, a refused refresh only revokes the live session: the selected library, bookmarks, progress and outbox stay, the indicator asks to sign in again, and sending resumes after sign-in. Until release 4 the catalogue tabs are empty without a `user` (as offline today) while downloaded items play and the indicator asks to sign in; this is an accepted intermediate state. An explicit logout is unchanged.

**Startup:** home and tabs open at once from the replica; authorization runs in the background; progress and bookmarks come from `userdata.db`. Offline, the user's permissions and settings come from `sync_state.userJson` as a separate cached copy in the store. It doesn't stand in for the live `user`, so when the server appears, authorization, the socket and token renewal start as usual.

## Player

**One place decides between the local copy and the stream:** a new native playback-source resolver. `AbsAudioPlayer.prepareLibraryItem` (JS passes the server id), the queue, Android Auto and session restore all go through it; JS no longer chooses between `localLibraryItemId` and the server id. `ApiHandler.playLibraryItem` (`/play`) is used only when the resolver picks the stream.

- **Server lost while streaming:** an ExoPlayer source error with a network cause (connection failure, timeout, or HTTP 502/503/504 from the stream) pauses playback in a "waiting for server" state and keeps the session. When the same server is `online` again, the item is prepared again at the same position without the user. A manual pause during the wait is kept: playback comes back paused. Closing the player, starting another item or switching servers cancels the wait; another server going `online` doesn't resume it.
- **The current streamed item finishes downloading:** playback switches to the local file at the same position.
- **The queue reaches an item that isn't downloaded while offline** (playlist, series, Up Next): the same pause with "waiting for server". The queue order is kept; nothing is skipped. See [continuous-playback.md](continuous-playback.md) for the queue itself.
- Bookmarks remain books-only: the server doesn't support bookmarks on podcast episodes, and offline mode doesn't change that.

## Migration, compatibility and rollback

- **Expand/contract.** Phase 1 adds Room next to Paper. `userdata.db.media_progress` becomes the source for reads, while every accepted progress change of a downloaded item is still mirrored to Paper `LocalMediaProgress`, and newer Paper values are imported back on every start. Removing Paper progress (contract) happens in phase 2, after a full release cycle.
- **Rolling back one version:** the old app reads Paper, which stays current thanks to the double write; it doesn't know the Room outbox, which is sent after the next update; it doesn't need the replica.
- **Schemas:** `replica.db` is reset and re-synced when its schema is incompatible, including on downgrade. `userdata.db` only gets versioned migrations, with the schema exported and committed and a test for each migration.
- **Minimum server version:** 2.13.0 (progress sync with the client's `lastUpdate`). From 2.36.0, separate `/api/me/progress` and `/api/me/bookmarks`, chosen by the server version of that connection; older servers use `/api/me`.
- **Every PR can be rolled back** one version without losing data.

## Testing

- **Kotlin (Robolectric, JVM):** `SyncEngine` against a fake server through an OkHttp interceptor, the way the existing `TokenRefresher` tests work; Room in-memory databases; table-driven tests of the merge rules (local newer, server newer, `pending`, bookmark tombstone, outbox coalescing); scenarios "offline → reconnect", "total changed during the walk", "404 in the outbox", "401 in the outbox"; `ServerReachability` as a transition table; every `userdata.db` migration.
- **JS (`node --test tests/*.test.mjs`):** `$absData` with a fake plugin, the development fallback to `$axios`, store updates from `onDataChanged`, the indicator from `serverState`.
- **Negative control:** every test that carries a fix must fail without it, shown by running it on the unfixed code or by removing the fix.
- **Device checklist** ([device-regression-checklist.md](device-regression-checklist.md)) gains: airplane mode on a cold start, Wi-Fi up with the server down, a stream losing the server with and without a local copy, a bookmark made offline appearing on a second device, a 10,000-item synthetic library on a test server.

## Releases

Each PR is useful on its own, breaks nothing and rolls back without data loss. Releases 0, 1, 3 and 7 are one PR each; release 2 is three (2a, 2b, 2c), release 4 six (4a-library, 4a-ui, 4b, 4c, 4d, 4e), release 5 two (5a, 5b), release 6 three (6-item, 6-ui, 6b) and release 8 two (8a, 8b); see [Status](#status). Each PR adds an entry to `CHANGELOG.md` and, if behaviour on the device changes, items to [device-regression-checklist.md](device-regression-checklist.md).

| Release | What | Done when |
|---|---|---|
| 0 | This document | The PR is accepted. |
| 1 | "Server down" ≠ "no network": `ServerReachability`, `ActiveServer`, `AbsData` and `$absData` (server state only), `serverState` in the store, the app-bar indicator, automatic reconnect | A server turned off while Wi-Fi is up is noticed without a restart and the indicator changes; a server turned back on is picked up by itself (re-authorization and session sync without user action). |
| 2a | `userdata.db`, outbox, sync engine core; bookmarks offline | Bookmarks created, renamed and deleted in airplane mode show no toast and reach the server after the network returns; bookmarks are there after a cold start without the network. |
| 2b | Progress and "finished": one merge rule, `userJson`, playback ticks written to Room, the old progress sync replaced; direct progress requests are gone, everything goes through the outbox; `unauthorized` no longer signs out | "Finished" and progress resets made in airplane mode reach the server and other devices; a revoked session keeps the library, bookmarks and progress, and the queue is sent after sign-in. |
| 2c | Listening sessions: own uuid for offline stream segments, per-session `local-all` handling, a `progress_patch` when `local-all` doesn't take the position | After a server outage mid-stream the server has both sessions, total listening time matches, and the server session isn't overwritten. |
| 3 | Player: downloaded copy always wins, "waiting for server", switch to the file after download | A stream that loses the server doesn't close the player and resumes by itself; a book with a local copy is never streamed. |
| 4 | Catalogue offline: `replica.db`, reconcile, socket events, catalogue reads in `AbsData`/`$absData` (4a-library); then one PR per tab: Library (4a-ui), Series, Authors, Collections, Playlists | Every tab opens in airplane mode with its last state. |
| 5 | Home and search from the replica: native shelves, search and podcast episodes (5a), then the pages (5b) | Home and search are identical online and offline. |
| 6 | Any item page offline: expanded-copy cache (6-item), "not available offline" markers and disabled buttons with a reason (6-ui), the player on the item a queue waits for (6b) | An item that isn't downloaded opens offline. |
| 7 | Covers offline: disk cache | Covers of shelves that were opened are visible offline. |
| 8 | Android Auto from the replica: `MediaManager` reads Room (8a); offline marks, lists that follow changes, cover icons (8b) | Android Auto offline shows the whole catalogue and plays downloaded items. |

## Risks

- **Regular merges from upstream.** Startup, the store and the home page still match the official Audiobookshelf app; moving them to `$absData` will cause merge conflicts there. Mitigation: one page at a time, new logic in new files.
- **Offset paging without a secondary key** can miss an item if the catalogue changes during a walk. Mitigation: restart when the total changes, and the next reconcile. A delete plus an add during the walk leaves the total unchanged and can drop an item from the replica until the next reconcile; accepted.
- **`user_updated` up to 2 MB** for large progress after every `PATCH`. Mitigation: parsed off the main thread, coalesced.
- **Device clocks:** the `lastUpdate` rule inherits the server's clock-skew problem. Accepted on purpose.
- **Bridge performance on lists:** at most 100 items per call; `LazyBookshelf` already loads page by page.
- **Paper and Room in parallel until phase 2:** progress of downloaded items is written twice. Mitigation: every accepted change is mirrored to Paper from one place, newer Paper values are imported back on every start, and a test checks that the two don't diverge.

## Alternatives considered

- **B. Replica and sync in JS.** Sync only while the WebView is alive; native code (Android Auto, the widget, the session outbox) can't see the replica, and the two progress worlds remain.
- **C. A caching HTTP layer.** Only visited URLs work offline, and replaying `PATCH` requests has no merge. A dead end.
- **D. An embedded local API server.** Means reimplementing ~30 endpoints, shelves, filters, search and the server's socket; the app turns into a server.
- **E. A JSON shim over snapshots.** 13 MB of JSON in WebView memory and the server's filters rewritten in JS. A dead end.
- **A server-side change log** (`changes?since=` with tombstones) is not part of phase 1: the app is public and must work with a stock server, which has no such endpoint. It could become a separate, feature-detected speed-up later.
