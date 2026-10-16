# Audiobookshelf+ release process

Covers how distributable APKs are signed, versioned, and published. See `docs/app-identity.md`
for why the fork has its own package IDs at all.

## Signing identity

All distributed `app.absplus.android` APKs must be signed with the permanent Audiobookshelf+
release key.

- **Certificate SHA-256 fingerprint**: `C5:40:04:6A:10:FE:8A:97:DD:75:AD:BA:1D:7D:60:E6:9D:69:6B:10:49:2F:F3:81:E1:3F:62:DF:70:2E:B9:D4`
- Key algorithm: RSA 4096-bit, SHA256withRSA, valid 30 years (until 2056).
- The keystore (`android/keystore/audiobookshelf-plus-release.jks`) and its credentials
  (`android/keystore.properties`) are **never committed to git** — both are gitignored. Anyone
  building a signed release needs their own local copy of both files, generated once and backed
  up securely (a password manager entry for the credentials, encrypted offline storage for the
  keystore file itself). **Losing the keystore file permanently breaks the ability to publish an
  update that existing installs can upgrade to** — there is no recovery path.
- `android/app/build.gradle`'s `signingConfigs.release` reads from `keystore.properties` when
  present and falls back to an unsigned release build when it's absent, so a normal clone without
  the secret still builds (just can't produce an installable signed release).
- Do not record the store/key passwords or any private key material anywhere in this repository,
  in commit messages, in issues, or in chat/AI tool output. The fingerprint above is public and
  safe to share; the keystore and its passwords are not.

## What gets published where

**Debug builds (`app.absplus.android.debug`) are never uploaded to GitHub Releases** — not as a
normal release, not as a prerelease, not as a stabilization/RC/device-test build, not as a
feature-test build — unless explicitly authorized as an exceptional case. Every APK attached to a
GitHub release, regardless of how temporary or test-oriented it is, must:

1. Use the release package ID, `app.absplus.android`.
2. Be signed with the permanent Audiobookshelf+ release key above.

Debug builds may still be built and installed locally for day-to-day development and diagnostics
— they're just not what gets handed to anyone for testing. If a debug build is ever genuinely
needed for a specific diagnostic purpose, it must be explicitly requested; it is not the default.

## versionCode convention for test/RC builds

Because every distributed build now shares one package ID and one signing identity, they all
compete for the same `versionCode` space — Android will refuse to install a build whose
`versionCode` isn't strictly greater than what's already installed, and Google Play permanently
rejects re-uploading a `versionCode` that's ever been used. There is no separate "test" numbering
track to fall back on.

**Convention: one monotonically-increasing integer, shared by every distributed build, numbered
release or temporary test/RC build alike. Never reuse, never skip backward, never jump ahead
arbitrarily — always exactly `last distributed versionCode + 1`.**

The last distributed value is recorded here and must be updated immediately after any build is
published (release, prerelease, or test build):

| versionCode | What it was |
| --- | --- |
| 123 | Audiobookshelf+ v0.14.0 (current stable baseline) |
| 124 | Keyboard/login release-package device-test build from `fix/keyboard-aware-login` (commit `b66f9d96`) — distributed; **FAILED** physical-device testing: the keyboard layout was not scrollable, so lower fields/buttons couldn't be reached. Superseded (its commit is only history within the approved 126 merge). |
| 125 | Keyboard/login release-package device-test build from `fix/keyboard-aware-login` (commit `1596c0f3`) — distributed; keyboard/form behavior corrected, but **REJECTED** because the project/footer links could overlap the Important notice with the keyboard open. Superseded by 126. |
| 126 | Keyboard/login release-package device-test build from `fix/keyboard-aware-login` (commit `fe30cbac`) — distributed; **PASSED** physical-device testing (Samsung Galaxy S26 Ultra): keyboard-aware Server Address and Login layouts approved, project/footer layout approved, both external project links (Official Audiobookshelf, Audiobookshelf+) verified on the device. Approved and merged into `plus`. |
| 127 | Playback/widget restoration release-package device-test build from `fix/playback-widget-restoration` (app code as of `ae0ffa16`; built from the commit that set `versionCode 127`, tagged `playback-restore-device-test-v127`) — distributed as a temporary prerelease; superseded for physical-device testing by the combined 128 build. Not merged into `plus`. |
| 128 | Combined Playback Restoration + In-App Diagnostics release-package physical-device test build from `feature/in-app-diagnostic-logging` (contains all of `fix/playback-widget-restoration`; app code as of `b9097ada`; built from the commit that set `versionCode 128`, tagged `playback-diagnostics-device-test-v128`) — distributed as a temporary prerelease; physical-device tested (Samsung Galaxy S26 Ultra): playback/session restoration **PASSED**, but its diagnostic export revealed privacy/sanitization gaps (serialized library items, file paths/URIs, encoded server connection ids). Superseded by 129. Not merged into `plus`. |
| 129 | Playback & Diagnostics release-package physical-device test build from `feature/in-app-diagnostic-logging` (app code as of `e1e8704b`; built from the commit that set `versionCode 129`, tagged `playback-diagnostics-device-test-v129`) — adds diagnostics privacy/sanitization fixes, fail-closed logging, old-log rescrubbing, interrupted-write recovery, Log Actions UI and Save Log; distributed as a temporary prerelease; **PASSED** physical-device testing (Samsung Galaxy S26 Ultra, SM-S948U, Android 16), installed in place over 128: app started normally after the upgrade and existing diagnostic logs were re-sanitized once with the current rules; playing session survived swiping the UI away and reopening reattached to it; paused session survived swiping away and widget Play rebuilt and resumed it; downloaded book resumed from the widget offline (Airplane Mode) after a paused swipe; position, playback speed and queue/session restored correctly; Diagnostics View, Mark, Save (Android system file picker; saved TXT retrieved and inspected), Share and Clear all worked; the v129 export confirmed versionCode 129, release package `app.absplus.android`, DEBUG diagnostics and the successful one-time re-sanitization; no regression requiring another test build. Approved and merged into `plus`. |
| 130 | **Audiobookshelf+ v0.15.0** (upstream v0.14.2-beta), versionName `0.14.2-beta`: the normal release, built from `plus` at `b6f139c0` and tagged `v0.14.2-beta-plus-v0.15.0`. It is published as the Latest GitHub release with the asset `audiobookshelf-plus-v0.15.0-release.apk` (SHA-256 `7b1ad3e91ab6e0083308aa6347d0ec7222ce55fbeb2867ce986e0c52619d4519`), and announced in Discussions #16. It includes playback restoration and diagnostics (129), the LLAMA theme and widget layouts, download Retry/Cancel/Clear, the device-folder access fixes, Rescan Folder across libraries and library page retry. Physical-device release validation was pending at publication. |
| 131 | **Audiobookshelf+ v0.15.1** (upstream v0.14.2-beta), versionName `0.14.2-beta`: hotfix release, built from `plus` at `d2795e4c` and tagged `v0.14.2-beta-plus-v0.15.1`. It is published as the Latest GitHub release with the asset `audiobookshelf-plus-v0.15.1-release.apk` (SHA-256 `1c391c9367e4e9f6d5204d6d08924b4d27633e8c789dfda5e2cff80a9e02db1d`), and announced in Discussions #17. It fixes the series bookshelf that showed only its first one or two books (`defb3cf8`; physical S26 debug validation passed before release). |
| 132 | **Audiobookshelf+ v0.16.0** (upstream v0.14.2-beta), versionName `0.14.2-beta`: "LLAMA visual refresh and stability fixes", built from `plus` at `10db9077` and tagged `v0.14.2-beta-plus-v0.16.0`. Published 2026-10-11 as the Latest GitHub release with the assets `audiobookshelf-plus-v0.16.0-release.apk` (SHA-256 `5db2ed02ecabbd3a119094e12cb2b944aa97981d83b0bda77adcd35886dde5a8`) and its `.sha256` checksum file, and announced in Discussions #21. Physical S22 release validation passed, including an in-place update from v0.15.1. An earlier candidate built from `6505177b` was superseded before publication and never distributed. |
| 133 | **Audiobookshelf+ v0.17.0** (upstream v0.14.2-beta), versionName `0.14.2-beta`, `PLUS_VERSION` `0.17.0`: "In-app GitHub updates", built from `plus` at `a9084e19` and tagged `v0.14.2-beta-plus-v0.17.0`. Published 2026-10-11 as the Latest GitHub release with the assets `audiobookshelf-plus-v0.17.0-release.apk` (15,052,980 bytes, SHA-256 `e11b9bbc353e7fea30b1e32c94c556b4c3758ce8d0aa6ab1c887331528396415`, signed with the release certificate above) and its `.sha256` checksum file. CI on the build commit: Build APK 38195849700, i18n 38195849703 (both success). The S22 updated a signed v0.16.0 install in place, kept login, library and theme, streamed, and the new updater reported it up to date. **No Announcement was posted:** the announcement is deferred until a later release has been installed over a production v0.17.0 through the in-app updater. |

| 134 | **Audiobookshelf+ v0.18.0** (upstream v0.14.2-beta), versionName `0.14.2-beta`, `PLUS_VERSION` `0.18.0`: "RICE customization and sign-in reliability", built from `plus` at `aaa10605` (clean build) and tagged `v0.14.2-beta-plus-v0.18.0`. Published 2026-10-12 as the Latest GitHub release with the assets `audiobookshelf-plus-v0.18.0-release.apk` (15,158,210 bytes, SHA-256 `1a184aa743200dc9897f46d3fa44c24b812672ca4f6d896f23464a61954a7bca`, release certificate above) and its `.sha256` checksum file. CI on the build commit: Build APK 38276925050, i18n 38276925350 (both success). An earlier incremental build that still carried `PLUS_VERSION` 0.17.0 in the updater was rejected before publication. **Production self-update:** the user's own phone (Galaxy S26 Ultra) updated its production v0.17.0 to v0.18.0 through Settings → Updates on 2026-10-12 (its diagnostic log shows the verified download, Android's installer and the restart as versionCode 134). |
| 135 | **Audiobookshelf+ v0.18.1** (upstream v0.14.2-beta), versionName `0.14.2-beta`, `PLUS_VERSION` `0.18.1`: "Up Next restoration fix", a maintenance release built from `plus` at `76abcf09` (clean build; the fix merged at `88f94830` from `hotfix/queue-restoration-v0.18.1`) and tagged `v0.14.2-beta-plus-v0.18.1`. Published 2026-10-12 as the Latest GitHub release with the assets `audiobookshelf-plus-v0.18.1-release.apk` (15,158,938 bytes, SHA-256 `766d982a5befc59ea55cd270136d91779741a5774c61d59ca86988eb4b0f2eec`, release certificate above) and its `.sha256` checksum file. CI on the build commit: Build APK 38284011391, i18n 38284011378 (both success). The defect was reproduced and the fix verified on the S22 with a debug build before release. **Production self-update verified (S22):** the published v0.17.0 found v0.18.1 in Settings → Updates, downloaded and verified it, asked Android for "Install unknown apps" and Android's installer required the user's confirmation; after the user confirmed on 2026-10-12, the package was replaced in place as versionCode 135 (installer: the app itself; first-install time unchanged; installed APK byte-identical to the release asset, certificate above). Login, server, Dark theme, settings and the saved listening position were kept, Settings → Updates reports it up to date, and the next start renewed the expired sign-in once without signing out. Announced in [Discussion #26](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions/26) together with v0.18.0 and the deferred v0.17.0 updater announcement. |
| 136 | **Audiobookshelf+ v0.18.2** (upstream v0.14.2-beta), versionName `0.14.2-beta`, `PLUS_VERSION` `0.18.2`: "LLAMA landscape cover frame fix", a maintenance release built from `plus` at `ef757efd` (clean build; the fix merged at `2b88ca49` from `fix/llama-landscape-artwork-frame`) and tagged `v0.14.2-beta-plus-v0.18.2`. Published 2026-10-13 as the Latest GitHub release with the assets `audiobookshelf-plus-v0.18.2-release.apk` (15,159,634 bytes, SHA-256 `b99e202d0ef1652b196c42abe9120440104bbe09fc52884629706fc23a1c470f`, release certificate above) and its `.sha256` checksum file. CI on the build commit: i18n 38414067712 (success), Build APK 38414067883 (attempt 1 failed when Robolectric could not download its Android runtime jar; the re-run of the failed job passed). The S22 production v0.18.1 offers it in Settings → Updates. Acceptance on the user's S26 is pending. |
| 137 | **Audiobookshelf+ v0.19.0** (upstream v0.14.2-beta), versionName `0.14.2-beta`, `PLUS_VERSION` `0.19.0`: "Podcast auto downloads, SD card storage, E-ink and Bluetooth controls", built from `plus` at `5151703e` (clean build: fresh `npm ci`, `.nuxt`/`dist` removed, `gradlew clean`) and tagged `v0.14.2-beta-plus-v0.19.0`. Published 2026-10-15 (2026-10-16T03:03:34Z) as the Latest GitHub release with the assets `audiobookshelf-plus-v0.19.0-release.apk` (15,291,262 bytes, SHA-256 `7e90010718045616151ff10e0d5c67a078dd1f945f342b6dcde3f707615e0ad8`, release certificate above) and its `.sha256` checksum file. CI on the build commit: Build APK 38841586011, i18n 38841585959 (both success); Kotlin 510/510 (Linux container), JS 481/481, lint 0/161 debug and 0/154 release (baseline registers). Includes PRs #22–#25 and #29 (Stanislav Markin), Bluetooth / external media controls (Discussion #27) and the 2026-10-14/15 security and download work. Before publication the S22 updated production v0.18.1 to v0.18.2 through Settings → Updates, then installed this APK in place over v0.18.2 (same signer, first-install time unchanged; login, theme, settings and the downloaded book kept; the updater reported it up to date). After publication a freshly installed published v0.18.2 found v0.19.0 in Settings → Updates, downloaded and verified it and installed it through Android's confirmation (Google Play Protect showed "App scan recommended" for the new APK; installed without scanning). The app updated itself in place (installer: the app; installed APK byte-identical to the release asset), kept login, theme and the downloaded book, and reports itself up to date. **Downgrades are unsupported:** earlier versions cannot read the device data this version writes. Physical SD-card and S26 acceptance pending. |

**Next distributed versionCode: 138.** The temporary GitHub prereleases/tags for 124–126 were
deleted after approval; these ledger rows are the permanent record, and those numbers (and 127,
128, 129, 130, 131, 132, 133, 134, 135, 136, 137) must never be reused.

Before building anything intended for distribution — including a one-off test/RC build — bump
`versionCode` to the next integer above the value in this table, build, distribute, then update
the table. This is a plain sequential counter (no gaps reserved for releases vs. test builds), so
it stays simple, never collides, and never complicates eventual Google Play publication (Play only
requires each upload's `versionCode` to be strictly greater than the last, which this trivially
satisfies).

## Development baselines

Milestones on `plus` that weren't distributed builds, so they have no `versionCode` row above.

**`615e981f`: upstream v0.14.2-beta synchronization (2026-09-28).**

- Upstream `advplyr/audiobookshelf-app` is synchronized through `7014e04e` (v0.14.2-beta), and `plus` is 0 commits behind it.
- `versionName` is `0.14.2-beta`, matching the upstream base. `versionCode` stays 129, and 130 is still unused.
- The five stale queue tests left over from the download-only design were repaired to match the intentional "stream past download gaps" behavior. Coverage was expanded.
- Automated results: JS tests 28/28, Kotlin unit tests 42/42. Debug build, release Kotlin compile, `lintDebug` (0 errors / 122 warnings), `lintVitalRelease` and Android CI all passed.
- Emulator regression validation passed on an AOSP API 35 emulator against a local test server. The results are in `docs/device-regression-checklist.md`.
- **Physical-device validation was pending at the time of this entry.** It was planned for a Samsung Galaxy S22 Ultra; that is historical. The current physical test device is the Galaxy S26 Ultra (`docs/device-regression-checklist.md`).
- The emulator found a **pre-existing** issue: with no player service running, `KEYCODE_MEDIA_PLAY` and `KEYCODE_HEADSETHOOK` don't restore the last session, while `KEYCODE_MEDIA_PLAY_PAUSE` (the widget) does. It is recorded in `docs/device-regression-checklist.md` (section 3, item 6). It must be verified on physical hardware before any change is made.

**Media-button cold start and logcat credential hardening (2026-10-09).** Merged from `fix/s22-reliability-security-final`; not a distributed build, so `versionCode` stays 131 and 132 is still unused.

- Fixes the pre-existing defect recorded above: `KEYCODE_MEDIA_PLAY` and `KEYCODE_HEADSETHOOK` now restore a paused session with no player service, and the race that could crash with `ForegroundServiceDidNotStartInTimeException` is gone. Stops are start-id aware, and a key-down that acts on key-up keeps the placeholder foreground for its key-up (with a 5 s bound).
- Raw logcat no longer receives credentials. Capacitor's own logging is off (`loggingBehavior: "none"`); it had logged plugin call data with `Authorization`/`x-refresh-token` headers, server connection configs, console output and the refresh-token cookie in debug builds. The app's logcat copies are sanitized, and device data and server configs are logged only as summaries.
- Automated results: JS 235/235, Kotlin 169/169, `lintDebug` 0 errors / 108 warnings, `lintRelease` 0 errors / 103 warnings, debug build and release Kotlin compile passed.
- Physical validation on a Samsung Galaxy S22 Ultra (Android 16, One UI 8.0) with `adb` key events: Play, Headset Hook and Play/Pause cold starts 10/10 each, 9/9 after process death, 4/4 offline, plus queue, widget, notification, lock screen, Close and Force stop; no crashes. A final smoke test passed and found no credentials in raw logcat or in the diagnostic export. Real wired-headset, Bluetooth and car-kit buttons were not part of that S22 testing.

**Local artwork crash hardening (2026-10-09).** Merged from `fix/local-artwork-crash-hardening` (fix commit `575c330d`); not a distributed build, so `versionCode` stays 131 and 132 is still unused.

- Fixes the pre-existing crash recorded in `docs/future-work.md`: a downloaded book whose local cover was missing, unreadable, revoked, zero-byte, corrupt or not an image crashed the app when it was prepared, because the cover was decoded on the main thread with no error handling (playback session, MediaSession queue, notification art and Android Auto browsing). A cover `FileProvider` can't serve no longer throws either.
- `player/CoverArt.kt` is now the single failure boundary: expected failures return no image and are categorised (MISSING, PERMISSION, CORRUPT, IO, INVALID), logged once per 5 minutes without a path or URI, and not decoded again in that window. Programming errors and coroutine cancellation still propagate. The session decodes off the main thread, and the metadata (notification, lock screen) falls back to the default Audiobookshelf+ artwork. Valid covers are unchanged.
- Automated results: JS 235/235, Kotlin 184/184 (15 new in `CoverArtTest` and `SessionCoverArtTest`; decoder failures are simulated because Robolectric can't decode images), `lintDebug` 0 errors / 108 warnings, `lintRelease` 0 errors / 103 warnings, debug build and release Kotlin compile passed.
- Physical validation on the Samsung Galaxy S22 Ultra: before the fix, a missing and a corrupt cover crashed widget and media-key restores after process death. After it, valid, missing, corrupt, zero-byte and text-file covers all restored and kept playing (position and 1.5× speed kept; one service, session and notification; no crash). Notification, lock-screen and widget controls worked with a missing cover, a series queue advanced past a book with a corrupt cover, and raw logcat had no paths or credentials. A revoked folder grant was simulated only, and Android Auto was not run.

**LLAMA faithful finish and accompanying fixes (2026-10-10).** Merged from `feature/llama-faithful-first-draft`; not a distributed build, so `versionCode` stays 131 and 132 is still unused. The changes are listed under v0.16.0 in `CHANGELOG.md`.

- The accepted test build was a debug APK (`app.absplus.android.debug`, versionCode 131), SHA-256 `1157fb12dea30686d840353f9c6796477a2e7ae29a312f50f7d847c82bbd990e`.
- **Automated results:** JS 264/264, Kotlin 184/184, `lintDebug` 0 errors / 108 warnings (the baseline register), debug build passed.
- **Physical validation:** Samsung Galaxy S22 Ultra (Android 16), LLAMA at font scales 1.0 and 1.3, Dark, Black and Light for regressions.
  - **Coverage:** Home, Library and series grids and lists; the full player with both progress rails, chapter only, book only and the queue key; collapse and expand; series and row Play; bookmarks online and offline; refocus of downloaded and streamed books; the PDF reader; downloaded-media screens.
  - **Evidence scope:** the final grid-title fix was validated on the accepted build. The rest of the on-device evidence comes from the build before it (`a5828114`), which differed only in that fix.
- **Not tested on a device** (no test content): the EPUB, MOBI, comic and podcast flows; see `docs/future-work.md`. The S26 Ultra was not part of this validation.

**RICE customization (2026-10-12).** Merged from `feature/rice-customization` (final commit `bae478a4`); not a distributed build, so `versionCode` stays 133 and 134 is still unused. Listed under Unreleased in `CHANGELOG.md`; details in `docs/rice-customization.md`.

- **Automated results:** JS 364/364, Kotlin 283/283 (WidgetContainment 8/8), `lintDebug` 0 errors / 108 warnings (the baseline register), `lintRelease` 0 / 103, web build, Capacitor sync, debug and release builds, translation order; GitHub Build APK and translation checks passed.
- **Physical validation:** Samsung Galaxy S22 Ultra (Android 16, One UI 8): the system palette through public APIs matched the rendered colors in light and dark, live system light/dark switching, background/foreground, WebView reload, return to the built-in themes, widget colors, text and Play/Pause. Two light-appearance defects found there were fixed and rechecked (mini-player play glyph, widget button faces). Emulator (Android 15) covered starters, the editor, playback continuity, recovery and a live GitHub import. The S26 Ultra was not part of this validation.

## Announcements

**Deferred for v0.17.0:** v0.17.0 introduced the in-app updater and was published without an Announcement. The updater
announcement waits until a later release has updated an existing production-signed installation through Settings →
Updates on a test phone. Post it with that release, describing the verified result. **Done:** the S22's production
v0.17.0 updated itself to v0.18.1 on 2026-10-12, and [Discussion #26](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions/26)
announced the updater together with v0.18.0 and v0.18.1.

Audiobookshelf+ has no in-app "what's new"/announcements screen (checked during the v0.14.0
release — there isn't one to reuse). The [GitHub Discussions "Announcements" category](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions/categories/announcements)
on this repo is the established, preferred place for users to read about completed Audiobookshelf+
changes, and is treated as part of the release itself, not an optional afterthought.

**Every normal Audiobookshelf+ release must have a matching Announcements post describing the
meaningful user-visible changes included in that release.** Rules for writing it:

- Write it before building the final release APK, as part of the release checklist below.
- Describe only functionality actually included in the release being announced — nothing merely
  investigated, deferred, or still in progress.
- Use plain, user-facing language (what changed for the user), not implementation details,
  internal class/file names, commit SHAs, branch names, or signing/repository-administration
  details.
- Temporary test/RC builds do not get their own Announcements post unless explicitly requested —
  they're for validation, not user-facing history.

## Completing a user-facing feature or fix

**Every completed user-facing Audiobookshelf+ feature or fix receives BOTH:**

1. **README documentation** — an entry in `readme.md` describing it for repository visitors.
2. **A GitHub Discussions → Announcements post** — a plain-language post for users following
   Audiobookshelf+ (same writing rules as above).

A feature/fix is only "completed" once it has passed its required testing (for device-dependent
changes, physical-device approval of a distributed test build) and been merged into `plus`. Until
then — including while temporary test/RC builds of it are being distributed — it is exempt:
**don't** add it to the README as a finished improvement and **don't** post its Announcement.
Both happen together as part of the completion step (merge into `plus` → README → Announcement →
finalize docs), not before.

## Summary checklist for any new distributed APK

1. Check the `versionCode` table above; use `last + 1`. Set `PLUS_VERSION` in `android/app/build.gradle` to the
   release's public version (`X.Y.Z`, the tag's `-plus-vX.Y.Z`) in the same commit (in-app updater, once merged).
2. Build the **release** variant (`app.absplus.android`), not debug, **from a clean build** (`./gradlew clean` first).
   Kotlin incremental compilation can keep the previous `PLUS_VERSION` inlined in the updater after the bump (seen while
   preparing v0.18.0), so also confirm the APK's updater code carries the new version and not the old one.
3. Confirm it's signed with the fingerprint above (`apksigner verify --verbose --print-certs`).
4. For a normal release (not a test/RC build): write/post the Announcements entry for it first,
   and make sure every completed feature/fix it includes is documented in the README.
5. Publish it, then update the `versionCode` table in this document.

## What the in-app updater expects of a release

Once the updater (docs/in-app-updates.md) ships, installed apps rely on these. Changing them stops updates for those
copies.
- **Release type:** a normal published release (not a draft or prerelease), marked Latest, with a tag of the form
  `v<upstream>-plus-vX.Y.Z`.
- **APK:** exactly one asset named `audiobookshelf-plus-vX.Y.Z-release.apk` (same `X.Y.Z` as the tag), between 1 MB and
  200 MB. GitHub's asset digest is used; the `.sha256` file is checked too when attached (keep it to one line,
  `<hex>  <apk name>`).
- **Signing:** signed with the permanent release key above. A future key rotation must use APK Signature Scheme v3
  rotation (lineage) from this key, or installed apps reject the update.
- **Version code:** higher than the previous release's, as always. The updater refuses same-version and older APKs.
- **Public version:** `PLUS_VERSION` set to `X.Y.Z`, or the next check reports the release as newer than the app that
  contains it. `tests/updater.test.mjs` enforces this against the ledger: while `versionCode` equals the last
  released row, `PLUS_VERSION` must be that release's version; once `versionCode` is higher, it must be a newer version.
