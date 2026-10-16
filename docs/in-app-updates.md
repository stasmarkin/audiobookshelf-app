# In-app updates (Settings → Updates)

**Status:** released in Audiobookshelf+ v0.17.0 (versionCode 133). Production self-updates through Settings → Updates
have been verified on real devices, most recently from the published v0.18.2 to v0.19.0 (see "Verified production
updates" under Testing).

The GitHub-distributed Audiobookshelf+ app can check the official GitHub releases, download the latest stable release
APK, verify it and hand it to Android's own installer. Every step starts from a button in Settings. Nothing downloads or
installs on its own, and the last step is always Android's confirmation screen.

The flow is CHECK → AVAILABLE → DOWNLOAD → VERIFY → INSTALL CONFIRMATION.

## Code

**Native (`android/app/src/main/java/app/absplus/android/updater/`):**

| File | What it does |
|---|---|
| `UpdateSource.kt` | Where updates come from and what they must be: the repository, allowed hosts, target package and trusted signing certificate. `UpdateSource.Production` is the only source in release builds. |
| `ReleaseSelector.kt` | Parses GitHub's latest-release response, checks the stable rules and picks the one production APK. Also holds the version and error types. |
| `UpdateHttp.kt` | All network access: HTTPS only, allowed hosts only, hand-checked redirects (at most 5), bounded timeouts, a streamed download into a partial file with a running SHA-256, and cancellation. |
| `ApkVerifier.kt` | Reads the downloaded APK through Android's package parser and decides whether it may be installed. |
| `UpdateStore.kt` | The app-private `cache/updates` directory: the partial download, then the verified APK and its record. |
| `UpdateStateMachine.kt` | The states, and the only allowed moves between them. Repeated presses can't start parallel work. |
| `UpdatePolicy.kt` | Small decisions: checksum files, Play-installed copies, storage headroom, install permission, version comparison. |
| `UpdateInstaller.kt`, `UpdateInstallReceiver.kt` | The PackageInstaller session and the installer's status. |
| `UpdateManager.kt` | Runs the steps on one background thread and logs events to Diagnostics. |

**Build-type configuration:**
- `src/release/.../updater/UpdaterConfig.kt` returns `UpdateSource.Production` and nothing else.
- `src/debug/.../updater/UpdaterConfig.kt` can also hold a loopback test source for device tests. This file is not
  compiled into release builds.

**Bridge and UI:**
- `plugins/AbsUpdater.kt`: the Capacitor bridge. JS can only read the state and send button presses; it never passes a
  URL, file or package.
- `components/settings/Updates.vue` and `utils/updaterView.js`: the Settings section.
- `plugins/capacitor/AbsUpdater.js`: the JS plugin.

## Release selection

- **Endpoint:** `GET https://api.github.com/repos/ADD-OCD/audiobookshelfplus-app/releases/latest`, unauthenticated (no
  GitHub account or token). GitHub's "latest" already leaves out drafts and prereleases; the app checks `draft` and
  `prerelease` again anyway.
- **Tag:** must be an Audiobookshelf+ release tag, `v<upstream>-plus-vX.Y.Z` (for example
  `v0.14.2-beta-plus-v0.16.0`). This also leaves out the temporary device-test releases.
- **APK asset:** exactly one asset named `audiobookshelf-plus-vX.Y.Z-release.apk`, with the tag's version. It must be
  `uploaded`, typed `application/vnd.android.package-archive` or `application/octet-stream`, and between 1 MB and
  200 MB.
  - Its `browser_download_url` must be exactly
    `https://github.com/ADD-OCD/audiobookshelfplus-app/releases/download/<tag>/<name>`.
  - Debug APKs and other attachments are ignored. Two production APKs, or two checksum files, reject the release.
- **Digests:** the asset's GitHub `digest` (`sha256:…`) and the release's `<apk>.sha256` file, when present, must both
  match the download. A release with neither is rejected.
- **Release page:** the `html_url` must be the official release page; it is the only link the app opens.
- **Rate limits:** a 403 or 429 with `X-RateLimit-Remaining: 0` or `Retry-After` shows "GitHub is limiting requests".
  Checks are manual, so the unauthenticated limit (60 an hour per IP address) is not a practical constraint.

## Version policy

The Android `versionName` stays the upstream version (`0.14.2-beta`), so it cannot tell Audiobookshelf+ releases apart.

- **Discovery:** the release tag's `vX.Y.Z` is compared numerically with `BuildConfig.PLUS_VERSION`. That field is set in
  `android/app/build.gradle` next to `versionCode`, and is bumped with it for every release (docs/release-process.md).
  "Up to date" means the tag's version is not higher. `tests/updater.test.mjs` checks `PLUS_VERSION` against the
  release ledger, so a release cannot ship with a stale or skipped public version.
- **Installation:** the downloaded APK's own `versionCode`, read by Android's package parser, must be greater than the
  installed `versionCode`. The comparison uses 64-bit values. Same-version and downgrade APKs are rejected, whatever the
  tag says.

## Verification before installation

A download becomes "ready to install" only after all of these pass. A failure deletes the file.

1. **Completed and the right size:** the transfer ended, and the byte count equals the release's `size` (and the
   response's `Content-Length`). Shorter, longer and HTML responses are rejected; the first bytes must be a ZIP header.
2. **Digests:** the SHA-256 computed over the completed file matches every published digest.
3. **Android's parser:** `PackageManager.getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES` (API 28+) or
   `GET_SIGNATURES` (API 24–27) parses the APK and verifies its v1/v2/v3 signatures cryptographically. An APK that does
   not verify gives no package info or no certificates, and is rejected.
4. **Package:** `app.absplus.android` (production).
5. **Version code:** newer than the installed one.
6. **Not debuggable.**
7. **Exactly one signer, and it is trusted:** the certificate's SHA-256 must be the permanent Audiobookshelf+ release
   certificate `c540046a…b9d4` (pinned in `UpdateSource.kt`, documented in release-process.md).
   - **Key rotation:** a different signer is accepted only when Android's verified APK Signature Scheme v3 lineage shows
     the trusted key authorised it.
   - Any other certificate is rejected. Multi-signer APKs are rejected.
8. **Storage:** the file never leaves app-private storage (`cache/updates`). Paths in responses are never used.

The same checks run again right before installing. The file is then copied into the installer session while being
hashed, and the session is committed only if the bytes are exactly the verified ones.

GitHub's digest alone does not prove the file is genuine, since the digest and the file come from the same place. The
signing-certificate pin does. Android's installer then verifies the APK once more and refuses an update not signed like
the installed app. The app never disables that check.

## Installation and permissions

- **Installer:** a `PackageInstaller` session (`MODE_FULL_INSTALL`, the target package name, and `USER_ACTION_REQUIRED` on
  Android 12+). There is no `file://` URI and no FileProvider sharing.
- **Confirmation:** Android answers `STATUS_PENDING_USER_ACTION` with its own confirmation screen. The app shows it from
  its activity when it is in the foreground; otherwise it waits and shows it on the next resume. **Open installer** shows
  it again while the session waits, for example after leaving it with Home. The app never accepts it on the user's
  behalf.
- **Permission:** `REQUEST_INSTALL_PACKAGES`. From Android 8 the user must allow "Install unknown apps" for Audiobookshelf+.
  - The app asks only after the user taps **Install update**. It then explains why and offers **Open Android settings**
    (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package).
  - Coming back to the app refreshes the state; tap Install again. There are no repeated prompts.
- **Outcomes:**
  - Cancelling Android's screen keeps the verified APK, so Install can be tried again without downloading.
  - "Blocked" and "not enough storage" also keep it.
  - A conflicting or invalid package deletes it.
- **While playing:** if audio is playing, the app asks first, because Android may stop playback and restart the app while
  it updates. Playback, downloads, settings and progress are app data; a same-package update keeps them.

## Cancelling and recovering

- **Cancel download:** stops the transfer at once (the connection is closed) and deletes the partial file.
- **Retry download:** shown after a failed or cancelled download, and downloads the same release again.
- **Interrupted downloads:** a download interrupted by process death is deleted at the next start. A verified update
  survives a restart; it is checked again (size and digest) and offered for installation.
- **Updated meanwhile:** when the installed version reaches the verified update's version (installed by the updater or
  by hand), the file is deleted.
