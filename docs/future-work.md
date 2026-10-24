# Future work

Items noted for later investigation. Nothing here is implemented or scheduled. Each item needs its own authorization before work starts.

## "Follow Device" theming

**Done as part of RICE** (merged into `plus`, not yet released): "Follow device" uses the Android 12+ system palette, which Samsung One UI's Color palette also sets, plus system light/dark and contrast. Verified on a Galaxy S22 Ultra. Samsung Galaxy Themes data is not exposed to apps through public APIs and is not used. See `docs/rice-customization.md`.

## RICE follow-ups

- Image-based palettes (the preset schema reserves `source.kind = 'image'`).
- More import formats (GTK, Rofi, Polybar and other config files) and hosts other than GitHub.
- Right-to-left and Chinese/Japanese/Korean text with the RICE type options on a device.
- Colors that predate the token system and stay fixed in every appearance (for example the yellow in-progress bar on book cards).
- With three-button navigation, One UI draws its own dark scrim over the navigation bar (built-in Light shows it too).

## Runtime system-bar colors

Apply the `system.status-bar`, `system.navigation-bar` and `system.bar-icons` tokens natively, so that for example Light can have light bars with dark icons. Today every theme shows the static `#232323` window background behind the bars. This needs a small native method and cold-start persistence (see "System bars" in `docs/theme-architecture.md`). It is a visible change, so it needs its own phase and physical-device testing on the current test device (Galaxy S26 Ultra).

## Bundled text fonts

Source Sans Pro and Ubuntu Mono never load: their `@font-face` rules use the invalid `format('ttf')`, so the app renders in the system sans-serif and monospace fonts (Roboto and Droid Sans Mono on the tested Pixel emulator). This is inherited unchanged from upstream and affects every theme. Phase 2C Gate G verified it at runtime and deferred it; the evidence and measurements are in `docs/theme-architecture.md` ("Typography runtime audit and deferral").

Restoring the bundled fonts is a global typography change, not a LLAMA fix. It would change appearance, weight hierarchy (no Bold is bundled, so 700 becomes SemiBold), readability, text metrics, some content-sized elements and some marquee decisions, and possibly consistency across devices. Decide deliberately, together with future typography presets that always fall back safely for Arabic, Hebrew and Korean.

Options to evaluate (no solution chosen):

- **A.** Correct the existing declarations to valid TrueType hints, keeping the current bundled files.
- **B.** Correct the declarations and add properly licensed missing weights with clear provenance, especially a true Source Sans Pro Bold (700).
- **C.** Convert to or replace with WOFF2, preserving the intended metrics and licensing.
- **D.** Standardize on Android system fonts on purpose, retiring the dead declarations and assets where appropriate.
- **E.** Offer typography as an opt-in preference or theme capability, if that fits later customization work.

Validation for any change:

- Dark, Black, Light and LLAMA.
- Pixel/emulator, and Samsung/One UI where appropriate.
- Font scales 1.0 and 1.3.
- Light, normal, semibold and bold requests.
- Non-Latin fallback.
- Marquee initialization and cold start.
- The 22-role geometry comparison and representative Gate B–E surfaces.
- Accessibility and readability.
- Native widget non-regression (the widget uses native typography and should not change).

## Startup theme flash

A saved non-default theme (Black, Light, LLAMA) is restored asynchronously from Preferences, so the pre-render loading screen briefly shows the default Dark surface. This was measured at about 1.3 s on the emulator during the LLAMA Gate 1 review. It is pre-existing and not LLAMA-specific. A possible fix is a synchronous startup cache of the resolved theme applied before the app bundle runs. It would change startup and theme-restoration lifecycle behavior, so it needs its own phase.

## Live widget progress

The FULL widget's progress and time are a snapshot from the last widget update (Phase 2B). A live readout without periodic polling would need widget refresh calls on seek and speed changes inside playback code (a `Chronometer` alone drifts at non-1× speeds and after seeks). That touches playback code, so it needs its own authorization and battery review.

## Widget session mismatch after restart

After a process restart the widget shows `deviceData.lastPlaybackSession`, while widget Play resumes the session chosen by the restoration store. On the emulator these were different books. This is pre-existing (the baseline build shows the same session) and lives in playback/restoration, so it was not changed in Phase 2B. The related S26 observations from the Phase 2C final acceptance are recorded separately below ("Widget controls after a book finishes or the player closes" and "Stale widget snapshot without controls").

