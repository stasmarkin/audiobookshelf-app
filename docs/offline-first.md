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
| 6 | Book, podcast and episode pages; server-only actions disabled with a reason | This release |
| 7 | Covers offline | Planned |
| 8 | Android Auto from the replica | In progress |

## Release 8: Android Auto

- **Replica first:** `MediaManager` answers the browse tree through `AutoCatalog`, which reads `CatalogReader` (the same filters, sorts, collapsed series, shelves and search as the tabs) and parses its server-shaped answers into the classes Android Auto already used. Progress in every description is `media_progress` of the active server (`MediaManager.progressOf`), not `/api/authorize`.
- **Server fallback per answer:** an answer whose `replica.reconciledAt` is set never reaches the server; an unreconciled one goes to the server while it is `ONLINE`, otherwise the replica's partial answer is shown (`replicaOr`). Node ids are unchanged, so the host's remembered ids keep working.
- **Root at once:** the root is built from `AutoSnapshot` (libraries with audio, reconciled libraries, Continue as the union of the `continue-listening` shelves, the progress map), recomputed only on `catalog`/`progress`/`user` changes or another active server; playback ticks reread only the progress, at most every 10 s. Upstream's `AutoRootLoader` still loads the server in the background for streaming and never reconciled libraries; nodes of reconciled libraries are served before it finishes (`servableWithoutServer`).
- **No I/O on the browser thread:** `onLoadChildren`, `onSearch` and resolving a chosen id run on IO coroutines. A chosen id is found in memory, then in the replica (books and episodes after a process restart), then on the server while reachable.
- **Downloaded mark:** the start's rule (`PlaybackSourceResolver`): same server, `ino`, a partial copy does not count. Podcast nodes keep their server id, so their episode list stays complete.
- **Not yet (release 8b):** marking and refusing undownloaded items offline, refreshing open lists on changes, reconciling while Auto is connected, cached cover icons.

## Release 6: item pages

- **Two steps:** a page draws from the replica at once (`$absData.getLibraryItem({ id })`), then asks for a refresh (`refresh: true`). Online, native `ExpandedItemCache` fetches `GET /api/items/:id?expanded=1&include=progress,rssfeed` only when the replica has no expanded copy of this version, writes it through `CatalogEventApplier` like a socket event, and answers with it. Progress in the answer is not stored.
- **`detailLevel`:** `expanded` (chapters, tracks and files of the current version), `stale` (an older expanded copy: its chapters with the fresh title, authors, series and cover of the minified row) or `minified` (never opened online: metadata and counters, no chapters). A minified page shows the chapters of a downloaded copy, or "Chapters appear after you open this book online once".
- **Not available offline:** while the server is known to be away, a server item without a downloaded copy of the active server (an episode: without this episode downloaded) is marked "Not available offline"; its page opens, Play and Read are disabled. A copy on an SD card that is not inserted still counts as downloaded.
- **Requires the server:** actions only the server can do stay visible, disabled and labeled "Requires the server", and their handlers do nothing offline: downloads (book, episode, series, "Download & Play", Retry), playlists, removing an episode from the server, RSS feeds, sending an e-book, e-book file status, opening the web client, statistics and Year in Review, adding a podcast and searching its feed. Both rules live in `mixins/requiresServer.js` (`isServerOnline`, `isServerAway`, `isNotAvailableOffline`, `serverOnlyItem`).
- **Known limits:** a book never opened online has no chapters offline; an episode edited on the server shows the change offline only after the podcast itself changes next.
