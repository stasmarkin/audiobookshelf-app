# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Audiobookshelf+ is an Android app built with **NuxtJS 2** (SSR disabled, static target) and **Capacitor 7**. The codebase inherited from upstream is cross-platform (one JS app inside Android and iOS shells), but Audiobookshelf+ ships, builds and tests Android only. Requires a running Audiobookshelf server to connect to.

This repo (`plus` branch) is an unofficial fork that is **Android-only** — the iOS project is not maintained here. Don't spend effort on iOS builds, native iOS code, or iOS-specific testing unless explicitly asked.

Branding: production-facing Audiobookshelf+ material (app UI, widgets and their empty states, screenshots, mockups and documentation imagery of the finished UI) must never contain Audible branding — no Audible logo, name or prompts. Empty states say Audiobookshelf+. An Audible screenshot may exist only as an explicitly labeled INTERNAL DESIGN REFERENCE. See `docs/app-identity.md`.

## Development Commands

```bash
npm install                  # install dependencies
npm run generate             # build static web app into dist/
npx cap sync                 # copy dist/ into the native shells (Android; the inherited iOS shell is updated too)
npm run sync                 # shortcut: generate + cap sync (use after JS changes)
npm run dev                  # local web dev server at http://0.0.0.0:1337
npx cap open android         # open Android Studio
npx cap open ios             # inherited from upstream; iOS is not built or supported in this fork
npm run devlive              # live reload via ionic cap run on connected device
```

Automated tests (both also run in the Build APK CI workflow):

```bash
node --test tests/*.test.mjs         # JS suite (theme, widget theme, playback queue); needs npm install, not generate
cd android && ./gradlew testDebugUnitTest   # Kotlin JVM + Robolectric unit tests (diagnostics, restore store, widget)
```

Use the glob form for the JS suite: `node --test tests/` fails on Node 22+.

## Test devices

Use the first level that is sufficient:

1. **No device:** JS tests, Kotlin/Robolectric unit tests, lint, static checks and Android builds. None of them needs, or may start, an emulator.
2. **Samsung Galaxy S22 Ultra test bench** (`R5CT60CSGEF`, SM-S908U1, Android 16 / One UI 8): all runtime testing (UI, screenshots, widgets, playback/MediaSession, notifications and lock screen, downloads/offline, lifecycle/background, device regressions).
   - Choose it with `dev-tools/android-test/target-device.sh`, which checks the serial, authorization and model. Then always use `adb -s <serial>`, and never send commands to an unidentified device.
   - Wrap scripted sessions in `with-s22-session.sh` (it runs `test-session.sh start`/`end`, with `end` guaranteed).
   - If the S22 is unavailable, report it. Don't substitute an emulator.
3. **Emulator** (`absplus_test`, Android 15), only for:

   - another Android version or screen configuration;
   - a clean isolated environment;
   - an emulator-specific problem;
   - an explicit user request.

   Start it only with `~/.android/emu-tools/start-emulator.sh`:

   - headless by default; `--window` for visual work, minimized without taking focus as soon as it appears;
   - Below Normal priority for its own processes only;
   - one instance only, and not during busy builds;
   - it never changes the home screen, apps or data. Home-screen cleanup is a separate opt-in command that is disabled until validated.

   Reuse it across tests rather than restarting it. Stop it with `stop-emulator.sh`, which stops only the emulator this session started and verifies cleanup.

The Galaxy S26 Ultra is the user's final acceptance device: never send ADB commands to it. Machine-specific tooling and its README live outside the repository in `C:\Users\Ben\AI\CLAUDE\dev-tools\android-test\`.

## Architecture

### Two-layer structure

**JS Layer (NuxtJS)** → compiled to `dist/` → synced into native shells via Capacitor.

**Native Layer (Android/Kotlin)** in `android/app/src/main/java/app/absplus/android/` (package and application ID `app.absplus.android`; debug builds add `.debug`).

### Custom Capacitor Plugins

The bridge between JS and native is custom Capacitor plugins. Each has a JS counterpart in `plugins/capacitor/` and a Kotlin implementation in `android/.../plugins/`:

| Plugin | Purpose |
|---|---|
| `AbsAudioPlayer` | Audio playback control, cast, sleep timer |
| `AbsDatabase` | Local SQLite DB, device data, download progress |
| `AbsDownloader` | File download management |
| `AbsFileSystem` | File system access, folder scanning |
| `AbsLogger` | Native log capture |
| `AbsUpdater` | Settings → Updates: GitHub release check, verified download, Android installer (`updater/`, docs/in-app-updates.md) |
| `AbsAppearance` | RICE: device light/dark, Android 12+ system palette, contrast; app-owned background (`appearance/`, docs/rice-customization.md) |
| `AbsThemeImport` | RICE: bounded GitHub palette-file import (`themeimport/`); start/readCandidate/cancel only |
| `AbsData` | Offline-first data: active server reachability (`unknown`/`online`/`offline`/`unauthorized`, `sync/ServerReachability.kt`), request/socket reports from JS, manual retry; used through `$absData` (`utils/dataService.js`) |

**JS → Native**: Call plugin methods directly, e.g. `AbsAudioPlayer.prepareLibraryItem({...})`.
**Native → JS**: Use Capacitor's `notifyListeners()` on the native side and `addListener()` on the JS side.