## Widget responsive-layout text containment

Found in Phase 2C Gate F; it affects every widget theme. LLAMA was mitigated with spacing and text-size changes in its own layouts. The standard layouts are unchanged.

- **FULL NORMAL:** the readout panel takes the cover row's height. At narrow widths (≤~300dp) `FullArtwork` limits the cover by the width, so the readout gets ~86dp at every height, while a two-line title, author and time row need ~101dp. The time row (and the author) are squeezed.
- **COMPACT / WIDE:** two text lines share half of a short row and clip below ~72–92dp tall, depending on theme and layout.
- **Font scale:** larger scales widen both.

Measured boundaries are in `docs/widget-architecture.md` (Gate F section). `WidgetContainmentTest` asserts only the sizes that fit today.

A complete correction needs its own gate. Areas to investigate (no solution chosen):

- `FullArtwork` planning: reserve the readout's minimum height at narrow sizes instead of following the cover.
- A readout minimum-height reservation in the NORMAL layouts.
- The NORMAL/EXPANDED choice at narrow or short sizes.
- Accessibility font-scale behavior in every presentation.
- Whether the standard layouts get the same mitigation.

## Shared Dialog keyboard support

Found in Phase 2C Gate H; inherited unchanged from upstream and the same in every theme. `modals/Modal.vue` and `modals/Dialog.vue` (all option menus, including the theme picker) have no focus management:

- opening a dialog does not move focus into it;
- Tab continues through the page behind it (no focus containment);
- Escape does not close it;
- the `<li role="option">` options are not focusable, so a keyboard or switch user cannot choose one.

Touch and pointer use are unaffected; TalkBack was not tested. Fixing this changes behavior in a shared component, so it needs its own authorization and keyboard, switch-access and TalkBack testing.

## Font scale 1.3 geometry

Found in Phase 2C Gate H and re-measured during the Phase 2C final acceptance. Android's font scale reaches the WebView (the root font size is 20.8px at 1.3), and icon glyph sizes follow it. Nothing becomes unusable. Dark and LLAMA have identical geometry, so this is shared, pre-existing geometry rather than a LLAMA regression (LLAMA's seams only make it more visible). On the tested emulator (412 CSS px viewport) at 1.3:

- **Mini-player:** the controls group stays a fixed 128px wide while its icon keys grow from 30 to 39px, so the jump-forward key frame extends about 4.2 CSS px beyond the right viewport edge. The glyph stays visible, the key stays tappable, no controls overlap, the play button stays 40×40 (shifted 9px), and the 120px height is unchanged.
- **Full player:** the jump keys grow to about 39×57 and approach the LLAMA seam below the control row, but stop about 2px short of it. The key's drop shadow visually meets the seam; the key and its "10s" label do not cross it. Nothing overlaps the bottom row.

Any fix is geometry-affecting responsive/accessibility work, so it needs its own authorization and a font-scale pass on the S26 Ultra's One UI font-size steps.

The Phase 4 finishing pass (LLAMA) gave the collapsed player fixed 34px key boxes, which no longer grow with the font scale: at 1.3 its forward key now ends at 406px, inside the screen. Dark, Black and Light keep the geometry described above.

## Error-button text contrast

Found in Phase 2C Gate H; shared by every theme. Error buttons (`ui/Btn` with `color="error"`) carry 3.19:1 white text in Dark and Black and 2.79:1 near-white text in LLAMA, below 4.5:1 (Light uses dark text and passes). Gate H fixed only the LLAMA destructive icon-key glyph (non-text, now 3.25:1). A semantic error-action fill, like `state.success-action`, is the likely shape; it needs its own decision.

## Dark muted text and item-header contrast

Found in Phase 2C Gate H; legacy themes, unchanged by LLAMA. In Dark, `text-fg-muted` measures 3.79:1 on cards and settings headers and 4.28:1 in the Chapters header, below 4.5:1 for that small text. On the item page the "Author"/"Duration" labels over the cover-tinted header measured 1.8–2.0:1 and the title 3.85:1 with the test cover. It needs a decision on changing legacy theme values.

