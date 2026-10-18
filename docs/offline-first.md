# Offline-first

Audiobookshelf+ works without its server: the catalog, Home, search and item pages read a copy on the phone, progress, "finished" and bookmarks are written on the phone first and reach the server later, and downloaded items always play from the phone. The server is the source of truth for the catalog; the phone never changes the catalog itself.

## How it fits together

- **`replica.db` (Room, `sync/`, `db/replica/`):** a read-only copy of the active server's catalog: libraries, items (minified, plus an expanded copy for items opened online), series, authors, collections, playlists and podcast episodes. It can always be rebuilt from the server; a schema downgrade empties it until the next sync.
- **`userdata.db`:** progress, "finished" and bookmarks with an outbox of changes waiting for the server. Kept when the catalog copy is rebuilt.
- **`SyncEngine`:** reconciles both with the active server (on start, on reconnect, after a long time in the background), applies socket events through `CatalogEventApplier` in `updatedAt` order, and reports changes to JS as `onDataChanged` topics (`items:<libraryId>`, `episodes:<libraryId>`, `series:<libraryId>` and so on).
- **`ServerReachability`:** one server state for the whole app (`online`, `offline`, `unauthorized`, `unknown` before the first check), shown by the app-bar indicator.
- **`AbsData` plugin and `$absData` (`utils/dataService.js`):** every page reads through `$absData`; native answers from the replica, and only while a library was never copied does the read fall back to the server.

## Releases

| Release | What works without the server | Status |
|---|---|---|
| 1 | "Server unreachable" is told apart from "no network"; the app stays signed in | Done |
| 2 | Bookmarks, progress and "finished" are kept offline and sent later | Done |
| 3 | Player: downloaded items never stream, a stream waits for the server instead of failing | Done |
| 4 | Catalog tabs: Library, Series, Authors, Collections, Playlists | Done |
| 5 | Home and search computed from the replica; podcast episodes in the replica | Done |
| 6 | Book, podcast and episode pages; server-only actions disabled with a reason | Done |
| 7 | Covers offline | This release |
| 8 | Android Auto from the replica | Planned |

## Release 6: item pages

- **Two steps:** a page draws from the replica at once (`$absData.getLibraryItem({ id })`), then asks for a refresh (`refresh: true`). Online, native `ExpandedItemCache` fetches `GET /api/items/:id?expanded=1&include=progress,rssfeed` only when the replica has no expanded copy of this version, writes it through `CatalogEventApplier` like a socket event, and answers with it. Progress in the answer is not stored.
- **`detailLevel`:** `expanded` (chapters, tracks and files of the current version), `stale` (an older expanded copy: its chapters with the fresh title, authors, series and cover of the minified row) or `minified` (never opened online: metadata and counters, no chapters). A minified page shows the chapters of a downloaded copy, or "Chapters appear after you open this book online once".
- **Not available offline:** while the server is known to be away, a server item without a downloaded copy of the active server (an episode: without this episode downloaded) is marked "Not available offline"; its page opens, Play and Read are disabled. A copy on an SD card that is not inserted still counts as downloaded.
- **Requires the server:** actions only the server can do stay visible, disabled and labeled "Requires the server", and their handlers do nothing offline: downloads (book, episode, series, "Download & Play", Retry), playlists, removing an episode from the server, RSS feeds, sending an e-book, e-book file status, opening the web client, statistics and Year in Review, adding a podcast and searching its feed. Both rules live in `mixins/requiresServer.js` (`isServerOnline`, `isServerAway`, `isNotAvailableOffline`, `serverOnlyItem`).
- **Known limits:** a book never opened online has no chapters offline; an episode edited on the server shows the change offline only after the podcast itself changes next.

## Release 7: covers

- **Route:** on native, the cover getters (`utils/coverUrl.js`, `store/globals.js`) build `/_absplus/cover/<itemId>?ts=<updatedAt>[&raw=1]` on the app's own origin, without the server address or token. `CoverWebViewClient` (`media/CoverRoute.kt`) intercepts only that path on the origin of `bridge.localUrl`; an id that fails `DownloadIdentifiers.isSafe` is a 404 without touching the disk or the network. Web dev mode builds the server URL as before.
- **Cache:** `media/CoverStore.kt` keeps files `filesDir/cover-cache/<itemId>_<updatedAt>.<ext>` (the file name is the whole index), one version per item, up to 150 MB with least recently used eviction; files over 5 MB, empty bodies and answers that are not `image/jpeg|png|webp|gif` (a captive portal's page) are never stored. `filesDir`, not `cacheDir`: the system clears the cache folder first, and offline covers are data the user sees.
- **Decision:** `media/CoverCache.kt`. A hit is served from disk without the network. A miss online is fetched once from the active server (one request for parallel asks, at most 4 at a time) with `Authorization: Bearer` and the server's custom headers, and stored; the route answers at once and the download happens when the WebView reads the body. Offline, or when the fetch fails, the newest cached version is served even if it is older than the requested one, otherwise 404 and the card shows its title and author. A request without `ts` takes the version from the replica row. `raw=1` (item page, player, full-screen cover) is proxied online and never stored, and seeds the standard cover; offline it gets the standard cover.
- **Native:** `PlaybackSession.getCoverUri` uses the cached file of a streamed book through the `FileProvider` path `cover_cache` (a downloaded copy's cover still wins; cast keeps the server URL), so the notification, lock screen and widget show it offline and after a restart. A cover URI with the `content` or `file` scheme is decoded by `CoverArt.decodeLocal`, and a vanished file falls back to the default artwork. The awaited book of a queue takes the cached cover when there is no copy. `CoverCache.init` runs from `SyncRuntime.ensureInit` and from the widget's fallback state, so a launcher-started process has it too.
- **No reachability reports:** cover requests do not report to `ServerReachability`, like `NoReachabilityReports` for catalog pages (`SyncHttp.kt`): a slow cover says nothing about the server as a whole and must not move the indicator. This departs from the rule that every server request reports its answer.
- **Known limits:** a cover never shown online is not available offline; Android Auto list icons come in release 8.
