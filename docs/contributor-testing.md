# Contributor testing channel

Contributor prereleases (`v0.20.0-dev.N`) are experimental builds of the consolidated PR32/PR34/PR35 and safety
candidate. They are not stable releases and not merges into `plus`.

## Package, version and signing

| Prerelease | Package | versionCode / versionName |
| --- | --- | --- |
| `v0.20.0-dev.1` | `app.absplus.android.debug` | 138 / `0.20.0-dev.1` |
| `v0.20.0-dev.2` onwards | **`app.absplus.android.contributor`** | 139 / `0.20.0-dev.2` |

- From dev.2 the `contributor` build type uses its own package, `app.absplus.android.contributor`. dev.1 shared
  `app.absplus.android.debug` with CI and local debug builds, which are signed with a different key, so neither could
  update the other.
- The build is not debuggable and explicitly disables WebView remote debugging. The regular release package
  `app.absplus.android` and its versioning are unchanged.
- Build with `:app:assembleContributor` after frontend generation and Capacitor asset synchronization. The output is
  unsigned; sign it offline with the persistent contributor-only key (certificate SHA-256 `25802304…90a7`). No
  signing secrets or machine-specific paths belong in the repository.
- Later contributor prereleases keep this package and signer and use a higher versionCode, so they install over dev.2
  as updates. Install them manually from their named prerelease. The production in-app update download channel and
  fixture-source overrides are disabled in this variant.
- versionCodes 138 and 139 are allocated to the contributor channel only; the production ledger in
  `release-process.md` is unchanged.

This supervisor-authorized channel is an exception to the default distribution policy in `release-process.md`: a
non-debuggable testing variant with a separate package and signer, not a regular production release. Do not sign it with
the production key or upload a normal debuggable APK.

## Moving from dev.1 to dev.2

dev.1 (`app.absplus.android.debug`) and dev.2 (`app.absplus.android.contributor`) are **separate Android apps**.

- dev.2 does not update dev.1 in place, and nothing on the phone moves from one to the other automatically.
- Both can be installed at the same time, next to the regular Audiobookshelf+ app.
- Installing dev.2 changes nothing in dev.1. Uninstalling dev.1 deletes dev.1's own data: its downloads in app storage
  (internal or SD card), its local listening progress, bookmarks made while offline, its settings and its saved server
  logins.

Before uninstalling dev.1:

1. **Sync your listening progress.** Open dev.1 while it can reach your server. It sends listening saved offline when
   it reconnects. Check on the server (or in the regular app) that the books and episodes you listened to offline show
   the right position. Progress and bookmarks that reached the server appear in dev.2 after you sign in there. Anything
   saved only offline and never synced exists only in dev.1.
2. **Note your settings** (theme, jump times, long jumps, download storage, auto download and auto delete). They don't
   carry over.
3. **Downloads:**
   - Downloads in **app storage** (internal or the app's SD card folder) are deleted with dev.1. Download them again in
     dev.2.
   - Downloads in a **folder you picked** stay on the phone. In dev.2, add the same folder under Local Folders. dev.2
     doesn't know these files as downloads, and **Rescan Folder** links books it can match. Downloading again into the
     same folder is safe: an existing file with the same name is kept, and the new download is saved under its own
     alternative name. Delete leftovers with your file manager once you no longer need them.
4. Only then uninstall dev.1, if you want to. Keeping both installed is fine.

## What to test, and known limits

- Test automatic deletion with disposable media and a dedicated app-managed download folder. Listening history should
  survive eviction and temporary storage loss; report any loss immediately.
- **Not yet validated:**
  - physical Samsung and SD card behavior;
  - the full elapsed six-hour foreground-service quota (shortened Android 15 framework timeout tests are not six hours
    of real use);
  - Android 7–9 on real devices (deletion in picked folders is unit-tested for Android 8–9; on Android 7 the app asks
    you to use the file manager).
- Legacy scanner stale server-progress overwrites and known HTTP range/integrity limitations remain outside these
  bounded corrections. Do not regard matching filenames or lengths as a universal integrity guarantee.
- User-requested in-app diagnostic logs remain available. Development-only WebView debugging and Android `run-as`
  access are disabled by the non-debuggable configuration.
- Do not install the separate synthetic test-provider APK as part of the distributed application.