## Success toast colors

Found while implementing Gate H. Toasts use vue-toastification's own stylesheet, whose success toast is white on `#4CAF50` (2.78:1). Toasts are notifications, not the actionable success controls Gate H fixed, so they were left unchanged. Overriding the library's colors needs its own decision.

## onPlaybackSession listener after a cold launch

Observed once during the Gate H audit on the emulator, not investigated. After a cold launch from the launcher, native prepared a session but logged "No listeners found for event onPlaybackSession", so the WebView did not see the session until the page was reloaded. It may be specific to that launch path. Reproduce it before treating it as a bug. The Galaxy S26 Ultra cold launch during the Phase 2C final acceptance did not reproduce it (no session was restored until Play was pressed, as designed).

## LLAMA non-widget control fidelity (after Phase 4H)

User direction recorded in Phase 4H. Phase 4H completed only the primary Play/Pause control and was not the final LLAMA visual-fidelity gate. The user wants the rest of the non-widget player controls to move closer to the approved mockup. A later audit should compare the complete non-widget control area against the mockup, keeping the real app authoritative for layout, behavior, touch targets and accessibility:

- **Transport and secondary controls:** rewind, forward and chapter previous/next. Physical key depth, bezel consistency with the new primary key, glyph scale and weight, grouping, and their relationship to the primary key.
- **Utility controls:** queue, bookmark, sleep and chapters/list. Physical key treatment; amber legends since the finishing pass (see the equipment color rule in `theme-architecture.md`).
- **Top player chrome (a separate, later pass):** collapse, cast, overflow and the DIRECT / playback-method display.

The semantic rule stays: playback and transport legends are amber, utility legends are neutral. Not every control becomes amber.

Phase 4J and the Phase 4 finishing pass implemented the transport bank, the utility keys and readouts, the collapsed-player keys and the top chrome (see "LLAMA control deck" in `docs/theme-architecture.md`). What remains:

- **Legends at very large font scales:** the transport legends fit their fixed keys at font scale 1.0 and 1.3. At much larger One UI steps (2.0 measured) the zoomed legends exceed their keys, although no key moves or overlaps another. A true maximum legend size cannot be expressed in CSS under the WebView's text zoom.
- **Playback Speed dismissal on the S26:** fixed (it did not close above or below its panel; see the control-deck section of `theme-architecture.md`). Awaiting the physical S26 retest.
- **Up Next backdrop strip:** the queue modal is 95% wide, so its side backdrop is a ~10px strip that Chromium's touch adjustment pulls into the panel (pre-existing; dismissal works above and below the panel and with the close key).

## Seek handle touch target

Found on the Galaxy S26 Ultra during the Phase 2C final acceptance; inherited and the same in every theme (not introduced by LLAMA). Relatively **high priority** accessibility/usability work.

The full player's seek handle is hard to grab. Dragging works, but the 28×28 handle (`trackCursor`) sits inside the 6px track, whose `overflow-hidden` clips it, so the effective touch target is about 28×6 CSS px. Its height is well below the 24×24 CSS px minimum (WCAG 2.2, 2.5.8).

Preferred direction: keep the thin visual seek treatment, and give the handle a larger invisible touch target centered on the current position. That changes hit testing and likely geometry, so it was deliberately not changed during Phase 2C acceptance.

## Widget controls after a book finishes or the player closes

Observed on the Galaxy S26 Ultra during the Phase 2C final acceptance. **Product/UX decision required** before any implementation.

When a book finishes or the playback session is closed, the widget can lose its controls: the restore/session model (from `plus`, `0dbfac4e`, before Phase 2C) clears the "resumable" flag, and the widget shows controls only for a live or resumable session. It still shows the last book's metadata.

Decide what the widget should represent when playback has completed, when playback was closed, when there is no resumable session, and while metadata is still available. Do not change the behavior until that is decided.

## Stale widget snapshot without controls

Observed once on the Galaxy S26 Ultra during the Phase 2C final acceptance. The widget showed an old position (an earlier snapshot) and no controls while the app had a paused playback session. A force-stop and relaunch restored the expected widget. Dark showed the same state, so it is not LLAMA-specific.

