# Contributing to Audiobookshelf+

Bug reports, focused fixes, documentation, translations, and ideas are welcome.
Audiobookshelf+ publicly releases and tests **Android only**. The upstream iOS code
remains in the repository, but iOS is not presently a supported Audiobookshelf+
release target.

## Start with the right conversation

Search existing Issues and Discussions before opening a report. Use the bug,
feature, or documentation form for Audiobookshelf+ work, and Discussions for
questions. Discuss large changes before investing substantial implementation time.
Server-only bugs belong in the upstream Audiobookshelf server project.

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
Never publish tokens, passwords, private server URLs, personal information, or
unpatched sensitive vulnerability details. Review and redact logs and screenshots
before attaching them. The `security` label is only for publicly safe hardening
tasks; it does not make an Issue private.

## Pull request workflow

1. Fork the repository and create a focused topic branch from **`plus`**, the primary
   development and production branch. Keep unrelated formatting and refactors out.
2. Make and test your change. Keep your fork's branch reasonably current with
   `plus`; do not change other contributors' branches.
3. Open a PR targeting `plus`. Use a draft while work is incomplete. Link relevant
   Issues with `Related to #123`, or `Fixes #123` if the PR fully resolves them.
   Small standalone corrections do not require a separate Issue.
4. Describe the problem, resulting behavior, approach, tradeoffs, and validation.
   Use concise, descriptive commits (for example `fix: restore offline queue`).
   Include redacted before/after screenshots for UI changes.
5. Address maintainer feedback and explain any tests you could not run. New outside
   contributors may need a maintainer to approve the initial CI run.

Maintainers review scope, correctness, security, compatibility, and test evidence
before merging. **Outside contributions are never automatically merged.** Passing
CI is necessary where applicable, but does not replace maintainer review. Maintainers
may request changes, defer a proposal, or coordinate a smaller implementation.

Preserve original authorship when adapting someone else's work; include co-author
credit when appropriate. Contributors receive credit through Git history and PRs;
maintainers can acknowledge notable contributions in release notes with the
contributor's preferred public attribution.

## Setup and testing

Use the Android setup in [readme.md](readme.md). CI currently uses Node.js 20 and
Java 21. Install locked dependencies with `npm ci --omit=optional`. Run applicable
checks from the repository root:

```sh
node --test tests/*.test.mjs
npm run generate
npx cap sync android
./android/gradlew assembleDebug -p android --no-daemon
./android/gradlew testDebugUnitTest -p android --no-daemon
```

On Windows, use `android\gradlew.bat` for Gradle. Add focused regression coverage
for behavior changes. For Android changes, run applicable lint checks and report
new warnings. For playback, downloads, widgets, UI, or lifecycle behavior, describe
device testing, Android version, reproduction steps, and remaining gaps; test both
streaming and offline playback where relevant. Documentation-only changes need
link/content validation, not a local signed APK. Let the applicable CI checks finish.

The existing `Build APK` check runs the JS suite, web build, Android debug build,
and Kotlin/Robolectric tests. Translation CI checks JSON key ordering. Dependency
review checks newly introduced known vulnerable dependencies on PRs after its
workflow is merged. Report exact results rather than marking unrun checks as passed.

Do not request or add signing credentials. Production signing and publication are
maintainer responsibilities governed by [the release process](docs/release-process.md).
Leave release version bumps (`PLUS_VERSION` and Android `versionCode`) to maintainers
unless explicitly coordinated. Do not upload development APKs to Releases.

## Code style, translations, and documentation

Follow nearby Vue, JavaScript, and Kotlin conventions and the repository's
`.prettierrc` (including single quotes and no JavaScript semicolons). Avoid whole-file
formatting unrelated to the change. Keep native/web boundaries and existing privacy
protections intact.

Put user-facing text in `strings/<locale>.json`, preserve placeholders, and keep
keys alphabetized. Audiobookshelf+ strings need English fallback text; do not invent
translations you cannot verify. Upstream translations are maintained through
[Audiobookshelf on Weblate](https://hosted.weblate.org/engage/audiobookshelf/).
Explain new or changed strings in the PR and preserve existing translations.

Update relevant technical and user documentation alongside behavior changes. Explain
setup, compatibility, and limitations. Prepare user-facing wording in the PR, but
coordinate finished-feature README entries and release announcements with maintainers
as required by the release process; do not describe unmerged work as released.