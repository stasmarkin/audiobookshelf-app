# RICE customization

Settings → Appearance. Status: merged into `plus` (2026-10-12), not in any release yet.

RICE lets the user customize colors and a few bounded style options, choose established starter palettes, follow the device's colors, and import palettes from public Linux theme projects. It extends the semantic theme engine (`docs/theme-architecture.md`); it doesn't replace it. Dark, Black, Light and LLAMA are unchanged and remain the only built-in themes.

## Behavior

**Enable RICE customization** is off by default and the Theme picker in Settings stays as it was.

- **Off:** built-in behavior exactly as before. Saved presets are kept.
- **On:** reveals Built-in themes, Starter themes, Follow device, My presets, Import theme colors, Restore defaults and the starter credits. Turning it on recolors nothing until an appearance is chosen.
- **Turning it off:** returns to the last built-in, removes every RICE trace (style node, `data-rice`, native background, widget colors) and remembers the RICE choice. **Use last custom appearance** brings that choice back.
- **Choosing a built-in while RICE is on:** applies the built-in unchanged.

| Choice | What it is |
|---|---|
| Built-in theme | Dark, Black, Light, LLAMA, unchanged; each can be copied ("Customize a copy") |
| Starter theme | One of ten read-only palette adaptations (below); editing makes a personal copy |
| Device colors and light/dark | Android 12+ system palette + current light/dark; re-resolved when the device changes |
| Device colors, always dark | The system palette, always as a dark appearance |
| My presets | Up to 32 personal snapshots: apply, edit, rename, duplicate, delete |
| Import theme colors | Palette files from public GitHub projects (below), opened in the editor before saving |

**Editor.**
- **Colors:** background, surfaces, text, secondary text, accent, progress. More options: selection, borders, progress track/buffered.
- **Style:** interface font (system / bundled Source Sans), time and number font (system / monospace / bundled Ubuntu Mono), weight 400/600, text size 100/110/120%, corners (app default / 0 / 8 / 12 px), cover frame (none / inset line), button outline 0–2 px, button shadow (app default / soft), button finish (flat / bevel).
- **Values:** hex fields accept `#RRGGBB`. Invalid text stays visible with an error and is never applied.
- **Preview:** changes appear only in the preview on the page, never in the app.
- **Resets:** Reset per color, Reset style options, and Restore starting appearance.
- **Saving:** Save and apply, Save (keeps the current appearance), or Cancel. Leaving the page discards the draft.
- **Contrast:** must pass to save; "Adjust colors for readability" repairs failing foreground values and lists each change.
- **LLAMA copies** keep LLAMA's recipe: only colors can change.

No CSS editor, imported fonts or images, layout switch, navigation or player changes, or volume control.

## Architecture

| Path | Role |
|---|---|
| `theme/rice/schema.js` | Strict validation of snapshots, presets, selections, the store envelope and source records |
| `theme/rice/strictJson.js` | Bounded JSON parser: rejects duplicate keys, `__proto__`/`prototype`/`constructor`, over-size/depth/count input |
| `theme/rice/color.js`, `contrast.js` | WCAG 2.2 luminance/contrast, the required pair graph, deterministic repair |
| `theme/rice/mapping.js` | Source roles → complete 29-token snapshot; device tones → snapshot; built-in copies; widget colors |
| `theme/rice/catalog.js` | The ten starters (static, pinned, attributed) |
| `theme/rice/runtime.js` | The only place a snapshot becomes CSS: one `<style id="abs-rice-style">`, plus the scoped preview stylesheet |
| `theme/rice/store.js` | Two-slot persistence and the pure preset operations |
| `theme/rice/controller.js` | Resolves the selection, applies it, commits every change as one store transaction |
| `theme/rice/import/parsers.js` | The four palette grammars |
| `plugins/rice.client.js` | Attaches the controller to `$theme` (after `theme.client.js`) |
| `pages/appearance.vue`, `components/appearance/*` | Settings → Appearance, editor, importer, preview |
| `assets/rice-fonts.css` | `AbsRiceSourceSans` / `AbsRiceUbuntuMono` aliases for the already bundled fonts (correct `truetype` hints), used only under the RICE marker |
| Native: `appearance/*`, `plugins/AbsAppearance.kt`, `widget/WidgetTheme.kt` + `WidgetPaint.kt`, `themeimport/*`, `plugins/AbsThemeImport.kt` | Device palette, native background, widget colors, GitHub import |

