# Changelog

Audiobookshelf+ changes by release. Earlier releases, their `versionCode`s and dates are recorded in
`docs/release-process.md`, and the feature list in `readme.md` describes what the app contains.

## Unreleased

Merged into `plus`, not yet in a published release.

- **Android Auto reads the catalog copy:** Android Auto opens with every section at once and browses your libraries
  from the device's copy of the catalog, also without the server. Continue, Recent, Libraries, authors, series,
  collections, Discovery, podcasts and search come from the same copy and the same rules as the app's tabs, with the
  progress kept on the phone, so they open instantly online and after a cold start in airplane mode; all podcasts,
  series and authors are listed (before, only the first 100 podcasts). Search now also finds podcasts. A book or episode
  chosen after Android Auto restarted the app plays instead of doing nothing; "play <title>" by voice offline prefers a
  downloaded match; Play without a choice resumes the latest started book or episode. The downloaded mark follows the
  same rule as playback, so a copy downloaded with another account of the same server is not marked. A library that was
  never copied is read from the server as before.
- **Android Auto offline:** without the server, a book or episode that is not downloaded says "Not available offline"
  in its subtitle, and choosing it shows that message at once instead of nothing happening. Covers you have seen show in
  Android Auto also offline, and covers of lists you open online are kept for later. Open lists update when the catalog,
  your progress or the server's state changes, but not on every playback tick. While Android Auto is in use, the
  catalog copy syncs with the server as when the app is open (until 10 minutes after Android Auto's last request), so
  new books appear in the car without opening the phone; this widens the rule that the catalog syncs only with the app
  in the foreground.
- **Covers offline:** covers you have seen online now show offline: on the shelves, tabs, Home, search, collections,
  playlists, item pages and in the player, also after a cold start in airplane mode. The app keeps up to 150 MB of
  covers on the device and drops the least recently shown ones beyond that; a cover seen once is not downloaded again.
  The notification, lock screen and widget use the same copy, so a streamed book shown before keeps its cover there
  offline too, and so does the book a queue waits for. Item pages and the player show the standard cover offline
  instead of the full-size original. Known limit: a book whose cover was never shown online shows its title and author
  offline.
- **The player opens on the book a queue waits for:** pressing Play on a series, collection or playlist whose next
  book is not downloaded while the server is away now opens the player on that book (title, author, its position, the
  downloaded cover if there is one, "Waiting for server"), instead of only a "Waiting for the server" toast, or of the
  previous book's title when a player was open. The notification, the lock screen and the widget show the same book.
  When the server is back it plays as before. A book that the catalog copy does not have yet still shows the toast.
  Fixed: with the screen off, a waiting book could stay silent for minutes after the server came back, until the app
  was opened; the wait now checks the server every 15 seconds and starts by itself, also before the app signed in.
- **Book, podcast and episode pages offline:** every book, podcast and episode page opens from the catalog copy,
  online at once and offline too, also after a cold start in airplane mode. Online the page then fills in the
  chapters, tracks and files within a second; the full copy is fetched once per version of the book, so opening it
  again costs no request, and it stays available offline. Tapping a card or a search result that is not downloaded
  now opens its page instead of a "Not available offline" toast; there Play and Read are shown disabled with "Not
  available offline". A downloaded book plays from the phone with its chapters, even if it was never opened online.
  A podcast offline lists its episodes; downloaded episodes play, the others are marked, and the podcast's Play picks
  the next downloaded episode. Latest episodes open offline. A book deleted on the server returns to the bookshelf,
  or opens its downloaded copy. Known limits: a book never opened online shows "Chapters appear after you open this
  book online once" instead of its chapters; an episode edited on the server shows the change offline only after the
  podcast itself changes next.
- **Server-only buttons say so offline:** downloads (book, episode, series, "Download & Play", Retry), playlists,
  removing an episode from the server, RSS feeds, sending an e-book to a device, e-book file status, the web client,
  statistics and Year in Review, adding a podcast and searching its feed stay visible offline, greyed out with
  "Requires the server", and do nothing when tapped.
- **Android Auto without a reachable server** (issue #33, Discussion #30): the Android Auto menu now opens at once
  with Downloads, instead of waiting for the server checks. With a server that was unreachable or stalled, the menu used
  to appear only after the connection attempts gave up (about 3 s per saved server, or about 30 s when the server
  answered its ping but then stalled). Downloaded books and podcast episodes can be opened and played right away.
  Continue, Recent and Libraries are added as soon as the server has answered. Fully offline it was already immediate.
- **Long jumps** (Settings → Long jump backwards / forwards time): an optional second jump in each direction, from 15
  seconds to 30 minutes, Off by default. When on, the full player shows it outside the short jump. The notification,
  headset, widget and Android Auto keep the short jumps. For everyone, the full player's jump keys are now chevrons
  centered on Play (the short jumps used circular arrows); the collapsed player keeps the arrows.
- **Downloads at the Android 15 time limit** (contributed by Stanislav Markin): Android 15 lets an app's downloads run
  in the background for 6 hours a day. When that time is used up, downloads now pause instead of the app crashing, and
  Android restarting the download service no longer crashes it again and again. Paused downloads keep what they have
  downloaded, stay in the list as "Continues when the app is open", and continue from there when the app is opened. A
  notification says that Android paused them. Background auto download runs continue in their own windows.
- **Home and search offline:** Home (Continue Listening and Reading, Continue Series, Recently Added, Recent Series,
  Discover, Listen and Read Again, Newest Authors; in podcast libraries Continue Listening, Newest Episodes, Recently
  Added and Listen Again) and search are now computed on the phone from the catalog copy and your progress, online
  and offline alike, so they open on a cold start in airplane mode and show the same shelves and results as online.
  Progress from another device, "Mark as finished" and a book that starts playing move books between shelves within
  a second, without leaving Home. The catalog copy now also keeps the episodes of every podcast, fetched once per
  podcast during the background sync and again only when it changed. Search finds books by title, subtitle, ASIN and
  ISBN, series, authors, narrators, tags and genres, and podcasts and their episodes; case and accents do not
  matter, also in Cyrillic. The search button shows whenever a server is saved, not only while signed in. Offline,
  results and Home episode cards that are not downloaded are marked "Not available offline" and tell so when
  tapped; downloaded ones open their copy, and an episode card only counts as downloaded when that episode is. The
  "continue" shelves of downloaded items show only while the catalog copy has none of its own (before the first
  sync), so they no longer appear twice. Until a library has been copied once (podcast libraries: with their
  episodes), Home and search read the server as before; offline Home shows the downloaded items and search says the
  catalog isn't downloaded yet. Known differences from the server: search finds a few more matches with accents,
  and `%` and `_` are searched as plain characters; counts of narrators, tags and genres include only books you can
  access; a podcast episode edited on the server shows the change offline only after the podcast itself changes
  next; a series whose membership is still being synced may briefly be missing from Continue Series and Discover;
  Discover keeps its picks until the app restarts (the server reshuffles every 30 minutes).
- **Catalog copy keeps podcast episodes (schema 2):** updating keeps the offline catalog. Going back to a version
  before this one empties the offline catalog until the next sync with the server; downloads, progress and bookmarks
  are not affected.
- **Playlists offline (all five catalog tabs now open offline):** the Playlists tab, a playlist's page and the
  "Add to playlist" list read the same catalog copy, so they open without the server with your last synced
  playlists, books and podcast episodes in the playlist's order. Play and the add-to-queue button of a playlist work
  offline too: downloaded books and episodes play from the phone and an item that is not downloaded waits for the
  server. Only downloads made with the current account and server are used. Playlists made, changed or deleted in
  the web client follow on the phone within a few seconds while the app is connected, also on an open playlist page,
  and the Playlists tab appears or disappears with them. Creating a playlist and adding or removing items need the
  server: offline these buttons stay visible, disabled and marked "Requires the server". The "Add to playlist" list
  now shows every playlist of the library, also beyond the first page. Until a library's playlists have been copied
  once, they read the server as before; offline the tab says the catalog isn't downloaded yet.
- **Collections offline:** the Collections tab and a collection's page read the same catalog copy, so they open
  without the server with the last synced collections, each book in the collection's order (as arranged in the web
  client). Play and the add-to-queue button of a collection work offline too: downloaded books play from the phone
  and a book that is not downloaded waits for the server. A collection reordered, renamed, changed or deleted in the
  web client follows on the phone within a few seconds while the app is connected, also on an open collection page;
  a deleted collection goes back to the Collections tab. Books you can't access are left out, and a collection with
  none left is hidden, as on the server. Offline, covers of books that are not downloaded show the title and author
  placeholder. Until a library's collections have been copied once, both read the server as before; offline the tab
  says the catalog isn't downloaded yet.
- **Authors offline:** the Authors tab reads the same catalog copy, so it opens without the server with the last
  synced authors and their book counts, sorted by name (the server listed them in no set order). Tapping an author
  opens the Library tab filtered to their books, offline too, also for an author added on the server since the
  last full sync. Offline, or when a photo fails to load, author photos show the placeholder. New, renamed, merged
  and removed authors and changed book counts follow the server while the app is connected. Until a library's
  authors have been copied once, the tab reads the server as before; offline it says the catalog isn't downloaded
  yet.
- **Series offline:** the Series tab and a series' page read the same catalog copy, so they open without the server
  with the last synced series, their books in series order and, with "Collapse Series" on in the series page menu,
  the books of other series folded into one card as on the server. Play and the add-to-queue button of a series work
  offline too: downloaded books play from the phone and a book that is not downloaded waits for the server. Fixed:
  offline, the series Play prompt ("Download or stream") counted the downloaded books as missing. A series that no
  longer exists now goes back to the Series tab instead of an error page. Until a library's series have been copied
  once, both read the server as before.
- **Library tab offline:** the app now keeps a copy of each library's catalog (libraries, books, series and the
  filter lists) on the phone and the Library tab reads it, so it opens without the server, also on a cold start in
  airplane mode, with the last synced state and the same filters, sorting and collapsed series as online. The copy is
  filled in the background after each full sync with the server (on start, on reconnect, on Sync now; the app-bar
  indicator shows "Syncing catalog" meanwhile), only rewrites what changed, and follows changes made on the server
  while the app is connected. Until a library has been copied once, the tab reads the server as before; offline it
  says the catalog isn't downloaded yet. Offline, books without a downloaded copy are dimmed, marked "Not available
  offline" and have no Play button. The first copy of a large library takes a while and some mobile data. Known
  limits: covers of books that are not downloaded are placeholders offline; opening such a book offline still needs
  the server; filtering by an author or narrator whose name contains a comma, and by "supplementary e-book", needs
  the server (offline the tab says so).