- **Newer release:** a check that finds a newer release than the verified one deletes it. There is at most one APK at a
  time.

## Google Play separation

Self-updating is not allowed for apps distributed on Google Play, and `REQUEST_INSTALL_PACKAGES` is a restricted
permission there. The updater is for the GitHub build.

- **Play-installed copies:** when the installing package is `com.android.vending`, the section shows that updates come
  from the store, and does nothing else.
- **A future Play build** would add a Play variant that removes the permission and the receiver from its manifest
  (`tools:node="remove"`), with an `UpdaterConfig` that disables the section. Play's own update mechanism would replace
  the updater.

## Network security

`res/xml/network_security_config.xml` gives `api.github.com`, `github.com`, `release-assets.githubusercontent.com` and
`objects.githubusercontent.com` HTTPS only, trusting system certificate authorities only. The rest of the app keeps its
existing configuration.

## Diagnostics and privacy

Update events go to the existing sanitized diagnostic log, tag `Updater`:
- check started and completed;
- the candidate version;
- download started, completed and cancelled;
- verification result;
- installer requested, cancelled and returned;
- cleanup.

URLs, file paths, headers, response bodies and content URIs are never logged. There are no requests besides GitHub's and
no analytics.

## Testing

- **JVM unit tests (`android/app/src/test/.../updater/`):**
  - `ReleaseSelectorTest`: parsing, stable filter, asset selection, URLs;
  - `ApkVerifierTest`: package, version code, signer and rotation;
  - `UpdateHttpTest`: scripted responses for redirects, content types, size, cancellation, timeouts and network errors;
  - `UpdateStateMachineTest`, `UpdatePolicyTest`, `UpdateStoreTest`;
  - `UpdateInstallerTest` (Robolectric): the session, the status intent and the manifest.