**`$theme` compatibility.**
- `select(id)`, `apply(id)`, `id` and `themes` keep their meaning.
- While a RICE appearance is active, `theme` returns a validated theme object (`id: 'rice-custom'`, `presentation.cover-color: 'theme'`) and `id` returns its approved base.
- Every edit swaps that object, so consumers (cover presentation, player chrome, reader shell) update without a remount.

**CSS.**
- `html[data-theme]` stays one of the four bases, so their recipes keep applying.
- The RICE stylesheet overrides only the token variables (and, for LLAMA copies, the derived equipment variables) under `html[data-theme][data-rice='active']`, plus the fixed recipes for the chosen style options.
- No snapshot field reaches a selector, property name, URL or font family string.

## Preset schema

Full snapshots, never live inheritance.
- **Snapshot:** `{ baseId, colorScheme, tokens, appearance }`.
  - `baseId` is one of the four built-ins (the CSS anchor), and it must match the scheme: `light` ⇔ light.
  - E-ink is a built-in but not a base: its paint-only finish isn't a custom option, so a copy of E-ink keeps its colors on the `light` base.
  - `tokens` holds exactly the 29 tokens of `theme/tokens.js`:
    - colors are `[r, g, b]` integers;
    - overlays are solid colors only (no gradients or alpha in custom data);
    - `presentation.finish` is `standard` (`equipment` only for LLAMA copies), and `presentation.cover-color` is always `theme`.
  - `appearance` holds the nine enums above; LLAMA copies keep the defaults.
- **Preset:** `{ format: 'absplus-rice-preset', schemaVersion: 1, tokenSchemaVersion: 1, id: 'p-' + 24 hex, name (1–48 code points, plain text), createdAt, updatedAt, snapshot, reset, source }`.
  - `reset` is the starting snapshot.
  - `source` is one of:
    - `builtin` (id);
    - `starter` (id);
    - `device` (mode);
    - `manual`;
    - `github`: canonical project URL, owner, repo, 40-hex commit, path, SHA-256, parser id, up to 24 original colors, mapping version, attribution, license label, optional license URL, up to 29 adjustments.
  - `image` is reserved for a later version and rejected today.
- **Store envelope:** `{ format: 'absplus-rice-store', schemaVersion: 1, revision, riceEnabled, lastBuiltin, selection, rememberedRiceSelection, presets[≤32], widget }`.
  - `widget` is the small native projection: revision, selection kind, device mode, family `standard|llama`, and seven colors.
- Presets contain appearance data only: no credentials, server, account or playback data.

**Storage.**
- **Keys:** Capacitor Preferences `rice.v1.slotA`, `rice.v1.slotB` and `rice.v1.head` (the existing CapacitorStorage group).
- **Commit:** validate → write the inactive slot → read it back → write the head (`current` + `previous`, each `{slot, revision, sha256}`) → read it back → mirror `lastBuiltin` to the legacy `theme` key.
  - The head is the logical commit point: an interrupted slot write is never active.
  - A detected write or read-back failure never reports success.
- **Load:** current slot, then the referenced previous slot, then the validated legacy built-in.
  - Data from a newer schema version is never overwritten.
  - Nothing is written until the user first acts on the Appearance page; that commit migrates the legacy built-in with RICE off.
- **Durability boundary:** Android Preferences writes with `SharedPreferences.Editor.apply()`, which doesn't acknowledge the disk write, so sudden power loss can lose the latest save. Uninstalling or clearing app data removes presets, and there is no cloud sync.

## Starter themes

Read-only palette adaptations, shown only with RICE on. Five source anchors per starter (background, raised surface, text, accent, progress) were read from the pinned file; the rest comes from the mapper, and every contrast correction is recorded in the catalog. `tests/fixtures/rice-catalog.sha256` pins the resulting snapshots.

