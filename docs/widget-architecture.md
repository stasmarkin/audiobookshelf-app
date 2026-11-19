# Home-screen widget: architecture (Phase 2B)

One responsive widget on the existing provider (Gate 3, option A). Placed widgets upgrade in place. Dark, Black and Light keep the existing widget look; LLAMA has its own paint-only presentation.

## Pieces

| Piece                                                                                        | What it is                                                                                                                                                                                                                     |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `MediaPlayerWidget.kt`                                                                       | The unchanged provider/receiver identity. `onUpdate`, `onAppWidgetOptionsChanged` and `onEnabled` call `WidgetRenderer.renderAll()`. `updateAppWidget()` (called by the widget updater) forwards to `WidgetRenderer.update()`. |
| `widget/WidgetRenderer.kt`                                                                   | Builds the `RemoteViews` for every size, sets the existing actions, loads artwork off the main thread and redraws once it's ready. Reads state only.                                                                           |
| `widget/WidgetSize.kt`                                                                       | Size buckets: **COMPACT** (< 320dp wide, or short), **WIDE** (≥ 320dp wide, < 150dp high), **FULL** (≥ 250dp wide and ≥ 150dp high). Also the ideal sizes for Android 12+ responsive layouts.                                  |
| `widget/WidgetTheme.kt`                                                                      | Fixed allow-list: the saved theme id `llama` → LLAMA; anything else, missing, malformed or of the wrong type → STANDARD.                                                                                                       |
| `widget/FullArtwork.kt`                                                                      | FULL presentation (side-by-side or expanded) and cover bounds from the reported widget size.                                                                                                                                   |
| `widget/WidgetText.kt`                                                                       | Snapshot formatting: elapsed, `-remaining` and a 0–1000 progress value, or nothing when unknown.                                                                                                                               |
| `res/layout/media_player_widget{,_wide,_full,_full_expanded,_full_expanded_large}.xml`       | Standard layouts. COMPACT is the pre-existing layout (only view ids added). `_full_expanded` is FULL stacked (cover on top); `_full_expanded_large` is the same with larger readout text and controls. Same views throughout.  |
| `res/layout/media_player_widget{,_wide,_full,_full_expanded,_full_expanded_large}_llama.xml` | LLAMA variants with exactly the same view ids and actions; paint only.                                                                                                                                                         |
| `res/values/widget_theme_colors.xml`                                                         | **Generated** by `scripts/generate-widget-theme.js` from `theme/builtins.js` (equipment-finish themes only). Do not edit by hand.                                                                                              |
| `res/drawable/widget_llama_*.xml`, `res/color/widget_llama_edge_light_*`                     | Original XML resources: steel housing, recessed readout, socketed steel keys with amber legends (the bevel inverts when pressed), steel artwork lip, amber progress. Bevel opacities match the app's bevels. The steel key colours (`key_lit`, `key_face`, `key_shade`, `key_pressed_shade`, `raised`, `content`, `legend`) are generated with the palette. |
| `res/xml/media_player_widget_info.xml`                                                       | Only change: `resizeMode` gained `vertical` (now horizontal and vertical). Size, target cells, preview and the Samsung `sub_screen` metadata are unchanged.                                                                    |

## Rendering

- **Android 12+ (API 31+):** one responsive `RemoteViews(Map<SizeF, RemoteViews>)`. The launcher picks the layout for the current size, including while resizing.
- **API 24–30:** the layout is chosen from `OPTION_APPWIDGET_*` (portrait: min width × max height; landscape: max width × min height) and redrawn in `onAppWidgetOptionsChanged`. **Not exercised on a device yet** (only the classification is unit-tested).
- **When it draws:** the existing updater events (play/pause, session prepared, player closed or destroyed), plus launcher `onUpdate`, size changes and theme changes. With no state in the process (e.g. the launcher started it), it draws the last saved session as not playing.
- **onUpdate** now renders instead of only logging. This is needed so placed widgets pick up the responsive layouts after an upgrade, and it also fixes the old placeholder `Artist`/`Title` after a launcher re-inflate. It is render-only.
- The existing daily `updatePeriodMillis` is unchanged; nothing new is scheduled (no alarms, jobs, handlers or timers).

## Actions

Unchanged: play/pause, rewind (jump backward) and fast-forward (jump forward) through `MediaButtonReceiver.buildMediaButtonPendingIntent`, and tapping the widget opens `MainActivity` (`FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`). Every size and theme uses the same four intents. The play/pause restoration path after process death is untouched.