The cause is undetermined: no device logs were captured at the time. It belongs with the playback/session/widget restoration work (see "Widget session mismatch after restart"). Capture `adb logcat` and a Diagnostics export when it recurs before treating any cause as known.

## Year in Review wordmark

The Year in Review share images (`components/stats/YearInReview*.vue`) draw an "audiobookshelf" wordmark and name their files `audiobookshelf_*.png`. They show listening data from the Audiobookshelf server but are generated by this app, so whether they should say Audiobookshelf or Audiobookshelf+ is a product decision. Left unchanged until it is made.

## Rescan Folder: cancellation and progress after leaving

Rescan Folder (`pages/localMedia/folders/_id.vue`, `AbsFileSystem.rescanFolder`, `FolderScanner.rescanFolder`) reports its progress live and keeps running after the page is left, as before. A reopened folder page shows "Scanning…" at once (`isRescanning`) and picks the counts up from the next progress event; there is no stored snapshot of the counts, and a scan can't be cancelled. Add either only if large real-world folders show a need. Podcast folders are not rescanned (books only).

Matching (after the S26 Dungeon Crawler Carl report, where books exist in several libraries): a `cover-<server item id>.jpg` in a folder names the exact copy it was downloaded from and wins, whatever its library, and it relinks a saved item linked to another copy (same local item, files untouched, local progress kept and re-pointed). Without it, author/title links only a single match in the library selected in the app; anything else is reported as ambiguous and left unlinked (no picker). Left for later: a relinked item keeps the old copy's now-unused local-file record, and local playback sessions not yet synced when a relink happens still name the old copy.

## Downloads follow-ups

See `docs/downloads.md`. Retained: a user Retry and an app restart start unfinished files again from byte 0 (byte-range continuation only happens for the automatic retries inside one service session); keeping staging files across sessions would make Retry a true Resume.

## Library list page loading

`LazyBookshelf` now retries a failed page (`utils/pageRetry.js`, up to 3 times, then on the next scroll) instead of leaving its rows as empty placeholders.

It measures its viewport from `#bookshelf-wrapper`, the element that scrolls. It used to measure `#bookshelf`, which is `h-full`, and so is only as tall as its rows when a page wraps it in an unsized element. The series page has wrapped it in a `<div>` since `15ec49ec` (v0.14.0), so there the height was 0 before the first page loaded. Only the one or two rows at the top edge ever got cards, at any scroll position. A bookshelf left while a page was loading no longer mounts cards afterwards. That is what logged `mount entity card invalid shelf 0/1`, and all bookshelves share the `#shelf-N` ids, so it could have reached the next bookshelf's rows. `tests/bookshelfViewport.test.mjs` runs the real component on Vue with a modelled page to cover both.

Still upstream-shaped and left as is: concurrent page fetches after a reset can write a stale response into the new list (a library or filter change while a page is in flight). The separate token refresh per 401 in `nativeHttp` (several at once raced on a rotating refresh token) is fixed on `plus` (from `fix/auth-refresh-single-flight`), not released yet: one shared renewal per saved server (`docs/authentication.md`).

## Missing local cover crashes playback

**Fixed on `plus` after v0.15.1** (`575c330d`; see "Local artwork crash hardening" in `docs/release-process.md`). A local cover that can't be decoded now falls back to the default artwork through `player/CoverArt.kt`. Kept here for history. Still not checked on a device: a revoked folder grant (simulated in unit tests) and Android Auto browsing.

## Raw local paths in `cleanLocalLibraryItems` logs

Pre-existing, inherited from upstream. Found during the local artwork fix and deliberately left out of it. `DbManager.cleanLocalLibraryItems` runs when the app UI starts and logs with plain `Log.d`, not `DLog`, so its lines skip the sanitizer. They reach raw logcat with a removed local file's absolute path, a removed cover's path and the book title. They don't reach the in-app diagnostic log. A fix would route these lines through `DLog` (or log the item id instead of the path), checked the same way as `docs/device-regression-checklist.md` section 7.

## Widget artwork after a failed load

`WidgetRenderer` caches one loaded cover and remembers one failed cover. A failed cover isn't loaded again in that app process until a different cover loads successfully; a cached hit doesn't clear it. Before the responsive widget (`77297c7e`), every widget update started a new load. The emulator shows this: a cover that failed while offline stays the logo for that process. An S26 case (a downloaded book showing the logo) matched this pattern, but it didn't recur after Rescan Folder, so the widget was not changed. A future fix should retry a failed cover on a later update (for example, after some time or on the next playback change) rather than for the whole process.