- **Server unreachable is no longer "connected":** when the server goes down while Wi-Fi stays up, the app notices
  within seconds (the live connection drops, the app checks the server's `/ping`; up to about a minute when the whole
  server machine is switched off and the connection just goes silent) and the indicator in the app bar
  shows "server unreachable" instead of a connected cloud; tapping it offers Retry. While it is down the app keeps
  checking (5 s, 10 s, 30 s, 1 min, then every 5 min, and at once when the network changes or the app returns to the
  foreground), and when the server answers again the app signs in, reloads the library and syncs listening sessions
  by itself, without a restart. A server that refuses the sign-in shows "sign in again". Downloads still follow the
  network as before.
- **Bookmarks work offline:** creating, renaming and deleting a bookmark no longer fails without the server. The
  change is saved on the device at once, the connection indicator shows how many changes wait to be sent, and they
  reach the server, in order, as soon as it is reachable (tap the indicator and Sync now to send them at once).
  Bookmarks are also shown after a cold start without a network, and bookmarks deleted on another device disappear
  on the next sync. A bookmark made on a downloaded copy of another saved server is sent to that server when it is
  the active one again. Rolling back to the previous version keeps unsent offline bookmarks on the device; they are
  not shown or sent by that version and go out after updating again.
- **Finished and progress work offline:** marking a book or episode finished or not finished, resetting progress and
  the e-book reading position no longer fail without the server. They are saved on the device at once, show as
  waiting changes in the indicator and reach the server when it is reachable, also for a downloaded copy of another
  saved server (sent when that server is active again). Progress, finished marks and your permissions are shown after
  a cold start without a network. One rule decides between the device and the server: the later change wins, and an
  unsent change on the device, including unsent listening to a downloaded book, is never overwritten by the server.
  A value from another device moves a paused player only when it wins. Progress deleted on another device or in the
  web client disappears here on the next sync. The previous separate sync of downloaded-book progress is replaced by
  this rule; progress saved by an older version (or after rolling back) is brought in on every start and sent when
  it is newer than the server's. Progress the server has no record of (for example reset in the web client) is not
  sent back; listening to those books still reaches the server with its sessions.