## Progress and time (FULL only)

A **snapshot**: the progress bar, elapsed and remaining time are exactly what they were at the last widget update. They do not advance while playing. Elapsed and remaining are listening time at the playback speed, as in the app (at 2×, an hour of the book reads 30:00); the bar is the share of the book. Without a live player the widget uses the speed the user last chose. See [speed-aware time](speed-aware-time.md).

A `Chronometer` was considered and rejected: it counts wall-clock time at 1×, so it drifts at other playback speeds and after seeks, and seeks and speed changes don't trigger a widget update. No periodic updates were added (per Gate 3). A live time would need widget refresh calls on seek changes inside playback code, which needs separate authorization. A speed change does redraw the widget (`PlayerNotificationService.setPlaybackSpeed`), so the snapshot is never drawn at a stale speed.

## Theme

- **Source of truth:** the saved built-in theme id (Capacitor Preferences key `theme`, SharedPreferences `CapacitorStorage`), read natively. It works when the app process is dead.
- **Allow-list only:** the id selects one of two repository-owned layout sets. No colors, styles, paths, drawable names or resource ids cross from preference data into native code.
- **Refresh bridge:** `AbsDatabase.refreshWidgets()` takes no arguments and only redraws. `ThemeService.select()` calls it after persisting, native only, errors logged and ignored. Startup restore doesn't call it.
- **RICE (branch `feature/rice-customization`):**
  - Once the user has used RICE, the committed RICE store (`appearance/AppearanceSnapshotReader.kt`, read-only, verified by SHA-256) decides instead of the `theme` key.
  - A RICE preset or starter on Android 12+ keeps the standard layouts; `WidgetPaint.kt` tints them (container, panels, text, key icons, FULL progress) after `WidgetRenderer.build`.
  - LLAMA-family RICE copies keep the LLAMA layouts as they are, and Android 11 and older keep the standard look.
  - Device mode re-reads the public system palette at each widget update.
  - Details: `docs/rice-customization.md`.
- **Parity:** `tests/widget-theme.test.mjs` fails if the generated XML is stale, if its colors differ from the tokens, if the Kotlin allow-list names an id that isn't a built-in with a generated palette, or if a LLAMA layout's view ids differ from its standard layout.

## Placed-widget compatibility

- The provider class, receiver and `android.appwidget.provider` resource are unchanged, so existing instances survive the upgrade (verified on the emulator: same widget id, redrawn without opening the app).
- Only `resizeMode` gained `vertical`. Existing instances keep their size until resized.
- The Samsung `sub_screen` (Z Flip cover screen) metadata is preserved but **untested**: there is no cover-screen device available.

## RemoteViews notes

