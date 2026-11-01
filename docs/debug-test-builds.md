# Remote debug test builds

`.github/workflows/debug-test-apk.yml` ("Debug Test APK (development only)") builds a **signed debug APK** of a development branch, so a build can be downloaded and installed on a phone away from the development computer. These are **development test builds, not releases**: no GitHub Release, tag or production signing is involved.

## What it produces

|           |                                                                                                                                                                           |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Package   | `app.absplus.android.debug`: installs **alongside** the production app `app.absplus.android`, with its own data, settings and widgets                                     |
| File      | `AudiobookshelfPlus-<versionName>-debug-<label>.apk`, e.g. `AudiobookshelfPlus-0.14.2-beta-debug-llama.apk`                                                               |
| Artifact  | `AudiobookshelfPlus-<LABEL>-S26-Test`, containing the APK and `build-info.txt` (branch, commit, version, package, APK SHA-256, certificate SHA-256, build time, run link) |
| Retention | 5 days (disposable)                                                                                                                                                       |

The label is the dispatch input, or else the first word of the branch name (`feature/llama-theme` → `llama`).

## How it runs

On demand only (`workflow_dispatch`), for the branch that is ready for a phone test. Pull requests (and on-demand runs) are validated by the Build APK workflow (JS tests, debug build, Kotlin tests); this workflow only produces the installable test APK, so it does not run, or keep artifacts, for pushes nobody tests on a phone.

- From GitHub: **Actions** → **Debug Test APK (development only)** → **Run workflow**, pick the branch, optionally enter a label.
- From a computer: `gh workflow run debug-test-apk.yml -R ADD-OCD/audiobookshelfplus-app --ref <branch>` (optionally `-f label=<label>`).

It runs only for branches of this repository. There is no `pull_request` trigger, so code from forks never reaches the signing secrets.

## No automatic debug APK

Pushes to `plus` no longer publish a debug APK (this saved Actions time and artifact storage). To get the current `plus` build, run **Debug Test APK (development only)** on the `plus` branch as described above. Earlier `AudiobookshelfPlus-debug-<commit>-run<id>` artifacts from Build APK expire after 14 days.

## On the phone

1. Open the GitHub app or github.com → **ADD-OCD/audiobookshelfplus-app** → **Actions** → **Debug Test APK (development only)**.
2. Open the newest run for the branch. The run summary shows the commit and APK SHA-256.
3. Under **Artifacts**, download `AudiobookshelfPlus-…-S26-Test` (a ZIP; downloading needs a signed-in GitHub account).
4. Extract the ZIP and open the `.apk` to install. Allow installs from the browser/file manager if Android asks.

Later test builds install **over** the existing debug app and keep its data, because they are all signed with the same test key. They never replace the production app.

If a debug app signed with a different key is already installed (for example one built locally with Android Studio's default debug key), Android refuses the update once: uninstall `Audiobookshelf+` **debug** (not the production app), then install. After that, every build from this workflow updates in place.

## Artifact hygiene

Test APKs are disposable. They are not an archive of development builds, and nothing should depend on an old one: any commit can be rebuilt with the same key by running the workflow again.

- Debug Test APK artifacts expire after 5 days, enough for a phone test session and a retest.
- **At phase close** (once a phase is accepted and integrated, or abandoned), delete that phase's remaining test APK artifacts instead of waiting for them to expire: **Actions** → the run → **Artifacts** → delete, or `gh api -X DELETE repos/ADD-OCD/audiobookshelfplus-app/actions/artifacts/<id>`. Keep the workflow and the three signing secrets; they are what create the next test APK.
- Only development test artifacts are cleaned this way. Never delete GitHub Releases, release assets or tags as part of it.

## Signing model

- A dedicated **debug/test key**, used only for `app.absplus.android.debug`. It is not the production key, and the workflow never references production signing (`keystore.properties`, the release keystore or release tasks).
- The workflow builds `assembleDebug`, then re-signs the APK with the test key using `apksigner` (passwords passed through environment variables, not the command line). The decoded keystore lives only in the runner's temp directory and is deleted at the end of the step.
- The certificate SHA-256 is pinned in the workflow (`EXPECTED_CERT_SHA256`). If the APK isn't signed with exactly that one certificate, or the package isn't `app.absplus.android.debug`, the run fails. There is no fallback to a throwaway key.
- Test certificate SHA-256: `0e22f17fc1da110460bef1729a80a1e045c9977968450c00fceedf6d61796c0a`.

Required repository secrets (names only):

- `ABSPLUS_DEBUG_KEYSTORE_BASE64`: the PKCS12 keystore, base64 encoded
- `ABSPLUS_DEBUG_KEYSTORE_PASSWORD`: its password (also the key password)
- `ABSPLUS_DEBUG_KEY_ALIAS`: the key alias

The maintainer keeps a private copy of the test keystore outside the repository. Keystores are never committed (see `.gitignore`). If the test key is ever replaced, update the three secrets and `EXPECTED_CERT_SHA256` together; phones then need one uninstall of the debug app.