- **A refused sign-in no longer throws you out:** when the server refuses the session (for example after a password
  change), the app stays where it was with the selected library, progress and bookmarks; downloaded books keep
  playing, changes keep waiting, and the indicator asks to sign in again (it opens the sign-in form for that server).
  Until you sign in, the library tabs stay empty as they do offline. An explicit sign-out is unchanged.
- **Rollback and a known limit:** rolling back to the previous version is safe: the downloaded-book progress it reads
  is kept up to date alongside, and it sends unsent "finished" and resets itself. Progress of a downloaded book reset
  in the web client before updating to this version, while the device never learned the server's record, stays on the
  device as its local position and is not sent to the server (as in the previous version).
- **Listening sessions offline:** listening to a streamed book while the server cannot be reached is no longer lost or
  inflated. The time the server did not get is saved as a listening session of its own, also when the app is closed
  before it could send it, and sent when the server is back, next to the streamed session, which it no longer
  overwrites. Rarely, when the server applied an update but its answer was lost, or the app was closed while an update
  was on its way, that time also goes into the offline session and can count twice.
  The server's answer is now read per session: the ones it took leave the phone, the ones it rejects because their
  book or library no longer exists are dropped (with a Diagnostics log entry), and any other is sent again next time.
  When the server takes a session but not its position, because its own record is newer, the phone's position is
  sent only if it is further on than the server's; otherwise the phone takes the server's. Rolling back to the
  previous version is safe: the saved sessions are ordinary listening sessions it sends itself; only the last minute
  or so of streamed time not settled at that moment is not sent by it (it is sent after updating again).
