---
name: emulator-check
description: Verify an Audiobookshelf+ change in the real app on a local Android emulator against a local Audiobookshelf server with fixture books — build and install the debug APK, log in, drive the WebView UI over Chrome DevTools Protocol and native UI over adb, take screenshots. Use after a change to UI, playback, queue, widget, notification or other runtime behaviour, and when asked to "check on the emulator", "verify the feature", "run the app", "проверь на эмуляторе", "покажи, как это выглядит". Not a replacement for `node --test tests/*.test.mjs` and `./gradlew testDebugUnitTest`.
---

# Checking a change on the emulator

The setup is three pieces, each with a script in `scripts/` (paths below are relative to this skill's directory):

- `abs.sh` — an Audiobookshelf server in Docker (container `absplus-emulator-abs`, `http://127.0.0.1:13378`, user `root` / `root`) with a generated fixture library. Data lives in `~/.local/state/absplus-emulator/`, outside the repo.
- `emu.sh` — a headless emulator, AVD `absplus-api35` (Pixel 6, API 35, Google APIs, arm64). Created on first run, along with the SDK packages.
- `app.sh` — builds the debug APK of the worktree the skill is in, installs it (`app.absplus.android.debug`) and drives it.

Run these scripts with the Bash sandbox disabled: they need Docker, adb and localhost ports. They also need Node 22+, because `cdp.mjs` uses the global `WebSocket`.

`app.sh` sends adb commands only to the emulator of this AVD, through `ANDROID_SERIAL`. A connected phone or another task's emulator is never touched.

## Procedure

1. Bring everything up. Each script is idempotent, and up to this point a run is fast:

   ```bash
   scripts/abs.sh up
   scripts/emu.sh up            # add --window to watch it on screen
   scripts/app.sh deploy        # npm generate + cap sync + assembleDebug + install + launch
   scripts/app.sh login         # only on a fresh install or after `app.sh clear`
   ```

   After Kotlin-only changes, `scripts/app.sh deploy --native-only` skips the web build. `app.sh install` keeps the app's data, so a deploy usually leaves you logged in.

2. Go through the feature as a user would. Write the scenario out first ("open Example Series, play Vol 1, wait for the end — Vol 2 starts"), then run it step by step and check every expected result. Don't stop at "the screen opened".

3. Write the report: the scenario, what you observed at each step, the screenshots, and what you could not check on the emulator (see "Limits"). If the user should look at the screenshots, copy them to `~/Downloads`.

## Driving the app

**WebView (all Vue UI)** goes through `scripts/app.sh cdp …`. It talks to the WebView over the Chrome DevTools Protocol, so it reads the real DOM and Vuex state, not pixels:

```bash
scripts/app.sh cdp text                                   # visible page text
scripts/app.sh cdp click-text 'Chaptered Book'            # clicks the deepest visible element with that text
scripts/app.sh cdp click 'button[type=submit]'
scripts/app.sh cdp fill 'input[type=password]' 'secret'   # fires input/change, so v-model updates
scripts/app.sh cdp wait-text 'Chapters' 20
scripts/app.sh cdp wait-for '.some-selector' 20
scripts/app.sh cdp eval '$nuxt.$store.state.playbackQueue'   # any expression; promises are awaited
scripts/app.sh cdp eval "\$nuxt.\$router.push('/bookshelf/series'), undefined"
```

Useful state: `$nuxt.$store.state.playerIsPlaying`, `.currentPlaybackSession` (`displayTitle`, `currentTime`), `.playbackQueue`, `$nuxt.$store.state.user.user`. Icons are Material Symbols ligatures, so button text includes words like `play_arrow`; `click-text 'Stream'` still works because it falls back to substring matching.

**Screenshots** for visual checks: `scripts/app.sh screenshot /tmp/absplus-check/<step>.png`, then Read the PNG. The screen is 1080×2400 px.

**Native UI** (notification, widget, system dialogs, Android Auto browser) isn't in the WebView:

```bash
scripts/app.sh adb shell uiautomator dump /sdcard/ui.xml && scripts/app.sh adb exec-out cat /sdcard/ui.xml   # bounds are in device px
scripts/app.sh adb shell input tap 540 1200
scripts/app.sh adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE    # also KEYCODE_BACK, KEYCODE_HOME
scripts/app.sh adb shell cmd statusbar expand-notifications
scripts/app.sh adb shell dumpsys media_session | grep -E 'state=|description='   # native player state and position
scripts/app.sh adb logcat -d --pid=$(scripts/app.sh adb shell pidof app.absplus.android.debug) | tail -200
```

**Server side** — the ABS REST API is at `http://127.0.0.1:13378` (log in via `POST /login` with header `x-return-tokens: true`, then `Authorization: Bearer <accessToken>`). Use it to check synced progress or prepare state.

## Fixture library ("Emulator Books")

| Book | Author | What it covers |
|---|---|---|
| Chaptered Book | Ada Tester | single m4b, 6 min, 3 chapters of 2 min, cover |
| Series Opener | Bob Example | Example Series #1, three 1-minute mp3 tracks, cover |
| Series Sequel | Bob Example | Example Series #2, one 90 s mp3, no cover |

The audio is silent, so playback makes no sound while the position still advances. The short durations let you check "book finished → next item" in a couple of minutes. Seek near the end instead of waiting.

## Reset and teardown

- `scripts/app.sh clear` — wipe app data (logged out, no downloads), then `app.sh launch` and `app.sh login`.
- `scripts/abs.sh reset` — delete server data and recreate it (progress, playlists, collections).
- `scripts/emu.sh down`, `scripts/abs.sh down` — stop both. Leave them running if more checks follow.

## Limits and gotchas

- **What the emulator can't show:** Samsung/One UI launcher behaviour, OEM battery restrictions, real Doze, Bluetooth, Cast and Android Auto in a car. Name these in the report as unchecked if the change touches them.
- **One emulator and one app id serve every worktree.** Two agents deploying at the same time overwrite each other's APK. Don't check in parallel.
- **The server is reached as `http://127.0.0.1:13378` through `adb reverse`** (set on every `app.sh launch`). On this machine, the emulator's usual `10.0.2.2` host alias refused TCP connections.
- **The ABS port is published on loopback only**, because the admin password is well known. If Docker runs in colima and colima fails to publish the port, `abs.sh up` opens an SSH tunnel into the colima VM itself.
- **The build runs on JDK 21–23**: Capacitor compiles for Java 21, and Gradle 8.11 runs on JDK 23 at most. `app.sh` chooses one via `/usr/libexec/java_home`.
- **`app.sh login` targets `/connect`.** If it reports that the form isn't shown, you are already logged in.
