# Changelog

Audiobookshelf+ changes by release. Earlier releases, their `versionCode`s and dates are recorded in
`docs/release-process.md`, and the feature list in `readme.md` describes what the app contains.

## Unreleased

Merged into `plus`, not yet in a published release.

- **Android Auto without a reachable server** (issue #33, Discussion #30): the Android Auto menu now opens at once
  with Downloads, instead of waiting for the server checks. With a server that was unreachable or stalled, the menu used
  to appear only after the connection attempts gave up (about 3 s per saved server, or about 30 s when the server
  answered its ping but then stalled). Downloaded books and podcast episodes can be opened and played right away.
  Continue, Recent and Libraries are added as soon as the server has answered. Fully offline it was already immediate.
- **Long jumps** (Settings → Long jump backwards / forwards time): an optional second jump in each direction, from 15
  seconds to 30 minutes, Off by default. When on, the full player shows it outside the short jump. The notification,
  headset, widget and Android Auto keep the short jumps. For everyone, the full player's jump keys are now chevrons
  centered on Play (the short jumps used circular arrows); the collapsed player keeps the arrows.

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
