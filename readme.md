# Audiobookshelf+ (unofficial Android app)

**Audiobookshelf+** is an unofficial Android fork of the [Audiobookshelf](https://audiobookshelf.org) mobile app,
built and maintained independently of the official project. Audiobookshelf itself is a
self-hosted audiobook and podcast server.

This fork tracks [upstream `master`](https://github.com/advplyr/audiobookshelf-app) and is
periodically rebased onto each new official release, so it stays current with all official
fixes and features while adding the changes below on top.

**This fork is Android-only.** The iOS side is not maintained here — changes, builds, and
testing are focused entirely on Android.

**Not affiliated with or endorsed by the official Audiobookshelf project.** All credit for the
original app goes to [advplyr](https://github.com/advplyr) and the Audiobookshelf contributors.
This fork exists to carry a small set of additional fixes/features that aren't (yet, or won't
be) in the official app.

### Latest release

**[Audiobookshelf+ v0.19.0](https://github.com/ADD-OCD/audiobookshelfplus-app/releases/tag/v0.14.2-beta-plus-v0.19.0)** — Podcast auto downloads, SD card storage, E-ink and Bluetooth controls
([download the APK](https://github.com/ADD-OCD/audiobookshelfplus-app/releases/download/v0.14.2-beta-plus-v0.19.0/audiobookshelf-plus-v0.19.0-release.apk)). [CHANGELOG.md](CHANGELOG.md) lists the changes.

New in v0.19.0:
- Automatic podcast downloads, with a managed or keep-all choice per podcast, Wi-Fi and charging limits.
- Downloads on a removable SD card (Settings → Download storage), with verified, restart-safe moves.
- Interrupted downloads continue where they stopped; the Downloads screen says why a download waits.
- Bluetooth / external media controls: Next and Previous skip by time, chapters or audio files (Settings → Playback).
- E-ink theme, Android reduced-motion support and an optional once-a-minute player.
- Readable sort arrows and check marks on light themes; security and dependency updates.
- After installing v0.19.0, do not go back to an earlier version (see the changelog).

New in v0.18.2:
- LLAMA full player in landscape: the cover's frame fits the cover, sits on the bottom edge and stays below the top
  buttons.

New in v0.18.1:
- Up Next shows your queue again after resuming a book once Android has closed the app (for example after a long
  break or an update).

New in v0.18.0:
- Optional RICE customization (Settings → Appearance): ten starter palettes, your own color presets, the phone's
  system colors (including Samsung One UI), and palette colors from GitHub Linux theme projects.
- More reliable sign-in renewal, with fewer unexpected sign-outs.

New in v0.17.0:
- In-app GitHub update checks, verified APK downloads, and Android-confirmed installation (Settings → Updates).

New in v0.16.0 ([announcement](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions/21)):
- Refreshed LLAMA theme and widgets.
- Fixed playback position when returning to the app, including progress from another device.
- Improved headset and Bluetooth playback controls.
- Fixed bookmark access while offline.
- Improved layouts with larger text.
- Fixed crashes caused by damaged downloaded cover images.
- Prevented sign-in details from appearing in system logs.

### What's different from official

**Playback and queue**
- Continuous playback through series and collections, preferring downloaded books and streaming the rest.
- Optional prompt before playing an incomplete series, collection or playlist, with the choice to download what's
  missing.
- A visible, reorderable Up Next queue.
- Optional long jumps (15 seconds to 30 minutes, set separately back and forward) beside the short jumps in the full
  player.
- Podcasts auto-advance to the next unfinished episode, from playlists and from the podcast page.
- More reliable background advancement (including under Android Doze), and playback pauses when Bluetooth disconnects.
- Playback restoration: a paused book resumes from the widget, notification or a headset after Android closed the
  player, and reopening the app reconnects to what's playing. (Android's *Force stop* is still a hard stop.)
- Fixed progress being saved to the wrong book during continuous playback.
- Fixed offline listening progress being overwritten when reconnecting.
- Fixed playlist items restarting from 0:00 when their progress was only on the server.
- Choose what Bluetooth, car, watch and lock-screen Next/Previous do: skip by time, chapters or audio files.
- Per-podcast episode order, and an optional once-a-minute player for e-paper screens.

**Downloads and local files**
- Download controls: each download's state in words, with Retry, Cancel and Clear.
- Automatic podcast downloads with Wi-Fi/charging limits and a managed or keep-all choice per podcast.
- Downloads on a removable SD card, moved and verified file by file.
- Interrupted downloads continue where they stopped after Android closes the app.
- A downloaded book shows as downloaded in every library it appears in.
- Rescan Folder links books already on the phone to the right library, without downloading them again.
- "Download Series" downloads in series order.
- Fixed downloads stalling after the first book of a series, or after a folder's permission was lost.
- Fixed downloads to device folders and the Local Folders permission check.

**Appearance**
- Optional LLAMA theme, a late-1990s audio-equipment look (Settings → Theme), including the home screen widget.
- Optional RICE customization (Settings → Appearance): starter palettes, personal presets, system colors and GitHub
  palette imports, including the home screen widget.
- E-ink theme for e-paper screens, and the app follows Android's "Remove animations" setting.
- Bigger, clearer home screen widget buttons.
- Keyboard-friendly connection and login screens.
- Its own name and icon, so it installs alongside the official app.

**Reliability and privacy**
- Sign-in renews once when it expires, without unexpected sign-outs when offline or switching accounts.
- Library lists reload rows that failed to load, and series show all of their books.
- Series progress includes books downloaded to the phone.
- In-app diagnostics log (Settings → Diagnostics) to view, save or share, with passwords, tokens and server addresses
  removed. Please look over a log before sharing it publicly.
- Many smaller playback and download fixes from a full stability audit.

**Known limitations:** While an audiobook is playing, progress updates from another device are not applied. The next
progress save from this device may overwrite those updates. However, when you return to a paused audiobook, progress
changes from another device are recognized and applied. See [CHANGELOG.md](https://github.com/ADD-OCD/audiobookshelfplus-app/blob/plus/CHANGELOG.md) for other known limitations.

### Getting builds

This fork does not publish to the Play Store or TestFlight. Installable APKs are published on
this repo's [Releases](https://github.com/ADD-OCD/audiobookshelfplus-app/releases) page — always
the release package (`app.absplus.android`), signed with the permanent Audiobookshelf+ key, so
updates install over each other. Debug builds (`app.absplus.android.debug`) are for local
development only and are not published. You can also build it yourself with the instructions
below.

---

This fork: [github.com/ADD-OCD/audiobookshelfplus-app](https://github.com/ADD-OCD/audiobookshelfplus-app)

[Official project repo: github.com/advplyr/audiobookshelf](https://github.com/advplyr/audiobookshelf) or the project site [audiobookshelf.org](https://audiobookshelf.org)

For Audiobookshelf+ questions and support, use this repository's [Discussions](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions).
Report bugs and request features in [Issues](https://github.com/ADD-OCD/audiobookshelfplus-app/issues), and report
security vulnerabilities privately as described in [SECURITY.md](SECURITY.md). Audiobookshelf+ has no Discord server.

**Requires an Audiobookshelf server to connect with**

## Contributing

The app is built with [NuxtJS](https://nuxtjs.org/) and [Capacitor](https://capacitorjs.com/). The
inherited codebase is cross-platform (one web app inside Android and iOS shells), but
**Audiobookshelf+ supports and tests Android only**. The iOS project is kept as inherited from
upstream and is not maintained or supported here.

### Localization

Translations come from the official Audiobookshelf project, whose translations are hosted on
[Weblate](https://hosted.weblate.org/engage/audiobookshelf/) (see
[how to help](https://www.audiobookshelf.org/faq#how-do-i-help-with-translations)). Strings added
by Audiobookshelf+ are maintained in this repository and fall back to English.

### Windows Environment Setup for Android

Required Software:

- [Git](https://git-scm.com/downloads)
- [Node.js](https://nodejs.org/en/) (version 20)
- Code editor of choice([VSCode](https://code.visualstudio.com/download), etc)
- [Android Studio](https://developer.android.com/studio)
- [Android SDK](https://developer.android.com/studio)

<details>
<summary>Install the required software with <a href=(https://docs.microsoft.com/en-us/windows/package-manager/winget/#production-recommended)>winget</a></summary>

<p>
Note: This requires a PowerShell prompt with winget installed.  You should be able to copy and paste the code block to install.  If you use an elevated PowerShell prompt, UAC will not pop up during the installs.

```PowerShell
winget install -e --id Git.Git; `
winget install -e --id Microsoft.VisualStudioCode; `
winget install -e --id  Google.AndroidStudio; `
winget install -e --id OpenJS.NodeJS --version 20.11.0;
```

![](/screenshots/dev_setup_windows_winget.png)

</p>
</details>
<br>

Your Windows environment should now be set up and ready to proceed!

### Mac Environment Setup for Android

Required Software:

- [Android Studio](https://developer.android.com/studio)
- [Node.js](https://nodejs.org/en/) (version 20)
- [Android SDK](https://developer.android.com/studio)

<details>
<summary>Install the required software with <a href=(https://brew.sh/)>homebrew</a></summary>

<p>

```zsh
brew install android-studio node
```

</p>
</details>

### Start working on the Android app

Clone or fork the project from terminal or powershell and `cd` into the project directory.

Install the required node packages:

```shell
npm install
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_android_npm_install.png)

</details>
<br>

Generate static web app:

```shell
npm run generate
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_android_npm_run.png)

</details>
<br>

Copy the web app into the native Android project (`cap sync` also updates the inherited, unmaintained
iOS project; that is expected and needs no action):

```shell
npx cap sync
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_android_cap_sync.png)

</details>
<br>

Open Android Studio:

```shell
npx cap open android
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_cap_android.png)

</details>
<br>

Start coding!

After making changes to the JS layer you need to rebuild the nuxt pages and sync them to the native shells:

```shell
npm run sync
```

### Mac Environment Setup for iOS

> **Not supported by Audiobookshelf+.** The iOS instructions below are inherited from upstream and
> kept for reference only. This fork does not maintain, build, test or distribute the iOS app.

Required Software:

- [Xcode](https://developer.apple.com/xcode/)
- [Node.js](https://nodejs.org/en/)
- [Cocoapods](https://guides.cocoapods.org/using/getting-started.html#installation)

### Start working on the iOS app

Clone or fork the project in the terminal and `cd` into the project directory.

Install the required node packages:

```shell
npm install
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_npm_install.png)

</details>
<br>

Generate static web app:

```shell
npm run generate
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_npm_generate.png)

</details>
<br>

Copy web app into native android/ios folders:

```shell
npx cap sync
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_cap_sync.png)

</details>
<br>

Open Xcode:

```shell
npx cap open ios
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_cap_open.png)

</details>
<br>

Start coding!

After making changes to the JS layer you need to rebuild the nuxt pages and sync them to the native shells:

```shell
npm run sync
```