- **The player offline:** a downloaded book now always plays from the file, from every place that starts it (card,
  item page, Continue shelf, queues, Android Auto, widget and headset), also when it was started by its server id or
  downloaded through another library; the cellular prompt appears only when the book really streams. When the server
  of a streamed book goes away (or answers 502/503/504, or a proxy's 52x), the player no longer closes with "Playback
  Failed": it pauses with "Waiting for server" and continues by itself at the same position when the server is back.
  A series or collection queue that reaches a book that is not downloaded while the server is away waits on that
  book instead of skipping it. When the download of the book being streamed finishes, playback switches to the file
  at the same position. A book starts where it was last left on this phone, also after a seek while paused offline.
  Rolling back to the previous version is safe: a queue saved by this version streams its entries there, and after a
  restart it is not reattached to a downloaded book's restored session (nothing is lost).

## v0.19.0 — Podcast auto downloads, SD card storage, E-ink and Bluetooth controls

**Released 2026-10-15:** versionCode 137, versionName `0.14.2-beta` (upstream base unchanged), `PLUS_VERSION`
`0.19.0`, tag `v0.14.2-beta-plus-v0.19.0`. Changes since v0.18.2 (`ef757efd`).

### Podcasts

- **Automatic podcast downloads** (contributed by Stanislav Markin, PR #23): new episodes of the podcasts you choose
  download on their own. Settings → Auto Download Settings turns it on and sets the defaults (all podcasts or chosen
  ones, how many of the latest episodes, the check interval, Wi-Fi only, only while charging). Each podcast's ⋮ menu →
  Auto download… overrides them for that podcast.
- **Managed or Keep all:** per podcast, "Automatically manage downloads" (the default) removes older automatic
  downloads once you have finished them or they fall outside your episode limit, unless you have started them;
  "Keep all downloads" keeps every downloaded episode until you delete it. Episodes you download yourself are never
  removed automatically.
- **Automatic downloads are marked** "Auto download" in the episode list, and an episode that waits says why.
- **Per-podcast episode order** (contributed by Stanislav Markin, PR #22): each podcast can keep its own sort; Next
  Episode follows the order you see.
- **Podcast library navigation:** every tab shows both its icon and its label: Home (house), Latest (clock), Library
  (stacked books) and Add (plus), with equal widths and screen-reader names.

### Downloads and storage

- **Downloads on an SD card** (contributed by Stanislav Markin, PR #29): Settings → Download storage moves downloads
  to a removable SD card or back to internal storage. Moves are journaled and checked file by file; the old copy is
  only removed after the new one has been verified again after a restart. While the card is missing or not
  recognized, work that needs it waits; downloads, episodes and progress return when it is back. Files on the SD card
  are removed when the app is uninstalled.
- **Interrupted downloads continue:** a download stopped by Android or a restart continues where it stopped, as long
  as the file on the server is unchanged (otherwise it starts again from the beginning).
- **Downloads screen states:** it says when a download waits for Wi-Fi, charging, a network, the SD card or for the
  app to be opened, and when it is held because its server sign-in changed (Retry is safe and never deletes anything).
- **Storage moves:** interrupted moves, items still to move and "Moved N items" are shown in Settings.
- **One record per episode:** downloading an episode again no longer adds a second copy to the episode list.
- **Progress cleanup:** deleting a downloaded book or episode removes its on-device progress at once; listening
  history on the server is unchanged.
- **Safe files:** empty or cut-short transfers are never saved as downloaded files, two episodes can never overwrite
  each other's file, and deleting one download never deletes a file another one uses.
- **Large download queues** (contributed by Stanislav Markin, PR #25): a queued episode no longer stores a full copy
  of its podcast, so long queues use far less memory. Older saved queues are converted on the next start.
- **Download reliability:** fixed a crash when the download list subscribed while downloads were starting or
  finishing, and made Retry and new download requests safe while other downloads change the queue.

### Playback

- **Bluetooth / external media controls** (Settings → Playback Settings): choose what Next and Previous from
  headsets, watches, cars, the notification and the lock screen do: **Skip by time** (the default, using your skip
  intervals), **Navigate chapters** (audiobook chapter markers; Previous restarts the chapter after 4 seconds) or
  **Navigate audio files**. Missing or invalid chapters and podcasts fall back to time skips. Navigation stays within
  the current book or episode and never advances a queue. Fast Forward and Rewind always use your skip intervals.
  Repeated deliveries of one key press act once; the home-screen widget's buttons act on every tap. Details:
  `docs/external-media-controls.md` (Discussion #27; builds on the upstream investigation in issue #352 and doofmars's
  proposal #1812).
- **Update once a minute** (contributed by Stanislav Markin, PR #24): a player-menu option that shows times as H:MM and
  redraws the player only when a minute passes, for e-paper screens. Seeking and other changes still redraw at once.

### Appearance

- **E-ink theme** (contributed by Stanislav Markin, PR #24): Settings → Theme → E-ink: paper and ink without shadows,
  gradients or tints. The home-screen widget uses its standard look with E-ink.
- **Reduced motion** (contributed by Stanislav Markin, PR #24): with Android's "Remove animations" on, the app stops
  sliding, pulsing and scrolling titles in every theme; loading spinners keep turning.
- **Light themes:** the sort-direction arrow and multi-select check marks are readable in Light and light custom
  themes (they were nearly invisible).

### Security and maintenance

- **Download credentials:** download identifiers are validated, and credentials are only sent to the server a
  download belongs to (H01, H04). Refresh tokens are never sent through redirects (R01).
- **PDF reader:** script evaluation in PDF files is turned off (CVE-2024-4367 mitigation).
- **Dependencies:** Capacitor 7.6.9, Android Gradle Plugin 8.9.1 (Android 16 / API 36) and socket.io-parser 4.2.7
  (GHSA-677m-j7p3-52f9, GHSA-2m8v-j782-fhvr).
- **Community:** contributor guide, issue forms, a private security reporting policy (`SECURITY.md`) and a dependency
  review check for pull requests.

### Known limitations

- **No downgrades:** after installing v0.19.0, do not install an earlier version over it. Earlier versions cannot read
  the device data this version saves and close at start (installing v0.19.0 again fixes it). The in-app updater never
  offers an older version. Never downgrade after moving downloads to an SD card.
- **SD card:** tested on virtual and emulated cards; removing a physical card or losing power during a move has not
  yet been tested on real hardware. Some moves keep both copies until the next restart, which uses extra space for a
  while. Keep backups of downloaded media that matter to you.
- **Automatic downloads** run in Android's background windows; a large episode may finish over several windows or when
  the app is opened.
- **Bluetooth controls** were tested with Android media keys, MediaSession controllers, the notification and the lock
  screen; individual headsets, watches and cars may send different commands.
- **Progress from another device:** while an audiobook is playing, progress updates from another device are not
  applied (unchanged from v0.18.0).

## v0.18.2 — LLAMA landscape cover frame fix

**Released 2026-10-13:** versionCode 136, versionName `0.14.2-beta` (upstream base unchanged), `PLUS_VERSION`
`0.18.2`, tag `v0.14.2-beta-plus-v0.18.2`. Maintenance release; changes since v0.18.1 (`76abcf09`).

- **LLAMA full player in landscape:** the cover's frame now fits the cover itself, with no empty space beside it,
  sits on the bottom edge of the screen, stays below the top buttons and is centered in the left half. Square, tall
  and wide covers keep their shape and are never cropped. Portrait and the Dark, Black and Light themes are
  unchanged.

## v0.18.1 — Up Next restoration fix

**Released 2026-10-12:** versionCode 135, versionName `0.14.2-beta` (upstream base unchanged), `PLUS_VERSION`
`0.18.1`, tag `v0.14.2-beta-plus-v0.18.1`. Maintenance release; changes since v0.18.0 (`aaa10605`).

- **Up Next after resuming:** after the app had been closed by Android (for example after a long break or an app
  update), pressing Play on the widget, notification or headset resumed the book but Up Next stayed empty. The queue
  now appears again, in its original order and position. Details: `docs/continuous-playback.md`.

## v0.18.0 — RICE customization and sign-in reliability

**Released 2026-10-12:** versionCode 134, versionName `0.14.2-beta` (upstream base unchanged), `PLUS_VERSION`
`0.18.0`, tag `v0.14.2-beta-plus-v0.18.0`. Changes since v0.17.0 (`a9084e19`).

- **RICE customization** (Settings → Appearance), off by default. Dark, Black, Light and LLAMA are unchanged.
  - Ten starter palettes adapted from Catppuccin (Mocha, Latte), Dracula, Nord, Gruvbox, Tokyo Night, Rosé Pine,
    Solarized (Dark, Light) and Everforest, with their licences and sources listed in the app.
  - Personal presets (up to 32): bounded color and style options, a live preview, Apply, Cancel and Reset, and a
    readability check before saving. Damaged saved appearance data falls back to the last good copy or a built-in.
  - Device colors: on Android 12 and newer, follows the system color palette (including Samsung One UI's Color palette)
    and system light/dark.
  - The home-screen widget takes RICE colors on Android 12 and newer.
  - Import palette colors from public GitHub Linux theme projects: pywal, Kitty, Base16/Base24 and plain hex lists.
    Nothing is applied before you save it.
  - Details: `docs/rice-customization.md`.
- **Sign-in renewal:** when several requests find the sign-in expired at once, the app renews it once and retries
  each of them. Overlapping renewals could use the same single-use refresh token and sign you out.
- **Fewer unexpected sign-outs:**
  - being offline or a server error while renewing no longer signs you out;
  - neither does a request that fails for another reason after renewing.
  You're signed out only when the server refuses the sign-in.
- **No crossed sessions:** a renewal still running when you sign out or switch server or account can't change the new
  session or the saved sign-in. A sign-in renewed at startup is saved. Details: `docs/authentication.md`.

### Known limitations

- **Linux theme import:** only the four formats above, from public GitHub repositories. It doesn't read GTK, Rofi,
  Polybar or other config files, other hosts, or images.
- **Image palettes:** making a palette from an image or wallpaper isn't included.
- **Samsung themes:** RICE follows the system color palette that One UI sets. It can't import Samsung Galaxy Themes or
  Theme Park themes.
- **Not themed:** the media notification, launcher icon and Android's own dialogs keep their standard look, and a few
  older colors (such as the yellow progress bar on book covers) stay the same in every appearance.
- **Progress from another device:** while an audiobook is playing, progress updates from another device are not
  applied, and the next progress save from this device may overwrite those updates. When you return to a paused
  audiobook, progress changes from another device are recognized and applied.

## v0.17.0 — In-app GitHub updates

**Released 2026-10-11:** versionCode 133, versionName `0.14.2-beta` (upstream base unchanged), tag
`v0.14.2-beta-plus-v0.17.0` at `a9084e19`. [Release](https://github.com/ADD-OCD/audiobookshelfplus-app/releases/tag/v0.14.2-beta-plus-v0.17.0). Changes since v0.16.0 (`10db9077`).

- **In-app updates** (Settings → Updates): check the official GitHub releases, download the latest stable release with
  progress, Cancel and Retry, and install it through Android's installer after verification.
  - The download must match its published size and digests.
  - It must be the Audiobookshelf+ package, newer than the installed version code, not debuggable, and signed with the
    Audiobookshelf+ release key.
  - Nothing downloads or installs on its own. Android asks you to confirm every installation.
  - Installing needs Android's "Install unknown apps" permission. The app asks for it only when you tap Install.
  - **Open installer** shows Android's confirmation again if you left it.
  - For the GitHub-distributed app; a copy installed from Google Play leaves updates to the store.
  - Details: `docs/in-app-updates.md`.
- **Release consistency:** an automated check keeps the app's public version (`PLUS_VERSION`) in step with its
  `versionCode` and the release ledger.

### Known limitations

- **Production self-update not yet verified:** the in-app updater has been tested with signed test applications.
  Updating an existing production Audiobookshelf+ installation through the updater will be verified with a subsequent
  release.
- **Progress from another device:** while an audiobook is playing, progress updates from another device are not
  applied, and the next progress save from this device may overwrite those updates. When you return to a paused
  audiobook, progress changes from another device are recognized and applied.

## v0.16.0 — LLAMA visual refresh and stability fixes

**Released 2026-10-11:** versionCode 132, versionName `0.14.2-beta` (upstream base unchanged), tag
`v0.14.2-beta-plus-v0.16.0` at `10db9077`. [Release](https://github.com/ADD-OCD/audiobookshelfplus-app/releases/tag/v0.14.2-beta-plus-v0.16.0) ·
[Announcement](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions/21). Changes since v0.15.1 (`d2795e4c`).

### LLAMA theme

LLAMA only; Black, Dark and Light are unchanged.

- Brushed-steel panels, keys and cover frames, green readouts and amber key legends on a near-black page.
- Steel home screen widget with socketed keys, at every size.
- Dialogs are black panels in a steel frame; primary actions are steel keys with amber labels.
- Play controls in series, lists, collections, playlists, podcast episodes and downloaded books use the amber legend.
- Home shelf cover frames are no longer cut off at the top.
- Slimmer collapsed player (92px) without the empty strip under its progress bar; pages, downloaded-media screens and
  reader toolbars follow its height.

### Fixes in every theme

- Bookmark button available offline; saved bookmarks can be viewed offline.
- A paused book shows its real position when you return to the app:
  - downloaded books no longer show an older saved position;
  - streamed books moved from the notification, widget or a headset while paused no longer jump back;
  - progress saved on another device after this phone's last save is applied, however soon after that save it came
    (the phone recognises its own saves by what the server confirmed, not by timing).
- Large text: grid titles sit below covers, long series names wrap without covering the navigation, and the
  downloaded-book and folder lists end at the player.
- PDF reader page counter visible again; comic page bar stays above its loading overlay.
- Swiping up just above the collapsed player no longer opens the full player.

### Other changes since v0.15.1

- Headset Play/call and Bluetooth Play reliably restore a paused book after Android closed the player.
- A downloaded book with a missing or damaged cover image keeps playing instead of making the app close.
- Sign-in tokens, server details and the username no longer reach Android's system log.

### Known limitations

See `docs/future-work.md`:
- **Progress from another device:** while an audiobook is playing, progress updates from another device are not
  applied, and the next progress save from this device may overwrite those updates. When you return to a paused
  audiobook, progress changes from another device are recognized and applied.
- Not tested on a device: comic CBR, compressed or KF8/AZW3 MOBI files, and real podcast feeds.