| Starter | Source (commit) | License |
|---|---|---|
| Catppuccin Mocha, Latte | catppuccin/palette `07d02aa1` `palette.json` | MIT |
| Dracula (open-source palette, not Dracula PRO) | dracula/dracula-theme `e22ebcc0` `README.md` | MIT |
| Nord | nordtheme/nord `1cef7160` `src/nord.scss` | MIT |
| Gruvbox Dark (medium) | morhetz/gruvbox `ef8864bb` `colors/gruvbox.vim` | MIT/X11 declared in README (no root license file) |
| Tokyo Night (night) | folke/tokyonight.nvim `cdc07ac7` `extras/kitty/tokyonight_night.conf` | Apache-2.0 repository; the exported Kitty file declares MIT |
| Rosé Pine (main) | rose-pine/rose-pine-palette `92af52b4` `dist/css/rose-pine.css` | MIT |
| Solarized Dark, Light | altercation/solarized `62f656a0` `README.md` | MIT |
| Everforest Dark (medium) | sainnhe/everforest `85a86eb6` `autoload/everforest.vim` | MIT |

- **Notice texts** ship in `static/licenses/rice/`, and the Appearance page shows them in "Starter palette sources".
- **Not included:** no fonts, logos or screenshots, and no theme script is ever run.
- **Notable corrections:**
  - Solarized base0 text (`#839496` → `#A8B2B3` on Dark, `#657B83` → `#586C73` on Light);
  - Nord and Everforest text slightly lightened;
  - Catppuccin Latte muted text, border and progress darkened;
  - progress tracks adjusted so the played endpoint reaches 3:1.

## Accessibility

- **Pairs and thresholds:** custom appearances must pass WCAG 2.2 thresholds (text 4.5:1, essential non-text 3:1), never rounded, on a fixed pair graph:
  - text on every surface and solid overlay;
  - secondary text, borders, accent and progress (played on track and base);
  - fixed white labels on success and destructive buttons;
  - status indicators;
  - the player's white/black glyphs.
- **High contrast:** an Android 14+ contrast request above 0 raises targets to 7:1 / 4.5:1 for device colors.
- **Repairs:** blends toward black/white with the least change; backgrounds are never moved.
- **Scope:** the built-in themes are never "corrected".
- **New UI:** controls are labeled for TalkBack, swatches announce role and hex value, and selected states use a check icon and text, not color alone. Hex fields and URLs are LTR in RTL layouts, and names render as plain text (`dir="auto"`, no `v-html`).
- **Text size:** the app scale applies only to text utility classes (never icons or layout), on top of the system font size, never twice.

## Samsung One UI and Android device colors

Public Android APIs only:
- `Configuration.uiMode` for light/dark;
- `android.R.color.system_accent1..3_*` / `system_neutral1..2_*` (Android 12+) for the system tonal palette, which One UI's Color palette also publishes;
- `UiModeManager.getContrast()` (Android 14+).

No wallpaper bitmap, Samsung settings, Theme Park or Galaxy Themes data, private APIs, root or Knox.

- **Mapping (dark / light):**
  - base `neutral1_900` / `neutral1_10`;
  - raised `neutral1_800` / `neutral1_50`;
  - selection `neutral2_700` / `neutral2_100`;
  - text `neutral1_50` / `neutral1_900`;
  - muted `neutral2_200` / `neutral2_700`;
  - accent and progress `accent1_200` / `accent1_600`;
  - border `neutral2_400` / `neutral2_500`.

  Then the same contrast repair as above. `tests/fixtures/rice-device-mapping.json` checks that the JS mapper and `DevicePaletteMapper.kt` give identical widget colors.