- LLAMA layouts use `android:tint` (platform `ImageView`); `app:tint` needs AppCompat and does nothing in `RemoteViews`, so lint's `UseAppTint` is suppressed there.
- Artwork in LLAMA uses `cropToPadding` so the cover never paints over its frame, and an 8dp content inset clears the launcher's corner radius.
- `progressDrawable` can't be set at runtime before API 31, which is one reason LLAMA uses separate layouts instead of runtime restyling.
- **Responsive FULL artwork (Phase 2C).** On the S26 Ultra, very tall One UI resizes made the FULL artwork grow with the row height, squeeze the metadata to nothing, and finally crop into the cover. A fixed 140dp cap fixed that, then responsive bounds let the cover grow beside a protected readout, but the S26 retest wanted a much larger cover at tall sizes. FULL now has two presentations with the same views, ids and actions, chosen by `widget/FullArtwork.plan()` from the widget's reported size:

  - **NORMAL** (`media_player_widget_full*.xml`): cover beside the readout. Cover max width = width − 48dp (padding, readout margin, and the 22dp the brand icon used to take; kept so the cover and the NORMAL/EXPANDED switch did not move when the icon started floating, so the readout now gets it) − a protected readout width (~156dp: the time row's two halves each fit `-12:34:56` at 13sp monospace, plus padding); max height = height − 87dp (padding, progress, controls).
  - **EXPANDED** (`media_player_widget_full_expanded*.xml`): cover on top in the flexible area, horizontally centered and resting on the readout (once the cover reaches its width limit, spare height goes above it, so cover, readout, progress and controls stay together at the bottom); readout below at the full content width; then progress and controls. Cover max width = width − 16dp; max height = height − 87dp − 8dp gap − a one-line readout (the layout itself limits a two-line one).
  - **Choice:** EXPANDED when the square cover it guarantees with a two-line title (min(width − 16, height − 87 − 8 − ~101)) is at least 1.2× and 24dp larger than NORMAL's. Normal two-row sizes always stay NORMAL; the choice depends only on the size (not the cover), and once a widget is tall enough to expand, taller sizes stay expanded. At 360dp wide it switches at ~384dp tall; at 291dp wide at ~307dp; at 496dp wide at ~547dp.
  - **LARGE** (`media_player_widget_full_expanded_large*.xml`, Phase 2C accessibility pass, refined after the S26 review): EXPANDED for very tall widgets with title 22sp and author/times 18sp (EXPANDED: 16/13/13sp), a 6dp + 80dp controls row holding 72dp buttons with 40dp glyphs (16dp inset; EXPANDED: 44dp buttons, 24dp glyphs), a readout across the full content width, and the brand icon floating at the top-end corner (see the brand icon below). Everything else, including structure, paint, ids, actions and the bottom-resting cover, is identical to EXPANDED (a test reverts the sizes and compares the files). Chosen when height ≥ width + 259dp (360dp wide: 619dp tall; 496dp wide: 755dp), plus the extra readout height of a larger font scale. With a two-line title a full-width cover needs width + 237dp, so at the threshold 22dp remain above the cover, more than the 18dp the floating icon reaches into the content: LARGE never makes the cover smaller and the icon never touches it. Shorter sizes keep EXPANDED sizing.

- **Brand icon (final widget geometry pass).** The Audiobookshelf icon never takes layout space and never touches content; its position depends on the presentation:

  - **COMPACT / WIDE:** the reserved icon column (std 36dp, LLAMA 33dp / 30dp) is gone. Title and author use that width; the buttons keep exactly their old width (a `FrameLayout` controls row: the button container fills it with an end margin equal to the former column, and the icon sits centered in that margin). In COMPACT the readout gets the former column as a fixed base width plus its weight, so the weighted cover keeps its exact width. In the standard layouts the title and author also get an 8dp end margin mirroring their 8dp start inset (the column used to keep them off the widget's rounded edge); LLAMA's text already sits inside its panel padding.
  - **FULL (NORMAL / EXPANDED / LARGE):** the icon (`tinyCornerIcon`) floats as the last child of a `FrameLayout` around `widgetContent`, top-end at `@dimen/widget_floating_icon_inset` (10dp); NORMAL also has a slot in the readout panel's top-end corner (`tinyCornerIconPanel`, `@dimen/widget_panel_icon_inset`, 4dp). The readout takes the width the icon used to take. `FullArtwork.plan()` decides where the icon shows (`Plan.icon`, applied by the renderer with `setViewVisibility`): the corner when it clears the cover and the row (worst cases: square cover, one-line title for EXPANDED, two-line title and LLAMA's progress bar for NORMAL), else in NORMAL the panel slot when the readout's text starts below it, else hidden. LARGE always shows the corner icon (its threshold guarantees the clearance). Hidden cases: narrow two-row NORMAL (e.g. 291×174dp) and a band of EXPANDED sizes where a near-full cover fills the top corner (360dp wide: ~482–535dp tall). Until the launcher reports a size, NORMAL shows no icon.

  Both keep the cover `wrap_content` + `adjustViewBounds` + `fitCenter`, so it keeps its aspect, is never cropped and never overlaps the readout; LLAMA's 2dp frame is inside the view, so it hugs the cover. The size used is the narrowest reported width and the tallest reported height (`OPTION_APPWIDGET_SIZES` on Android 12+, otherwise the min/max options); on Android 12+ every FULL slot of the responsive map is built with the chosen presentation, and API 24–30 build it directly, so both redraw on `onAppWidgetOptionsChanged`. `ImageView.setMaxWidth`/`setMaxHeight` are RemoteViews-callable on every supported API (tested on API 24–35). They don't request a layout, and launchers reapply an update to their existing views after laying out the new size, so on its own a widget that grew would keep its old bounds; `WidgetRenderer.applyArtworkBounds` also hides and re-shows the cover in the same update, which forces the layout (tested on API 24–35). Until the launcher reports a size, FULL is NORMAL with `@dimen/widget_full_artwork_max` (140dp). Loaded covers get a fixed bitmap density (`WidgetRenderer.widgetArtwork`) giving the 300px bitmap a 1200dp intrinsic size on every phone, so the bounds, not the bitmap, decide the displayed size (the direct-resource placeholder is 162dp). WIDE and COMPACT size their artwork from the row height and are unaffected.

## Emulator validation (AOSP API 35, Pixel launcher)

| Check                                                                   | Result                                                                                             |
| ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Place widget with the baseline build (c7a617bc), upgrade to this branch | Same widget id and provider; redrawn after install without opening the app                         |
| COMPACT / WIDE / FULL                                                   | All three render; resize switches layouts; resize redraws from the last state                      |
| Dark / Black / Light / LLAMA                                            | Standard look identical for all three; LLAMA applied immediately on selection in Settings (bridge) |
| Invalid saved value (`@drawable/widget_llama_chassis`) + refresh        | Standard look                                                                                      |
| Artwork present / absent, long title and author                         | Cover or app icon; text ellipsizes in all sizes                                                    |
| Play/pause, jump forward, jump backward, tap to open                    | All work through the existing paths                                                                |
| Process killed, then widget Play                                        | Resumes at the saved position                                                                      |
| Snapshot while playing                                                  | Unchanged after 40 s of playback; the package has no alarms or scheduled jobs                      |

Observed, not changed (pre-existing, outside the widget): after a restart the widget shows `deviceData.lastPlaybackSession`, while widget Play resumes the session chosen by the restoration store; on the test device these were different books. The baseline build shows the same session.

## LLAMA widget polish and text containment (Phase 2C Gate F)

LLAMA layouts and drawables only. Standard layouts and drawables, `WidgetSize`, `FullArtwork`, the renderer, the provider and its info XML are unchanged. Hashes pin them in `tests/widget-theme.test.mjs`, and Robolectric renders 434 standard-theme cases identically before and after.

**Shared clipping limitation (found, not fixed).** Text can clip in every theme, because of how space is allocated:

- **FULL NORMAL:** the readout's height follows the cover row. At narrow widths (≤~300dp) the cover is limited by the width to ~86dp, so the readout is ~86dp at every height, while a two-line title, author and time row need ~101dp.
- **COMPACT / WIDE:** two text lines share half of a short row.
- **Font scale:** larger scales widen both problems.

The originally reported cases (LLAMA ~291×174dp FULL NORMAL with a two-line title, time row ~2dp; LLAMA COMPACT title) are instances of this. A complete fix needs responsive-layout changes (see `docs/future-work.md`). Gate F mitigates LLAMA only.

**LLAMA mitigation (exact values):**

- **COMPACT:** title 13sp, author 12sp, `includeFontPadding=false`.
- **WIDE:** `includeFontPadding=false` on title and author (sizes unchanged).
- **FULL NORMAL:**

  - readout `paddingVertical` 8→4dp (horizontal stays 8dp, the width reserve);
  - author `layout_marginTop` 2→0dp;
  - time row `layout_marginTop` 8→4dp;
  - `includeFontPadding=false` on title, author and both times.

  `FullArtwork`'s readout constants are a reserve; the LLAMA NORMAL readout now uses less than they model, so cover and icon planning stay conservative.

- **EXPANDED / LARGE:** spacing unchanged (they never clipped).

Measured clipping boundaries with a long two-line title (Robolectric, real font metrics, 420dpi):

| LLAMA                   | Before (font 1.0)                        | After (font 1.0)            | Before (font 1.3)     | After (font 1.3)                                       |
| ----------------------- | ---------------------------------------- | --------------------------- | --------------------- | ------------------------------------------------------ |
| FULL NORMAL 291×174     | time row 1.9dp                           | contained                   | clipped               | clipped                                                |
| FULL NORMAL ≥291dp wide | clipped ≤186dp tall, every height at 291 | clipped only 150–168dp tall | clipped at most sizes | ≥360 wide contained from 192dp; narrower still clipped |
| FULL NORMAL ~250dp wide | clipped every height                     | clipped every height        | clipped               | clipped                                                |
| COMPACT                 | clipped ≤92dp tall                       | clipped ≤72dp tall          | clipped ≤112dp        | clipped ≤88dp                                          |
| WIDE                    | clipped ≤88dp tall                       | clipped ≤80dp tall          | clipped ≤108dp        | clipped ≤96dp                                          |

On the emulator's Pixel launcher, all of these fit: one row (360×104dp, WIDE), two rows (360×224dp, FULL NORMAL, also at font 1.3) and EXPANDED (to 584dp).

**Paint:**

- **Time readouts:** the accent green in every FULL presentation (they were text/muted). With the play glyph neutral there is one accent zone, the readout display, and the bold white title still leads. On the near-black well: 12.3:1.
- **Play glyph:** the neutral text color like the jump glyphs (was the accent). It has 6.4:1 on the steel face, against 5.0:1 for the accent. Play/pause reads from the glyph's shape, and the accent stays reserved for readouts.
- **Radii:** steel keys 8→4dp and the readout well 6→4dp (the app's key/well radius). The chassis keeps the launcher's widget radius, the artwork frame stays square, and the progress bar stays 3dp.
- **Unchanged:** artwork, frame, chassis, progress and icon placement.

**Tests:**

- `WidgetContainmentTest` (Robolectric, native graphics) lays out the real RemoteViews and asserts:

  - the LLAMA sizes that now fit (291×174; FULL NORMAL 291–400dp wide from 174dp tall, and from 192dp at font 1.3 for ≥360dp; COMPACT from 76dp, or 92dp at 1.3; WIDE from 88dp, or 104dp at 1.3; EXPANDED and LARGE);
  - unchanged cover/control/play geometry in both themes;
  - an icon that takes no layout space and never overlaps content.

  Known-limit sizes are documented, not asserted. A mutation run against the pre-Gate-F layouts fails the containment tests and passes the geometry ones.

- It needs `testOptions.unitTests.includeAndroidResources = true` to inflate app layouts. That is a unit-test-only option: debug and release APKs built with and without it are identical entry for entry.

## LLAMA control-finish parity (Phase 4D)

Resource-level paint only, LLAMA only. The accepted Phase 4B player has dark secondary keys with amber playback legends and a lighter silver Play/Pause. The widget keeps the same hierarchy:

- **Secondary keys (rewind, fast-forward):** `widget_llama_button` is now a dark key. Its face is deeper than the chassis (`surface.recessed` at 85% over `surface.base`, the collapsed player's depth), with a soft sheen (lit top, shaded bottom), the same lit top/left and dark bottom/right edges as before, and a cut-in face while pressed (the bevel inverts and the shade falls from the top). The legend tint is `widget_llama_played`, the playback amber.
- **Primary key (Play/Pause):** (superseded by Phase 4H below) `widget_llama_button_primary` is the earlier lighter steel key, byte-for-byte except its comment, and its glyph stays near-white. It is still the lightest key, as the silver Play/Pause is in the app. The renderer is unchanged: it still only swaps the play/pause glyph resource, so no key paint is set from Kotlin.
- **Generated colors:** the face, lit top, shaded bottom and pressed shade are `widget_llama_key_face`, `_key_lit`, `_key_shade` and `_key_pressed_shade`. They come from `scripts/generate-widget-theme.js` (`keyColors`) with fixed blend factors, so they trace to the theme tokens like every other widget color, and the stale-file test covers them.
- **Geometry:** both key drawables have identical items, offsets and 4dp corners, and the layouts changed only the `android:background` of Play/Pause and the `android:tint` of rewind and fast-forward (three attribute lines per layout). Across 19 Robolectric cases (COMPACT, WIDE, FULL, EXPANDED, LARGE, font scale 1.3, Standard) every bound and text-fit field is identical before and after, every LLAMA pixel change lies inside the three key boxes, and the Standard renders are pixel-identical.
- **Unavailable state:** the widget has none. The three keys are always enabled, and the renderer hides the controls as a whole when there is nothing to control, so there is no disabled paint to maintain.

Measured contrast (generated colors, confirmed on rendered pixels): amber on the resting key 9.8–11.5:1, on the pressed key 11.1–11.8:1; the near-white Play/Pause glyph 6.4:1 on its key (9.7:1 pressed); the secondary key face against the chassis 1.04:1 at the lit top and 1.19:1 at the face; the primary face against the secondary face 2.4:1. `tests/widget-theme.test.mjs` keeps the contract: generated colors, the legend split, the drawable structure, frozen key geometry attributes, and untouched Standard resources.

A held touch on a launcher widget starts the launcher's own long-press (drag) mode, so the pressed state is verified from the Robolectric pressed renders, not the emulator launcher.

## LLAMA primary key parity (Phase 4H)

Resource-level paint only, LLAMA only. The app's Play/Pause became a dark squared primary key with an amber legend (`docs/theme-architecture.md`, Phase 4H), and the widget's center key follows it:

- **Dark primary face:** `widget_llama_button_primary` uses the secondary key's dark sheen face (`widget_llama_key_lit` → `_key_face` → `_key_shade`). It no longer uses the lighter steel (`widget_llama_raised` / `_content`).
- **Primary bezel:** from the outside in, a lit return ring (`edge_light_35`), a 1dp dark channel (`edge_dark`), the lit top/left (`edge_light_55`) and dark bottom/right inner edge, then the face, inset 3dp. The secondary key has no ring and a 1dp bevel. Pressed keeps the ring and channel, inverts the inner edge (dark top/left, lit bottom/right) and cuts the face in (`_key_pressed_shade` falling from the top). All corners stay 4dp.
- **Amber glyph:** Play/Pause is tinted `widget_llama_played`, like the jump keys. The existing `ic_media_play_dark` / `ic_media_pause_dark` bitmaps, their padding and the renderer are unchanged.
- **Primary status:** central position, the ring and channel, and the solid amber glyph. The three keys keep their equal widths, as before. The drawable has no drop shadow: a widget background cannot paint outside the view, and the key bounds are frozen.
- **Geometry:** layer insets only paint inside the view. Every key's size, margin and padding comes from the layout, and the only layout change is the Play/Pause `android:tint`, one line in each of the five LLAMA layouts. Across 40 Robolectric renders (LLAMA and Standard; COMPACT, WIDE, FULL, EXPANDED, LARGE; paused, playing and pressed) every bound and text-fit field is identical to `5a18d5d2`, and all 20 Standard renders are pixel-identical. `WidgetContainmentTest`: 8/8.

Measured contrast of the amber glyph against the face in its own rows (rendered): 10.6–10.7:1 resting and 11.3–11.4:1 pressed. From the generated colors, the worst face point (the lit top, which the glyph does not reach) is 9.75:1. Before, the near-white glyph on the steel key was 6.4:1. Glyph ink is 7.6×9.5dp (Play, COMPACT) to 19.4×22.9dp (Pause, LARGE), unchanged.

After this change no widget resource used `widget_llama_raised` or `widget_llama_content` (lint reported two `UnusedResources` warnings, 143 → 145). Phase 5.1 removed both from the generator (`TOKEN_COLORS`), and `tests/widget-theme.test.mjs` now requires every generated color to be used by a widget drawable or layout.

`tests/widget-theme.test.mjs` keeps the contract: the exact layer structure in both states, the dark face, the amber tint on all three transport keys, 7:1 on every generated face color, the unchanged bitmaps and padding, and untouched Standard resources.

## Galaxy S26 Ultra findings (One UI, first real-device pass)

The CI-signed debug build installed alongside production. Player, item page, mini-player, Up Next and normal widget sizes were accepted. Two corrections followed (Phase 2C): the FULL widget's artwork at very tall resizes (above; first a fixed cap, then responsive bounds after the S26 retest preferred the large cover), and a LLAMA-only recessed treatment for the Chapters list. Extreme resizes are only approximated on the emulator; the S26 retest is the real check.

## One UI widget checklist (run on the Galaxy S26 Ultra)

This checklist was first written for a Galaxy S22 Ultra, which is no longer the test device; run it on the S26 Ultra, the current device.

1. Install the current `plus` build and place the widget at its default size. Install this branch's build over it: the widget must stay, keep its size and show the last book without opening the app.
2. Resize through the One UI grid: 3×1 or narrowest allowed (COMPACT), 4×1 (WIDE), 4×2 (FULL). Check text isn't clipped at each size.
3. Settings → Theme: Dark, Black, Light (widget unchanged), then LLAMA (widget switches promptly). Check the One UI corner radius against the LLAMA chassis and artwork frame, in light and dark system mode.
4. Actions in every size: play/pause, jump backward, jump forward, tap to open.
5. Pause, swipe the app away, wait, then press Play on the widget: playback resumes at the saved position.
6. Reboot: the widget shows the last book (not placeholder text) in the saved theme.
7. FULL while playing: time and bar stay at the snapshot (expected), and update on pause/play.
8. Lock-screen / keyguard behavior, if One UI offers the widget there.
9. Battery: no new wakeups attributable to the widget over a listening session.
10. Z Flip cover screen: not testable on the S26 Ultra.