### Android Native Key Files

- `MainActivity.kt` — `BridgeActivity` subclass; registers all plugins; binds `PlayerNotificationService` on `onPostCreate` and wires it to `AbsAudioPlayer` via `pluginCallback`.
- `player/PlayerNotificationService.kt` — `MediaBrowserServiceCompat` foreground service; owns ExoPlayer instance; implements `ClientEventEmitter` interface to push events back to `AbsAudioPlayer.kt`.
- `player/CastManager.kt` / `CastPlayer.kt` — Google Cast support.
- `media/MediaManager.kt` — Caches library items, authors, series, collections, and podcast data for Android Auto / media browser.
- `media/MediaProgressSyncer.kt` — Syncs playback progress with the server.
- `managers/DbManager.kt` — SQLite wrapper for local library items and progress.
- `managers/SleepTimerManager.kt` — Sleep timer logic.
- `server/ApiHandler.kt` — All REST API calls to the ABS server.

### JS Layer Key Files

- `plugins/server.js` — `ServerSocket` class (socket.io); injected as `$socket`; handles authentication and real-time events from server.
- `plugins/db.js` — `DbService`; thin wrapper around `AbsDatabase`; injected as `$db`; also listens for `onTokenRefresh` / `onTokenRefreshFailure` events.
- `plugins/localStore.js` — `LocalStorage` class backed by Capacitor `Preferences`; injected as `$localStore`; persists user settings, theme, language, last library.
- `plugins/theme.client.js` + `theme/` — semantic presentation-theme tokens, built-in themes as validated data, and the `$theme` service; see `docs/theme-architecture.md`. Themes are presentation-only.
- `plugins/init.client.js` — Registers global Vue utilities (`$eventBus`, date/time helpers, `$bytesPretty`, `$elapsedPretty`, etc.); handles back-button behavior for Android and iOS swipe navigation.
- `plugins/capacitor/AbsAudioPlayer.js` — Web fallback implementation of `AbsAudioPlayer` using the HTML5 `<audio>` element; used during browser-based development.

### Vuex Store

- `store/index.js` — Global state: network status, current playback session, player playing/fullscreen state, cast availability, playback queue (`playbackQueue`).
- `store/user.js` — Auth token, server connection config, user object (permissions, media progress, bookmarks), user settings.
- `store/libraries.js` — Library list, current library ID, filter data.
- `store/globals.js` — Modal open state and other UI globals.

### Playlist Queue

Playlist playback uses a two-layer approach so advancement works even when the screen is off or Android's Doze mode throttles the WebView's JS engine:

**Native layer** (`PlayerNotificationService.kt`): Holds `playlistQueue: List<PlaylistQueueItem>` and `playlistQueueIndex`. When ExoPlayer fires `STATE_ENDED`, `PlayerListener` calls `advancePlaylistQueue()` directly on the service. This calls `preparePlayer()` for the next item (local: from DB; server: via `apiHandler.playLibraryItem()`) without touching the JS layer at all.

**JS layer** (`AudioPlayerContainer.vue` + `utils/playbackQueue.js`): The queue is the central Vuex `playbackQueue` (`{ sourceType, sourceId, items, currentIndex }`). Pages do not set it up themselves (`pages/playlist/_id.vue` no longer calls `setPlaylistQueue`); they emit `play-item` with a queue source, and `AudioPlayerContainer` resolves the queue for playlists, collections and series (`resolvePlaybackQueue`), syncs it with `AbsAudioPlayer.setPlaylistQueue(items, currentIndex)`, then starts playback. A request without a source clears the queue (`clearPlaylistQueue()`). When `onPlaybackEnded` fires in JS, it reads the native queue (`AbsAudioPlayer.getPlaylistQueue()`) and re-syncs the Vuex `currentIndex` from it — it does NOT trigger playback. Up Next edits go through `mutateQueue`, which resyncs instead of writing if native already advanced. See `docs/continuous-playback.md`.

**`AbsAudioPlayer.kt` background suppression**: `onMetadata` events are suppressed while backgrounded (`isInForeground == false`) except for `ENDED` state. Do not remove this exemption — it allows the JS Vuex state to sync when the app returns to foreground after an episode ended.

### Communication Patterns

- **Server REST API**: `$axios` (configured in `plugins/axios.js`); base URL is the connected server address.
- **Real-time updates**: socket.io via `$socket`; socket must be authenticated with `auth` event after connection.
- **Expired access token (401)**: `$nativeHttp`, `$axios` and native `ApiHandler` share one renewal per saved server (`$authRenewal` → `AbsDatabase.refreshAccessToken` → `server/TokenRefresher.kt`); never refresh directly. See `docs/authentication.md`.
- **Playback events**: flow from `PlayerNotificationService` → `AbsAudioPlayer.kt` (via `ClientEventEmitter`) → `notifyListeners()` → JS `addListener()` handlers → Vuex mutations.

### i18n

Translation strings live in `strings/<locale>.json`. Applied via `plugins/i18n.js`. Language preference is persisted with `$localStore.setLanguage()`.

### Styling

Tailwind CSS 3 with PostCSS. Global styles in `assets/app.css` and `assets/tailwind.css`. The `tailwind.config.js` controls customization.
