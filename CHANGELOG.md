# Changelog

Audiobookshelf+ changes by release. Earlier releases, their `versionCode`s and dates are recorded in
`docs/release-process.md`, and the feature list in `readme.md` describes what the app contains.

## Unreleased

Merged into `plus`, not yet in a published release.

- **Catalog copy on the device (groundwork for browsing offline):** after each full sync with the server (on start,
  on reconnect, on Sync now) the app now also copies the catalog of every library (libraries, books, series and the
  filter lists) into a database on the phone, page by page in the background, and only rewrites what changed since
  the last copy. Nothing changes on screen yet: the tabs still read the server as before. The first copy of a large
  library takes a while and some mobile data; later ones only fetch the lists again.
- **Podcast library navigation:** every tab now shows both its icon and its label, selected or not: Home (house),
  Latest (clock), Library (stacked books) and Add (plus). Library no longer uses the microphone and Add no longer
  uses the broadcast symbol. Tabs keep equal widths, the labels name each destination for screen readers, and the
  layout adapts to portrait, landscape and larger text. Audiobook libraries are unchanged.
- **Large podcast download queues** (contributed by Stanislav Markin, PR #25): a queued episode no longer stores a
  full copy of its podcast, so queueing many episodes of a podcast with long show notes uses far less memory and
  storage. In testing with 100 queued episodes, the app stayed around 55 MB of memory where it previously ran out of
  memory at about 54. Queues saved by older versions are converted on the next start, and queued downloads continue
  after a restart.
- **Download reliability:** fixed a crash when the download list subscribed while downloads were starting or finishing,
  and made Retry and new download requests safe while other downloads change the queue.
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