- **Events:** `AbsAppearance` re-reads on Capacitor's configuration-change and resume callbacks, coalesced at 100 ms. It sends only real changes, with a per-instance session id and a monotonic sequence; the JS side ignores stale or duplicate snapshots. No polling and no service.
- **Without a palette:** Android 11 and older (or an unreadable palette) follow light/dark with built-in Dark/Light.
- **Native background:** the app-owned window background behind the WebView's inset margins takes the custom background; built-ins restore the original `#232323`. Inset handling is unchanged.
- **Status bar icons:** follow the appearance's light/dark through the existing StatusBar call.
- **Player glyph:** on light RICE appearances the play/pause glyph is black on the `surface.raised` face (the `#black` glyph pair in `contrast.js`); dark ones keep white.
- **Widget:**
  - **RICE presets and starters on Android 12+:** the standard layouts are tinted by `WidgetPaint` (container, panels, text, icons, progress); ids, actions, sizes and artwork are unchanged. On a light container the buttons take `widget_button_bg_on_light` (the standard face's dark-ink twin), since the standard white-wash faces would vanish.
  - **LLAMA copies:** keep the LLAMA widget.
  - **Android 11 and older:** the standard look.
  - **Device mode:** re-reads the current palette at each widget update, even without the app running.
- **Not covered:** media notification, launcher icon tint and native dialogs are unchanged. Exact One UI propagation of a changed Color palette is a physical-device check (see Testing).

## Linux theme import

Import theme colors accepts a link to a public GitHub project:
- `https://github.com/OWNER/REPO[.git]`;
- `…/blob/REF/PATH` or `…/tree/REF/PATH`;
- `https://raw.githubusercontent.com/OWNER/REPO/REF/PATH`.

A branch or tag with a `/` needs the "Specify a branch, tag or commit" field; refs are never guessed.

**Native boundary (`AbsThemeImport`, `themeimport/`).**
- **Client:** a dedicated OkHttp client with no cookies, credentials, proxy or automatic redirects, separate from the server client, the updater and downloads.
- **Hosts:** it only contacts `api.github.com`, through a DNS check that refuses any non-global-unicast address (loopback, private, link-local, CGNAT, multicast, documentation, unique-local, IPv4-mapped and similar).
- **Redirects:** at most 2, and only to the same host's `/repos` or `/repositories`.
- **Resolution:** the branch or tag resolves once to a commit, and everything after is fetched by SHA through the Git trees and blobs API. Trees are non-recursive.
- **Files:** only regular files (mode `100644`) are accepted: symlinks, submodules, executables, LFS pointers, binaries and non-UTF-8 text are refused. Blobs are verified against their Git id.
- **Limits:** 24 requests, 8 tree responses (depth 4), 32 candidates, 4 file reads, 64 KiB palette, 1 MiB per session; 5 s connect/read, 10 s call, 30 s network total; 5 minutes idle.
- **Errors:** rate limits report the retry time.
- **Sessions:** one session at a time; cancel stops the call in flight, and late results are discarded.
- **Bridge:** only three operations (`start`, `readCandidate`, `cancel`), with no generic fetch.

**Formats** (`theme/rice/import/parsers.js`, strict grammars, data only):

| Format | Accepted |
|---|---|
| pywal / pywal16 JSON | `special.background/foreground[/cursor]`, `colors.color0..15`; `wallpaper`/`alpha`/`checksum` discarded, never opened; template placeholders fail |
| Kitty | `key #RRGGBB` lines for background, foreground, cursor, selection, url, tab/border colors and `color0..23`; comments; `include`, `map`, font and other directives, non-literal values and duplicates rejected |
| Base16/Base24 | Legacy flat `scheme/author/base00..0F`, or `system/name/author/variant/slug/description` + `palette:` with exactly 16 or 24 slots; anchors, aliases, tags, merge keys, flow and block syntax rejected; colors stay text (leading zeros kept) |
| Hex list | 2–24 lines of `#RRGGBB` only; the user picks background, text, accent and progress |

Anything else, including GTK CSS, Rofi, Polybar, KDE, Alacritty, Hyprland, Xresources, scripts and archives, fails with "not a supported palette format".

**Mapping and saving.** Roles go through the same mapper as the starters:
- **Base16:** base00 background, base01 surface, base02 selection, base04 muted, base05 text, base0A progress, base0D accent.
- **pywal/Kitty:** background and foreground; accent from color4 (or color6).

**Review screen.** It shows the preview, the source (owner/repo, path, commit, format), the license status and every adjustment. Saving opens the editor; nothing is applied before Save.

**Stored with the preset:**
- canonical project URL, commit, path and SHA-256;
- parser, original colors, adjustments and attribution;
- license status: "not verified" if the project has a license file, else "unknown".

Never stored: queries, tokens or file contents. Unknown-license imports are for personal use; the app never bundles them.

The browser development build refuses imports, because a browser fetch can't enforce this boundary.

**Pinterest example.**
- The user's pin leads to `ajfunk327/Adventure-Time-Dotfiles` (commit `8d2b1573`).
- Its `Colors/colors` file is a 15-line hex list that imports with the user choosing roles (dark teal background, cream text, coral accent, peach progress).
- The repository has no overall license, so nothing from it is bundled. The tests use only its 15 numeric color values, with attribution.
- Its i3/Polybar/Rofi/GTK files are not imported.

## Testing

| Suite | Covers |
|---|---|
| `tests/rice-core.test.mjs` | Strict JSON, schema rejection cases, LLAMA lock, contrast math and repair, mapping, built-in copies untouched, device fixture, starter catalog + notices + pinned digest |
| `tests/rice-store.test.mjs` | Fresh install, slot/head commits, write and read-back faults, interrupted commits, recovery chain, newer-version protection, serialized writers, preset operations, no unrelated data |
| `tests/rice-service.test.mjs` | Enable/disable, starters, built-in cleanup after every theme, presets, contrast gate, restart/recreation, corrupt-store recovery, device ordering/de-duplication/fallback, concurrency, no playback/auth/CSS-injection paths |
| `tests/rice-import.test.mjs` | Four parsers incl. the Adventure Time palette, malicious inputs, limits, detection |
| `RiceAppearanceTest.kt` | JS/Kotlin mapping parity, API 30/31/34 reader guards, store reader verification and fallbacks, LLAMA family, device re-resolution, widget paint on every standard layout (Robolectric applies the RemoteViews), built-ins untouched |
| `ThemeImportTest.kt` | URL policy, address classification, guarded DNS, fake GitHub API: discovery, direct paths, symlinks/executables/submodules, private repos, refs, tags, truncated trees, blob integrity/UTF-8/LFS, redirects, size/compression/rate-limit/request/byte/time budgets, cancel |

The device checklist (`docs/device-regression-checklist.md`) has a RICE item for a Samsung phone.

**Physical validation (Galaxy S22 Ultra, One UI 8, 2026-10-12):** the current system palette matched the rendered app and widget colors in light and dark; live light/dark switching, background/foreground, WebView reload, widget Play/Pause and the return to built-ins passed. Two light-appearance fixes came from it (mini-player play glyph, widget button faces). Not exercised: changing the One UI Color palette itself (checklist item 8.3, first step).

## Limitations

- **Old WebViews:** applied style options exclude the editor preview with `:not(... *)`, which needs Chromium 88+ (Android System WebView updates bring that to Android 7+). An older WebView drops those rules and shows the base shapes; colors are unaffected.
- **Fixed colors:** some component colors predate the token system and stay fixed in every appearance: the yellow in-progress bar on book cards, series badges, overlay scrims. See "Remaining hardcoded presentation values" in `docs/theme-architecture.md`.
- **Widget:** the RICE widget tint needs Android 12+. LLAMA-family copies use the LLAMA widget unchanged.
- **Not themed:** the media notification, launcher icon and native dialogs are not themed.
- **Three-button navigation:** with button navigation, One UI draws its own mostly opaque dark scrim over the navigation bar (seen on an S22: `#212122` over a dark custom background, `#4F4E4F` over a light one; built-in Light shows the same dark bar). Gesture navigation shows the custom background.
- **Samsung propagation:** how quickly a changed One UI Color palette reaches the public resources (and a widget while the app is closed) depends on the OEM. It is re-read on configuration change, resume and widget updates.
- **Import:** GitHub only, four formats. Importing doesn't run in the browser development build. Rate limits are GitHub's (60 unauthenticated requests per hour per IP).
- **Not yet:** image-based palettes (the schema reserves `source.kind = 'image'`), more formats and other hosts.