- **JS:** `tests/updater.test.mjs` covers the view model and the wiring.
- **Device tests:** debug build plus a fixture source (`C:\Users\Ben\AI\CLAUDE\dev-tools\updater\`):
  - `fixtures/build-fixtures.sh` builds a disposable test package `app.absplus.updaterfixture`, signed with disposable
    test keys. It is never signed with the release key.
  - `fixtures/server.js` stands in for the GitHub API and downloads on `127.0.0.1:18765` (`adb reverse tcp:18765`), with
    switchable scenarios.
  - `AbsUpdater.setTestSource` (debug builds only) points the updater at it, trusting only the fixture certificate. A test
    source may not target the production package or the app itself, nor trust the production certificate.
  - These tests exercise Android's real installer with the fixture package. They do not exercise the production signing
    path end to end; that is covered by updating one published release to the next.

### Verified production updates

Published, release-signed builds updated in place through Settings → Updates, with Android's own confirmation each time:

- v0.17.0 → v0.18.0 (Galaxy S26 Ultra, 2026-10-12), v0.17.0 → v0.18.1 and v0.18.1 → v0.18.2 (Galaxy S22 Ultra).
- **v0.18.2 → v0.19.0** (Galaxy S22 Ultra, 2026-10-15, after publication). The installed v0.18.2:
  1. discovered the published v0.19.0 release;
  2. downloaded its signed APK;
  3. verified it (size, digests, package, version code, signing certificate);
  4. asked for Android's "Install unknown apps" permission (a fresh install had not granted it yet);
  5. opened Android's standard installer;
  6. installed v0.19.0 in place (installer: the app itself; first-install time unchanged; installed APK byte-identical
     to the release asset);
  7. kept sign-in, settings and the downloaded book;
  8. reported "Audiobookshelf+ v0.19.0 (137)" and "up to date" on the next check.

  Google Play Protect showed "App scan recommended" for the newly published APK before Android's installer continued;
  that prompt comes from Play Protect for APKs it has not seen before, not from the updater ("More details → Install
  without scanning" or "Scan app" continue the installation).