## Store-listing branding

A separate release-asset task, not part of code phases. Inherited Audiobookshelf+ identity debt (not Audible-related). Phase 5 rewrote the fastlane listing text (`fastlane/metadata/android/{en-US,de}/`) for Audiobookshelf+, but the imagery (`fastlane/metadata/android/en-US/images/`: the feature graphic, the icon and eight phone screenshots, several in iPhone frames) is still upstream's "audiobookshelf" material with upstream demo content. Replace it with Audiobookshelf+ graphics and current Android screenshots before any public store or release material is prepared. It must also follow the no-Audible rule in `docs/app-identity.md`.

## README screenshots

Phase 5 removed upstream's demo image (official app, iPhone frames) from the README. The fork's own `screenshots/plus/` images predate the current widget and player and are not referenced. Add current Audiobookshelf+ Android screenshots when public material is next prepared, following the no-Audible rule in `docs/app-identity.md`.

## Chapter Track and the second (whole-book) rail

Found while preparing the LLAMA B+ faceplate (Phase 6A.3); not changed. The pair is opt-in: Chapter Track is off by default, and Total Track (on by default) only shows its own rail once Chapter Track is on.

- Turning Chapter Track on while paused leaves the second rail's played bar at 0% until the next playback tick: the toggle updates the rails before the new rail has rendered.
- A book without chapters shows two identical whole-book rails when Chapter Track is on (the second rail's condition does not check for chapters).
- The menu labels ("Total Track", "Chapter Track") do not explain the pair; "Total Track" alone has no visible effect in the default state.

## Wide two-rail overlap at font scale 1.3

On a wide portrait screen (about 762×986) at font scale 1.3 with Chapter Track and Total Track both on, the metadata readout overlaps the artwork by about 12px (and touches the second rail on a 412×734 screen). This is the previous geometry, which the B+ faceplate keeps on purpose for the two-rail state; it was the same before Phase 6B. A fix belongs to that shared geometry, not the faceplate.

## Mini-player clearance at font scale 1.3

Measured during the LLAMA mini-player work (Phases 7A/7B) and left unchanged; the panel is fixed at 120px (LLAMA's collapsed player is 92px since the 2026-10-10 finish: the empty band under its seek row was removed and every module moved down by the same amount, so the spacing below is unchanged). At font scale 1.3 only about 2px separate the mini artwork from the seek row, and the title/author block extends about 1.2px above the panel. The default "Book | Chapter" title already scrolls at 412px. This rules out any inset well or bezel around the mini readout or artwork without a geometry change; a fix belongs to the shared collapsed-player geometry (all themes).

## Browsing chrome at font scale 1.3

Found during the LLAMA browsing work (Phases 8A/8B), in every theme. At font scale 1.3 the app bar's library selector truncates the library name ("Restore T…" at 412px). It comes from the shared layout (a content-sized selector), not from presentation, and is still open.

**Fixed on `plus` (2026-10-10):** in the alternative bookshelf view, book-card titles sat tight against, then over, the bottom of the artwork at 1.3 (a fixed upward title offset). The title block now hangs from the cover and the shelves size their title space by the measured text scale (`utils/textScale.js`), in every theme.

## Reader and podcast formats not tested on a device

The 2026-10-10 LLAMA finish changed the comic, EPUB and MOBI readers' offsets above the collapsed player, the comic and PDF page bars' stacking, and the podcast episode Play legends.

**Tested on the Galaxy S22 for the v0.16.0 release (2026-10-11),** with the paused collapsed player showing, at font scales 1.0 and 1.3, in LLAMA and in Dark:
- **Readers:**
  - generated EPUB 3, MOBI (PalmDOC, uncompressed) and CBZ fixtures, plus a real PDF;
  - real swipes turn the page (EPUB location, comic and PDF page counters);
  - the EPUB and PDF bars sit on the player and are the topmost element;
  - the comic bar hides behind the player and returns onto it on a tap;
  - the MOBI view ends above the player.
- **Podcasts:** a podcast library built from a local folder (two public-domain LibriVox MP3s):
  - the episode and Latest-episode Play pills start playback;
  - an episode downloads and plays from the phone;
  - the legends are amber in LLAMA and green in Dark.

**Still not tested on a device:** comic CBR files, compressed or KF8/AZW3 MOBI files, and real podcast RSS feeds (feed refresh, remote episode downloads).

## Streamed refocus and remote progress

Returning to the app with a paused streamed book compares the server's progress with what this phone wrote (`utils/livePosition.js` `refocusServerAction`):
- **This phone's own write:** the position of its last sync the server acknowledged, a sync sent without an answer yet, or, before any sync, the position the server created the session with. The native side reports these as `serverProgressAck` (`player/ServerProgressAck.kt`, recorded by `MediaProgressSyncer`). The native player's position stands, so a move made while paused is kept and saved by the next sync.
- **Already handled:** a server state an earlier refocus dealt with (same session, server `lastUpdate` not newer). It is kept in the WebView's storage so it survives a recreated UI.
- **Anything else is another device's update** and is applied (seek, still paused), however soon after this phone's last save it came. The next sync continues from it.

No timestamp from this phone is compared with the server's. The earlier 10-second rule (unpublished v0.16.0 candidate) missed remote updates inside that window and let the next sync overwrite them.

**Remaining limits:**
- While an audiobook is playing, refocus does not check the server: progress updates from another device are not applied, and this device's next progress save may overwrite those updates (last writer wins, as upstream: the Play-time server check is commented out in `PlayerListener`). Returning to a paused audiobook applies them, as described above.
- A remote position within 1 second of this phone's last saved position counts as this phone's own.
- Downloaded books follow the separate local-progress rules (`checkSyncServerProgressWithLocalProgress`).

**Verified on the S22 (2026-10-11)** with a server-side write standing in for another device, real Home and relaunch refocus, and media-session fast-forward keys for paused moves:
- the phone's own save is kept, and a paused move is kept and then saved;
- remote updates 3.1 and 15.1 seconds after the phone's save are applied, and the next save continues from them;
- a paused move after an applied remote update is kept;
- on a fresh session before any save, a paused move is kept and a remote update is applied.

## In-app updater: what is not yet proven

The updater (`feature/in-app-github-updater`, docs/in-app-updates.md) was tested on the S22 with a disposable fixture
package and disposable test keys: real downloads, Android's real package parser and real installer confirmations.

**Not yet exercised:**
- **The production signing path end to end:** a production-signed APK verified against the pinned
  `c540046a…b9d4` certificate, then installed over a production-signed app. That happens with the first release that
  contains the updater, when the next release is installed through it.
- **The installer completing a self-update:** the app replacing itself and its process restarting.
- **Instrumentation tests:** the project has no instrumented test setup, so APK parsing and the installer are covered
  by the device runs, not by `androidTest`.

**Play Protect:** on the S22, Google Play Protect sometimes put an "App scan recommended" dialog in front of Android's
installer for the unknown fixture package. When it was dismissed, the installer reported a cancel and the verified
update was kept. "Install without scanning" completed the install. A published Audiobookshelf+ release may see the same
dialog; nothing in the app can or should bypass it.

**A Google Play build** would need a variant without `REQUEST_INSTALL_PACKAGES` and the updater (see
docs/in-app-updates.md, "Google Play separation").

## Widget ANR on display-size changes

Changing the display size (`adb shell wm size`) sends `APPWIDGET_UPDATE_OPTIONS` to the home-screen widget provider, and its handling has produced an "isn't responding" dialog on the emulator. Needs a separate reliability investigation of the widget's options update path.

## Android lint register (Phase 5.1)

`lintDebug` went from 0 errors / 145 warnings to 0 errors / 108 warnings in Phase 5.1. Every remaining warning belongs to one of the families below, each verified and classified: **C** intentional, **D** lint false positive or tool limitation, **E** out of scope until a product, design or dependency decision. None is unexplained, and no lint ID is disabled, baselined or downgraded; the only lint markers in the tree are `tools:keep` for `xml/config` (`res/raw/keep.xml`) and `tools:override` on the two ExoPlayer icon overrides.

| Lint ID | Count | Class | Why it remains | Future action |
| --- | --- | --- | --- | --- |
| `VisibleForTests` | 43 | D | Every call is a public Google Cast SDK API (`CastOptions.Builder`, `MediaStatus`, `RemoteMediaClient.getMediaStatus`, `MediaQueueItem.Builder`) whose shipped classes carry an internal `@VisibleForTesting`; the app has no other way to use Cast. | Re-check after a Cast SDK upgrade. |
| `PrivateResource` | 20 | C | The widget layouts reference `exo_icon_rewind` / `exo_icon_fastforward`, which the app deliberately overrides (`tools:override`): ExoPlayer UI's `exo_notification_rewind/fastforward` are aliases of them, so the override also styles the media notification. | Disappears with a move to app-owned icon names plus a notification icon provider, or with Media3. |
| `GradleDependency` | 11 | E | Newer AndroidX (core-splashscreen, appcompat, constraintlayout, core-ktx, work, media) and ExoPlayer 2.19.1 releases exist. Upgrades can change playback, notification and widget behavior. | A dedicated, device-validated dependency phase (ExoPlayer 2 is end-of-life; Media3 is the successor). |
| `IconDuplicates` | 10 | C | `ic_launcher_round` deliberately uses the same art as `ic_launcher` (main and the shared debug/contributor `devicon` variant). | None. |
| `VectorPath` | 5 | C | Long paths in the brand and category icons (`abs_*`, `icon_monochrome`, `ic_play_speed_3_0x`); simplifying them changes the artwork. | Only with an icon redesign. |
| `NestedWeights` | 4 | E | Standard and LLAMA compact/wide widget layouts. Restructuring changes RemoteViews measurement; widget geometry is protected (Gate F freeze, `WidgetContainmentTest`). | Only inside a widget layout phase with containment and device checks. |
| `UnusedAttribute` | 3 | C | `previewLayout`, `targetCellWidth` and `targetCellHeight` in `media_player_widget_info.xml` apply from API 31 and are ignored below; the file is in the Gate F freeze. | None. |
| `DisableBaselineAlignment` | 2 | E | Compact widget rows; `baselineAligned="false"` can move text vertically. Same protection as `NestedWeights`. | As `NestedWeights`. |
| `UselessParent` | 2 | E | FULL widget layouts; removing the wrapper changes the view tree the renderer and containment tests rely on. | As `NestedWeights`. |
| `ScopedStorage` | 1 | E | `READ_EXTERNAL_STORAGE` without `maxSdkVersion`; `MainActivity` requests it at runtime for local folders and downloads. | A storage-permission review (Android 13+ media permissions, local folder scanning). |
| `VectorRaster` | 1 | C | `icon_monochrome` is drawn larger than 200×200; it is a brand icon. | Only with an icon redesign. |
| `AcceptsUserCertificates` | 1 | C | Self-hosted Audiobookshelf servers often use a private CA; the network security config trusts user certificates on purpose. | None (product requirement). |
| `InsecureBaseConfiguration` | 1 | C | Cleartext is permitted on purpose: many self-hosted servers are reached over plain HTTP on a LAN. | None (product requirement). |
| `ExportedService` | 1 | C | `PlayerNotificationService` is the `MediaBrowserService`, which must be exported for Android Auto and media controllers; `onGetRoot` rejects callers outside `VALID_MEDIA_BROWSERS`. | None. |
| `ObsoleteSdkInt` | 1 | E | `values-v21/styles.xml` is what minSdk 24 devices actually use, and it differs from `values/styles.xml` (for example `AppTheme.NoActionBarLaunch` is `Theme.SplashScreen`-based in `values/` but `AppTheme.NoActionBar`-based in `values-v21/`; the widget container style also differs). Merging would settle which theme is intended. | A launch-theme and widget-style decision; merge the folder with the chosen definitions. |
| `StaticFieldLeak` | 1 | D | `DownloadServiceHost` keeps a `DownloadItemManager` built from `context.applicationContext`, which lives as long as the process. | None. |
| `IconLocation` | 1 | C | `drawable/icon.png` is densityless on purpose: it is the placeholder and fallback cover art, the download notification icon and the widget's default art. Moving it into a density folder would rescale it everywhere. | Only with an icon redesign. |
