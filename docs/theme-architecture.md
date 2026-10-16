# Semantic theme architecture

Phase 1 of Audiobookshelf+ theming. This phase adds a centralized, validated, testable token layer under the existing themes without changing how the app looks or behaves. Later phases can change the appearance deliberately on top of it.

## Goals and scope

- **Presentation only.** Themes may eventually control colors, backgrounds, gradients, borders, shadows, corner radii, typography presets, shipped icon/glyph presets, seek/progress appearance, control styling, artwork treatment and other purely visual indicators.
- Themes **never** rearrange, add, remove or move controls, create alternate layouts, change navigation, or change playback, queue, restoration or download behavior. They never add a volume control. They never execute CSS, JavaScript or HTML, and never load remote resources.
- A theme is **validated data**, not a stylesheet.

## Pieces

| File                                       | Role                                                                                                                     |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| `theme/tokens.js`                          | The token schema: semantic name, value type, CSS variable, purpose                                                       |
| `theme/builtins.js`                        | Built-in themes (Dark, Black, Light) as data                                                                             |
| `theme/engine.js`                          | Validation and fallback, the only serializer from data to CSS, theme-id resolution, built-in registry                    |
| `tailwind.config.js`                       | Tailwind plugin that emits each built-in theme's variables at build time; the build fails if a built-in theme is invalid |
| `plugins/theme.client.js`                  | `$theme` service: the one runtime place a theme is applied, persisted and restored                                       |
| `tests/theme.test.mjs`                     | Schema, parity, fallback, security and service tests                                                                     |
| `tests/fixtures/legacy-theme-c7a617bc.css` | The pre-token theme CSS, kept as the parity reference                                                                    |

The three modules under `theme/` are CommonJS on purpose, so the Node build (Tailwind) and the app bundle share one source of truth.

Theme identity (`id`, `labelKey`, `colorScheme`), the semantic tokens, how they are applied, and the persisted selection are kept separate.

## Token model

There are 29 tokens in 10 groups (25 from Phase 1, plus `surface.recessed` and the two `presentation.*` policies from Phase 2 — see [Presentation policies](#presentation-policies-phase-2) — and `state.success-action` from Phase 2C Gate H). Each token names a _purpose_. Its `cssVar` is the CSS custom property the existing Tailwind classes and component styles already use, so no component had to change.

| Group        | Tokens                                                                    | CSS variable                                                                                                            |
| ------------ | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| surface      | `base`, `content`, `raised`, `hover`, `recessed`                          | `--color-primary`, `--color-bg`, `--color-secondary`, `--color-bg-hover`, `--color-recessed`                            |
| text         | `default`, `primary`, `muted`                                             | `--color-text-default` (the root `color`), `--color-fg`, `--color-fg-muted`                                             |
| border       | `default`                                                                 | `--color-border`                                                                                                        |
| control      | `toggle`, `toggle-selected`                                               | `--color-bg-toggle`, `--color-bg-toggle-selected`                                                                       |
| progress     | `track`, `buffered`, `played`                                             | `--color-track`, `--color-track-buffered`, `--color-track-cursor`                                                       |
| overlay      | `item-header`, `player`, `mini-player`                                    | `--gradient-item-page`, `--gradient-audio-player`, `--gradient-minimized-audio-player`                                  |
| accent       | `primary`                                                                 | `--color-accent`                                                                                                        |
| state        | `success`, `success-strong`, `success-action`, `warning`, `error`, `info` | `--color-success`, `--color-success-dark`, `--color-success-action`, `--color-warning`, `--color-error`, `--color-info` |
| system       | `bar-icons`, `status-bar`, `navigation-bar`                               | (applied by the service or natively, not CSS)                                                                           |
| presentation | `finish`, `cover-color`                                                   | (validated policies; never CSS)                                                                                         |

The legacy variable names (`--color-bg` means _content_ surface, `--color-primary` means _base_ surface) are kept so that upstream components merge cleanly. The semantic names are the vocabulary for theme data and future work.

Value types:

- `rgb`: `[r, g, b]` integers 0–255, emitted as `r g b` so Tailwind's `/<alpha>` modifiers keep working.
- `overlay`: `{ kind: 'solid', color }` or `{ kind: 'linear', angle: 0–360, stops: [{ color: [r,g,b] | [r,g,b,a], at: 0–100 }] }` with 2–8 stops.
- `enum`: one of a fixed list.

## Built-in themes

`dark` (the default and the fallback), `black` and `light` are plain objects in `theme/builtins.js`. Their values reproduce the pre-token CSS exactly. That includes the status colors, which were previously fixed hex values in `tailwind.config.js` and are now variables with the same values in every theme. The one later addition, `state.success-action` (Gate H), has no pre-token value; it is shared by every theme too.

The Tailwind plugin emits:

- `:root { … }` for Dark;
- `html[data-theme='black'] { … }` and `html[data-theme='light'] { … }` for the others.

The CSS is therefore present at first paint and costs nothing at runtime. The persisted value (Preferences key `theme`) and the `data-theme` attribute keep their existing values, so saved selections carry over.

## Presentation policies (Phase 2)

Phase 2 adds one semantic color and two validated policies.

- **`surface.recessed`**: sunken display/readout wells. Dark, Black and Light carry their `surface.base` value for schema completeness. Nothing in the standard themes consumes it, so their appearance is unchanged; the only change to their compiled CSS is the one new variable.
- **`presentation.finish`** (`standard` | `equipment` | `eink`) selects a **fixed, repository-owned recipe** in `theme/presets.js`.
  - `standard` produces no rules at all.
  - `equipment` is a fixed set of rules, emitted at build time under the theme's own `html[data-theme='<id>']` root.
  - `eink` is the same kind of fixed set with the opposite purpose: it removes shadows, gradients and translucency (see [E-ink](#e-ink-built-in)).
  - Selectors and property names live in code. Values reference only token variables, plus two edge colors (`--color-edge-light`, `--color-edge-dark`) derived from validated surfaces with fixed blend factors.
  - Theme data cannot supply selectors, properties, shadows, gradients, dimensions, URLs, paths or blend factors.
- **`presentation.cover-color`** (`legacy` | `theme`):
  - `legacy` keeps player and item chrome tinted from cover art (existing behavior).
  - `theme` makes that chrome use the theme palette instead. The cover art itself is untouched.
  - Implemented in `theme/coverPresentation.js`, a pure projection from the validated theme and the unchanged cover sample to the chrome values. It covers the full-player backdrop, the mini-player panel, the fullscreen body background, the play-button surface, the white wash (`controlWash`, Gate B) and icon, the dark-foreground decision, and the item-header fill.
  - Under `legacy` it returns exactly the previous expressions, including Black's light-foreground exception.
  - Under `theme` it returns fixed references to `surface.base` (backdrop, panel, body, header) and `surface.raised` (play button).
  - `AudioPlayer.vue` and `pages/item/_id/index.vue` only bind to the projection. A watcher re-syncs the fullscreen body background when the _policy_ changes, so a theme switch while fullscreen can't leave a stale color.
  - Extraction, cover loading and playback code are untouched.

Policies are ordinary enum tokens: invalid values fall back to `standard` / `legacy`, and unknown `presentation.*` keys are ignored. They have no CSS variable, so the serializer never emits them. Dark, Black and Light are `standard` + `legacy`.

## LLAMA (built-in, Phase 2)

LLAMA is an original Audiobookshelf+ theme inspired by the material language of late-1990s blue audio equipment: a deep navy chassis with steel trim, recessed black displays, phosphor-green readouts and amber progress. It uses no third-party skin assets, fonts or pixel values.

- It is a built-in like the others (`id: 'llama'`, label `LabelThemeLlama`, `colorScheme: 'dark'`, `equipment` + `theme`). It appears last in Settings through the registry.
- It styles the existing UI only. Layout, geometry, controls and behavior are unchanged, with one authorized exception: the full player's metadata readout (Phase 4E, below).

The equipment recipe (`EQUIPMENT_RULES` in `theme/presets.js`, emitted only under `html[data-theme='llama']`) is **paint-only**: `box-shadow`, `background-image`, `background-color`, `background-clip`, `border-radius`, `color` and `outline`. It never changes border widths, padding, transforms or layout. The single exception is the full-player metadata readout's own box (`left`, `width`, `padding`, `bottom` on `.fullscreen .title-author-texts`, and `bottom` with the total-track display shown, plus its short-screen and landscape variants in `EQUIPMENT_MEDIA_RULES`); a test allows geometry properties on exactly those selectors and nowhere else. It covers:

- bevelled chassis on the app bar, bookshelf navigation, dialogs/menus and the drawer, with the selected tab shown as a pressed key plus an accent underline;
- steel buttons over their semantic colors (pressed = inset), bordered icon buttons as steel keys (borderless icon buttons stay bare glyphs), and a steel play button;
- recessed wells for text fields and selects, the Up Next list, and a recessed readout strip behind the fullscreen seek and time rows;
- inset seek channels. The played portion stays amber even after the player's seek code swaps in its settled-state class;
- phosphor-green timestamps, speed readout and playback-method label. Titles and other text stay neutral;
- a thin outer steel frame around player artwork and book-card artwork, drawn outside the image (in list view, around the cover only, not the row);
- a visible accent focus outline;
- Checkpoint F polish, each needed by LLAMA:
  - the bookshelf view's wood material becomes graphite/blue-gray equipment (steel ledge, recessed placards with neutral text); alternative-view placards match;
  - toggle switches get a recessed off slot and a steel thumb. On keeps the success color, state also reads from thumb position, and the disabled thumb reads dimmer than the enabled one;
  - unfinished playback progress bars (cards, list rows, playlist rows, item cover) and the current-chapter marker in Chapters use the amber played-progress token. Finished bars (success) and other yellow uses keep their colors;
  - a contrast safety margin for small uppercase muted headers and inactive navigation icons, using primary text at reduced alpha (6.6:1 and 4.8:1 measured, up from 4.6:1 and 3.05:1).

A test compiles the real content with Tailwind and fails if any recipe selector would be pruned. Tailwind drops selectors whose classes only exist at runtime, such as router-added classes.

### Shared equipment primitives (Phase 2C Gate A)

`theme/presets.js` exports `PRIMITIVES`. Rules are composed from these instead of new one-off values. They are fixed repository values: theme data can't supply or adjust any of them, and they only reference the derived edge colors and the accent token.

- **Light source:** light upper/left edges, dark lower/right edges, drop shadows falling down.
- **Radius scale (`RADIUS`):** `frame` 2px (artwork frames and fine detail), `key` 4px (wells, panels and equipment keys, the primary Play/Pause key included). `round` (9999px, for the circular play button) was retired in Phase 4H, when Play/Pause became a squared key. Every `border-radius` in the recipe must come from this scale; a test enforces it.
- **Elevation (`ELEVATION`):** the only drop shadows rules should use.

  - `raised` (buttons);
  - `panel` (cards and framed artwork);
  - `overlay` (dialogs and menus).

  Each level equals a value existing rules already used, so adopting them changed nothing on screen. A few older shadows (the Chapters header, the drawer edge, the mini-player and shelf ledge, which mirror their components' own shadows) keep their values until the gate that restyles those surfaces.

- **Edges:** `RAISED_BEVEL`, `PRESSED_BEVEL`, `RECESSED_WELL`, `STEEL_SHEEN`, `CHASSIS_SHEEN`, `ARTWORK_FRAME`.
- **`ENGRAVED_SEPARATOR`:** a seam cut into an element's bottom edge, a dark inset line with a faint light return below it. It's drawn inside the element's own box, so row sizes never change. Gate B applies it to the player (transport vs secondary row); queue, chapter, table and dialog rows are later gates. `ENGRAVED_SEPARATOR_TOP` is the same seam on a top edge, and `RECESSED_FACE` is a recessed-display bevel as a background layer (both Gate B).
- **Control states** (complete declaration sets):
  - `KEY_CAP` / `KEY_CAP_PRESSED`: a subtle squared steel face on an existing bare-glyph control's own box, inverted while pressed. It never changes size, placement or touch target. Gate B applied it to the player keys; Phase 4B moved those to their own `PLAYER_KEY` recipe (see below), so `KEY_CAP` now serves only the other keys (library selector, dropdown triggers, steppers, row play buttons).
  - `SELECTED_KEY`: pressed in (inset bevel plus inner shade) with an accent ring inside the edge. Selection reads as a physical state, not only a color. Not applied yet; Gates C and E consume it.

Semantic hooks: artwork framing targets the generic `card-artwork` class instead of the `book-card-*` id prefix. `LazyBookCard` (whose root is the cover) and the cover box of `LazyListBookCard` carry it. The old id-prefix selector also matched the list card's whole row, so list view used to frame every row. A test checks both templates.

### Full player and mini-player (Phase 2C Gate B)

Paint only. Every player box, control position, size, order and touch target is unchanged. The emulator check found 22 geometry roles with 0 Dark-vs-LLAMA differences, all identical to Gate A, and a 120px mini-player. Dark/Black/Light get no new rules.

- **Artwork:** the player artwork uses the squared `RADIUS.frame` (2px) instead of the fullscreen 16px card radius (mini was 3px). Fullscreen artwork adds `ELEVATION.panel`, so it sits mounted on the chassis. Size, position, crop and aspect ratio are unchanged.
- **Transport deck:** the existing 200px fullscreen bottom panel (`#playerContent`) becomes a raised chassis panel:

  - the `surface.content` fill, `CHASSIS_SHEEN` and `RAISED_BEVEL`;
  - a dark seam above it, against the artwork zone.

  It holds the seek display, transport row and secondary row exactly where they were.

- **Seams:**
  - `ENGRAVED_SEPARATOR` on `#playerControls` divides the transport row from the secondary row;
  - a new mirror primitive, `ENGRAVED_SEPARATOR_TOP`, on the mini-player's seek region divides it from the title/controls zone.
- **Keys:** the new `player-key` hook marks controls that read as physical keys:

  - transport: chapter start/end and both jumps (the jumps are also the mini-player's);
  - secondary row: queue, bookmark, sleep and chapters.

  They get `PLAYER_KEY` on their own box (a dark-faced key since Phase 4B; it was `KEY_CAP` in Gate B), and `PLAYER_KEY_PRESSED` while pressed. A key that is currently unavailable (loading, no next chapter, no chapters) also carries `key-disabled`, next to its existing dimmed glyph. It then has no cap, so it reads flat by shape as well as color. Readouts (speed, sleep countdown) don't fit a cap inside their boxes and stay bare. The invisible podcast bookmark placeholder isn't a key. The play button stays round.

- **Play button:** `coverPresentation` now also returns `controlWash`, which decides whether the button keeps its translucent white wash:

  - `legacy`: `!isLight`, exactly the previous `v-if`;
  - `theme`: `false`.

  The wash sat above the steel sheen and inset bevel and flattened both. The template binds only to the projection; there are no theme-id checks in the player.

  Without the wash, the subtle `STEEL_SHEEN` over the `surface.raised` fill read as dark slate, the same blue-gray as the chassis. Gate B.1 gave the play button (mini and fullscreen) its own stronger primitive, `PRIMARY_STEEL` (retired in Phase 4H; see "LLAMA primary Play/Pause key" below):

  - an upper-left highlight (same light source as the bevels);
  - a near-opaque top-to-bottom steel falloff composed over the fill.

  The face reads silver-gray, about 112 121 136 behind the glyph. The white glyph keeps at least 3:1, checked against the brightest point, and a test enforces it. Pressed uses `PRIMARY_STEEL_PRESSED`, a dimmer face with an inverted falloff, plus the inset bevel. Secondary keys, buttons and the other steel surfaces keep `STEEL_SHEEN`.

- **Readouts:**
  - The sleep countdown (`sleep-readout` hook) uses the phosphor accent like the other live readouts. This is a presentation mapping; `state.success` still means finished/complete everywhere.
  - The fullscreen seek and total-track wells gain `RECESSED_FACE`, a background-layer bevel that follows `background-clip: content-box` (an inset shadow would span the padding). It adds a dark upper lip with an inner shade and a faint light lower lip.
- **Pending seek:** the seek code's pending state (`bg-yellow-300`, until playback confirms the position) was a near-identical yellow next to the amber played bar. LLAMA draws it as the same amber broken into segments, so it reads as unsettled by pattern, not only by hue. Seek behavior and the class toggling are unchanged.

### Up Next and player-adjacent overlays (Phase 2C Gate C)

Paint only, scoped to the overlays opened from the player:

- Up Next (`QueueModal`);
- playback speed;
- sleep timer;
- bookmarks (`BookmarksModal` and `BookmarkItem`).

`AudioPlayerContainer` is the only place these are used. Shared dialogs (`Dialog.vue`, the global `Modal` shell) are untouched. Geometry, queue semantics, drag/reorder, removal, scrolling and dismissal are unchanged. Chapters is the consistency reference and gets no new rules.

They all follow one hierarchy: the existing raised chassis dialog panel, then a recessed list well, then engraved row seams, then the current or selected state. Rows stay flat display entries, not keys.

- **Up Next:** the panel stays the recessed well it already was.
  - The current item is the Now Playing block (`queue-current`), not a row in the list. It is now a lit entry with the same played-progress amber as Chapters' current-chapter marker, drawn as a 2px inset left edge (`CURRENT_MARKER`) rather than a new element. It is pressed in (`PRESSED_BEVEL`), so it reads by shape as well as color, and its divider becomes an engraved seam.
  - Upcoming rows (`queue-row`) get `ENGRAVED_SEPARATOR` between rows, none after the last.
  - The drag handle and the remove control keep their existing look.
- **Speed and sleep option lists** (`playback-option-panel`):
  - a recessed `ul[role=listbox]` well with seamed `li[role=option]` rows;
  - the selected speed (`option-selected`, next to its existing wash class) is a `SELECTED_KEY` (pressed in, accent ring) on a lit row;
  - the speed stepper strip (`option-panel-footer`) is a raised chassis strip, and its steppers are `KEY_CAP` keys (pressed inverts them; disabled steppers stay flat).
- **Live readouts:** the current speed (`speed-readout`) and the running sleep countdown (`sleep-readout`) use the phosphor accent.
- **Bookmarks:**
  - a recessed list (`bookmarks-list`) with seamed rows (`bookmark-row`);
  - the bookmark at the current position (`bookmark-current`, next to its existing highlight classes) is a lit, pressed entry with the amber marker;
  - its icon (`bookmark-icon`) is amber, because it marks a position. It was success green, which now only means completion or a positive outcome. The Create Bookmark bar keeps its success color as a positive action.

Cascade: a state rule outranks the row-seam rule it shares an element with, by equal specificity and later order; a test computes this. Without it, the selected speed lost its key to the seam rule.

### Browsing and detail surfaces (Phase 2C Gate D)

Paint only:

- home shelves;
- the bookshelf toolbar;
- the library selector and the library list;
- card types;
- item, collection and playlist detail pages;
- detail tables;
- group-item tables.

Geometry, navigation, data loading, filters, sorting and actions are unchanged. Content stays dominant: artwork is mounted, information sits in recessed displays, tappable section headers are raised strips, and only real controls become keys. `PRIMARY_STEEL` stays reserved for the play button. The completed player, the Gate C overlays and Chapters carry none of these hooks, and a test checks that.

- **Artwork:** every card type marks its artwork box with the generic `card-artwork` hook. That's the series, collection and playlist cards' inner cover box, the author card's portrait box (its rounded portrait shape is kept), and the cover box of collection and playlist table rows. It's never a card root or row; a test checks each template. Detail artwork (`detail-artwork`) gets the same mounted frame on the squared frame radius:
  - the item page's cover box, which also holds the progress bar;
  - the collection and playlist cover components, at their detail-page usage only.
- **Shelves:** each home section (`shelf-section`) ends in an engraved seam. It's drawn beneath the shelf's content, so in the standard shelf view the shelf's own divider covers it; it only shows in the alternative view.
- **Toolbar** (`browse-toolbar`): a chassis strip seamed off from the content below.
  - The active-filter dot (`filter-indicator`) was success green. It's now the accent: an active state, not a completion.
- **Library selector** (`library-selector`, in the app bar): a key that inverts while pressed.
- **Library list** (`library-option-panel`): recessed well with seamed rows. The current library (`option-selected`) is a `SELECTED_KEY` on a lit row, and its marker (`option-marker`) is the accent instead of warning orange.
- **Item detail:**
  - The progress box (`detail-progress`) is a recessed display; its text is unchanged.
  - The tappable Chapters/Tracks/Ebook-file headers (`section-bar`) are raised strips.
  - Their count badges (`section-count`) are small recessed accent readouts.
  - The tables (`tracksTable`) become a recessed display with a dark zebra.
  - Action buttons keep the existing steel `.btn` treatment.
- **Collection and playlist detail:**
  - The items table (`group-items`) is a recessed well under a raised header strip, with the count and total duration (`section-readout`) as accent readouts.
  - Collection rows (`group-row`) are seamed display entries.
  - Row play buttons (`row-play-btn`) are keys.
  - Playlist rows keep their own entry panels.
- **Semantics:**
  - Unfinished progress bars stay amber and finished ones stay success green, through the existing rule; Gate D adds no progress selectors.
  - No Gate D rule uses success or warning colors.
  - Cascade checks: the selected library outranks its row seam; the LLAMA table zebra outranks both the LLAMA base rows and `assets/app.css`; the artwork frame outranks the author card's `box-shadow-book` utility.

### Forms, shared dialogs and interactive controls (Phase 2C Gate E)

Paint only:

- the shared form controls;
- the shared `Dialog`;
- the playlists-modal row;
- search result artwork.

Sizes, behavior, validation, native inputs, ARIA roles, focus handling and dismissal are unchanged. The completed player, the Gate C overlays and the Gate D surfaces carry none of these hooks, and tests check that. The player seek is not a range input, so the range rules cannot reach it.

- **Segmented toggles** (`ui/ToggleBtns`, existing `toggle-btn` and `selected` hooks):

  - segments are raised steel;
  - the selected choice is a `SELECTED_KEY` on its existing lit fill;
  - pressing inverts the bevel;
  - unselected labels keep a readability margin (78% text, 5.4:1 on the sheen).

  Segment shapes and borders are unchanged.

- **Range** (`range-input`): a recessed slot with the seek channel's edges, and a steel thumb instead of playback amber (a setting is not progress). Focus keeps its outline, now in the accent.
- **Checkbox** (`checkbox-box`, `checkbox-checked`, `checkbox-disabled`, `checkbox-mark`):

  - unchecked is an empty recessed well;
  - checked is lit and pressed, with an accent edge and accent check (checked is not success);
  - disabled is flat, with a dark edge and a muted check, and outranks checked, so a disabled checked box never reads as active.

  The native input stays, still invisible. Its keyboard focus now outlines the visible box via `:has(input:focus-visible)`; before, keyboard focus on a checkbox showed nothing.

- **Dropdown** (`dropdown-button`, `dropdown-menu`): an enabled trigger is a key, and a disabled trigger stays flat. The open list is a recessed module with seamed options; it has no selected state of its own.
- **Shared Dialog** (`dialog-panel`, all menus and option lists): the existing chassis panel holds a recessed list with seamed rows. A selected option (`option-selected`, next to the existing wash classes) is a `SELECTED_KEY` on a lit row instead of a success-green wash.
- **Playlists-modal row:** the cover component is `card-artwork` (never the row root). The membership marker (`membership-marker`) is the accent instead of success.
- **Search results:** item, episode and series covers and the author portrait box are `card-artwork`. Narrator and tag results have no artwork.
- **Unchanged, already consistent:**
  - text inputs and textareas: Gate A recessed wells with the accent focus outline;
  - `ui-btn`: steel over its semantic color; destructive and positive keep their colors, and disabled stays flat;
  - `IconBtn`: only bordered icon buttons are keys (the Gate A contract, re-tested);
  - `ToggleSwitch` (Checkpoint F);
  - the `Modal` shell (a backdrop only).
- **Deferred:**
  - reducing the 8px dialog radius: the radius comes from the shared dialog-panel rule that also draws the frozen Gate C panels;
  - `MultiSelect` (podcast-only chips);
  - `FullscreenModal`: the reader's sheet follows the independent reader theme;
  - unused `DropdownMenu` and `Menu`.

Palette (accepted as the LLAMA palette through Phase 2C Gates A–H; the chassis family was retuned toward the mockup in the Phase 4 finishing pass, below):

| Token                                              | RGB                               | Role                                                                                                              |
| -------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `surface.recessed`                                 | 10 13 18                          | black-blue display wells                                                                                          |
| `surface.base`                                     | 22 33 58                          | deep navy chassis: app bar, dialogs, menus                                                                        |
| `surface.content`                                  | 39 54 87                          | blue equipment panel: page surface, player deck                                                                   |
| `surface.raised`                                   | 63 81 120                         | lighter blue-steel strips                                                                                         |
| `surface.hover`                                    | 81 101 146                        | blue-steel highlight                                                                                              |
| `text.default` / `text.primary`                    | 235 238 242 / 226 232 240         | neutral near-white                                                                                                |
| `text.muted`                                       | 150 162 178                       | subdued cool gray                                                                                                 |
| `border.default`                                   | 88 109 157                        | blue-steel edge                                                                                                   |
| `control.toggle` / `-selected`                     | 39 54 87 / 81 101 146             | toggle segments                                                                                                   |
| `progress.track` / `buffered` / `played`           | 40 46 56 / 86 98 116 / 245 190 40 | recessed channel, lighter buffered, yellow-amber played                                                           |
| `accent.primary`                                   | 96 232 104                        | phosphor-green readout/accent                                                                                     |
| `state.*`                                          | shared                            | success, warning, error and info keep their semantic values, so warning orange stays distinct from amber progress |
| `state.success-action`                             | 46 125 50 (shared)                | fill of actionable success controls with white labels (Gate H)                                                    |
| derived `--color-edge-light` / `--color-edge-dark` | 149 159 181 / 9 13 23             | bevel edges, fixed blends of `surface.raised` / `surface.base`                                                    |
| `system.*`                                         | 35 35 35, light icons             | the actual native window background; runtime system-bar theming is out of scope                                   |

**Chassis palette (Phase 4 finishing pass).** With the control deck finished, the largest remaining difference from the approved mockup was the chassis: the accepted palette was a restrained blue-gray slate (hue about 217°, saturation 17–26%), the mockup a deeper blue equipment chassis with a slight indigo lean (panels sampled at hue 218–222°, saturation 35–45%, for example 41 56 86 for its deck and 23 35 54 for its darker frame). The chassis family (`surface.base`, `surface.content`, `surface.raised`, `surface.hover`, `border.default`, both toggle segments, and the chassis colors inside the three overlays) moved by +5° of hue and 1.7× the chroma at unchanged WCAG luminance:

| Token                                       | Before (Phase 2C)      | After (finishing pass) |
| ------------------------------------------- | ---------------------- | ---------------------- |
| `surface.base`                              | 27 34 46               | 22 33 58               |
| `surface.content` / `control.toggle`        | 45 55 71               | 39 54 87               |
| `surface.raised`                            | 70 82 101              | 63 81 120              |
| `surface.hover` / `control.toggle-selected` | 88 102 124             | 81 101 146             |
| `border.default`                            | 96 111 134             | 88 109 157             |
| derived `--color-edge-light` / `-dark`      | 153 160 170 / 11 14 18 | 149 159 181 / 9 13 23  |

- **Why this one.** Runtime prototypes of the finished player and of the bookshelf, item page, settings, a modal and the mini-player compared the current palette with a subtle (+3°, 1.35×), an intermediate (+4°, 1.5×) and a moderate (+5°, 1.7×) move. The moderate palette is the one that reads as the mockup's equipment: its deck (39 54 87) sits on the mockup's own panel color (41 56 86), and it stays dark, with no neon or "gaming" blue. The subtle and intermediate steps were recognizably on the way to it rather than distinct alternatives.
- **What does not change.** Holding luminance keeps every contrast and every depth step where it was: text, readouts and amber within ±0.02 of before (primary text 13.0:1 on the chassis, 9.7:1 on the deck; muted text 4.6:1 on the deck; the green 7.6:1 and the amber 7.0:1 on the deck), the content/base step 1.33:1 (the mockup's is about 1.35:1) and the base/well step 1.22:1, so nothing collapses into black on OLED. The grayscale rendering of the player is essentially unchanged. The near-black wells (`surface.recessed`), the seek channel (`progress.track`, `progress.buffered`), amber (`progress.played`), green (`accent.primary`), the text colors, `state.success`, `state.success-action`, `state.error` and the system bars are not part of the chassis family and keep their values.
- **Amber and green.** The rendered legends are as before, measured on the finished build: transport amber 10.4:1 (10.9:1 pressed), utility amber 10.4–10.5:1, primary 9.9:1, collapsed 11.2:1, speed readout 12.3:1. Against the bluer deck, amber reads warmer without turning orange and the green keeps its phosphor look, because both sit on the dark key faces and wells, whose colors moved only slightly with the chassis.
- **Widget.** The native widget palette is generated from these tokens (`scripts/generate-widget-theme.js`), so the LLAMA widget's chassis, raised trim, bevel edges and key faces follow the new navy (base `#16213A`, content `#273657`, raised `#3F5178`, key face `#0C1018`). No widget layout, geometry, provider or action changed; Robolectric renders of the FULL and WIDE layouts confirm the same hierarchy on the new chassis.
- **Scope.** LLAMA only: Dark, Black and Light are pixel-identical, and geometry is unchanged (the 22-role check matches the accepted build).

Known limits at this stage:

- **Startup:** like Black and Light, a saved LLAMA selection is restored asynchronously, so the pre-render loading screen briefly shows the default Dark surface.
- **Non-EPUB reader shell:** it only knows `black`, `dark` and `light`. `engine.readerShellId()` keeps those three and maps any other theme to the shell matching its color scheme, so LLAMA uses the dark shell. The independent EPUB reader theme and `EpubReader.vue` are unchanged.

## Validation and fallback

- `validateTokens(input, fallback)` reads only schema tokens, and only as own properties.
  - Missing or invalid values take the fallback (default theme) value and are reported.
  - Unknown keys are ignored and reported.
  - Non-plain objects fall back entirely.
  - It never throws.
- `validateTheme` also checks identity: the `id` pattern `^[a-z][a-z0-9-]{0,31}$`, a label key, and a `colorScheme` of `dark` or `light`. A theme with invalid identity is unusable.
- `resolveThemeId(value)` maps any stored or requested value to a built-in id. Unknown, empty, non-string or prototype-named values resolve to `dark`. This matches the old behavior, where an unknown attribute simply fell back to the `:root` (Dark) CSS.
- Built-in theme data is deep-frozen at runtime. A built-in that fails validation fails the build.

## How tokens reach the UI

1. **Build time:** `builtinThemeRules()` → Tailwind `addBase` → the CSS variables → existing Tailwind classes (`bg-bg`, `text-fg`, `bg-success/10`, …) and component styles (`rgb(var(--color-track))`).
2. **Runtime:** `$theme.apply(id)` sets `<html data-theme>` to a validated id and applies the system-bar icon style. `$theme.select(id)` also persists the choice. `$theme.restore()` runs at startup, and `$theme.ready` resolves once the saved selection is applied.
   - Settings lists themes from the registry (same order and labels as before) and calls `select`.
   - `init.client.js` no longer writes the attribute or the status-bar style itself.

## System bars

Findings:

- Under edge-to-edge (targetSdk 36), `MainActivity` offsets the WebView by the system-bar insets. The areas behind the status and navigation bars show the native activity background: `AppTheme.NoActionBar` → `@color/background_dark` (`#232323`).
- `window.statusBarColor` and Capacitor's `StatusBar.setBackgroundColor` have no effect on Android 15+. The bars are therefore `#232323` with light icons in **every** theme, including Light.
- Before this phase, the icon style was set unconditionally to light icons at startup.

Phase 1 status:

- `system.bar-icons`, `system.status-bar` and `system.navigation-bar` are recorded per theme with the current values (`light`, `#232323`, `#232323`). A test keeps them equal to `colors.xml`.
- The `$theme` service applies the icon style from the token, which is the same light-icon style as before, so nothing changes on screen.
- **Deferred (native work):** applying the bar colors at runtime needs a small native method. It would set the activity root/decor background, set `WindowInsetsControllerCompat` light/dark bar appearance, and persist the last colors natively so the next cold start doesn't flash. That would let Light use light bars with dark icons. It changes Light's appearance, so it belongs to a later phase, with physical-device testing (the Galaxy S26 Ultra is the current test device) across Android versions. Layout and inset handling must not change when it is added.

## Typography findings

- **Declared:** `tailwind.config.js` sets `sans: 'Source Sans Pro', …defaults` and `mono: 'Ubuntu Mono', …defaults`. `assets/fonts.css` declares Source Sans Pro (Light, Regular, SemiBold) and Ubuntu Mono (Regular) from `static/fonts/` (OFL/UFL licensed files). It also declares the Material Symbols Rounded and absicons icon fonts.
- **Actual (verified in the Android WebView):**
  - Only the two icon fonts are registered.
  - All 27 text `@font-face` rules use `format('ttf')`, which is not a valid format hint (the valid one is `truetype`), so the browser discards their `src`. The files themselves are packaged and would be served fine.
  - In general, text therefore renders in the **system sans-serif** reached through the stack (`system-ui`), and monospace text in the **system monospace** font. Which faces those are depends on the device.
  - On the tested Pixel emulator (Android 15) they are **Roboto** and **Droid Sans Mono**. Semibold and bold come from real Roboto variable-font weight instances, not synthesized bold. Samsung/One UI may resolve the system fallback differently; that is not verified.
- **Phase 1 keeps this unchanged:** the system fallback _is_ the current appearance. Fixing the format hint would visibly change every screen, so it is a deliberate later decision.
- **Scripts:**
  - Roboto covers Latin (including Slovak diacritics), Cyrillic and Greek.
  - Android's system fallback supplies Noto Naskh Arabic, Noto Sans Hebrew and Noto Sans CJK/Korean per glyph.
  - Source Sans Pro, if loaded, would also cover Slovak, but not Arabic, Hebrew or Korean. Those would still fall back per glyph, provided the stack ends in a generic family.
  - The app does not set `dir="rtl"` for Arabic or Hebrew; layout mirroring is out of scope for theming.
- **Future font presets** must:
  - use only bundled fonts or system families (no URLs);
  - always end in a generic family (`sans-serif` / `monospace`) so unsupported scripts fall back instead of rendering as boxes;
  - apply display or decorative fonts only to limited roles, never to body text;
  - use `font-display: swap` and a correct format hint.

### Typography runtime audit and deferral (Phase 2C Gate G)

Gate G was a read-only audit of what the Android WebView actually renders. It **changed no runtime behavior**: the inherited font-loading issue is documented and deferred (see "Bundled text fonts" in `docs/future-work.md`).

Environment: Pixel emulator, Android 15 / API 35 Google APIs x86_64, 1080×2400 at 420 dpi, Android System WebView 124.0.6367.219, font scale 1.0. Inspection used the Chrome DevTools Protocol against the app's WebView.

Evidence:

- `CSS.getPlatformFontsForNode` (the DevTools "Rendered Fonts" data) identified the face that actually drew each text node on 11 surfaces, in Dark and LLAMA.
- The CSSOM still contains all 27 Source Sans Pro / Ubuntu Mono rules, but each has an **empty `src`** descriptor.
- `document.fonts` holds only Material Symbols Rounded and absicons, which load correctly.
- No Source Sans Pro or Ubuntu Mono file is ever requested. No warning is logged; the WebView drops the invalid `src` silently.
- `document.fonts.check()` is **not** usable as proof: it returned `true` even for a nonexistent family, because there was no face to load. `getComputedStyle().fontFamily` only repeats the declared stack.

Rendered faces on the tested Pixel:

| Requested           | Rendered today                    |
| ------------------- | --------------------------------- |
| Source Sans Pro 400 | Roboto                            |
| Source Sans Pro 300 | Roboto 300 variable-font instance |
| Source Sans Pro 600 | Roboto 600 variable-font instance |
| Source Sans Pro 700 | Roboto 700 variable-font instance |
| Ubuntu Mono 400     | Droid Sans Mono                   |

The emulator image happens to ship Source Sans Pro as the system family `source-sans-pro`. The CSS name `Source Sans Pro` does not match it, so it is not used.

Inventory and provenance:

- Bundled files: `SourceSansPro-Light.ttf` (300), `SourceSansPro-Regular.ttf` (400), `SourceSansPro-SemiBold.ttf` (600) and `UbuntuMono-Regular.ttf` (400), identified from their internal name and OS/2 tables. Licenses: OFL (Source Sans Pro) and UFL (Ubuntu Mono).
- **Not bundled:** Source Sans Pro Medium (500), Bold (700) and any italic; Ubuntu Mono Bold.
- The 27 `format('ttf')` hints (21 Source Sans Pro, 6 Ubuntu Mono) live in `assets/fonts.css`. The minified bundle keeps them (as `format("ttf")`), and the font files reach the Android assets byte-identical. The failure is the declaration, not missing files.
- `assets/fonts.css`, `assets/absicons.css` and `static/fonts/` are identical to upstream. The hints date from upstream commit `30d86279` ("Update:Host fonts locally", 2022-05-05). Audiobookshelf+ and LLAMA did not introduce or modify them.
- `theme/presets.js` declares no font family, size or weight; its LLAMA `.font-mono` rules set color only. Dark and LLAMA had **0** differences in computed family, weight, size and line-height. The behavior is global, not theme-specific.

Intended-font experiment: a temporary, runtime-only `<style>` re-declared the same files, families, weights and unicode ranges with only the hint corrected to `truetype`. The bundled faces then loaded. The style was removed afterwards and no repository file changed. Results:

- Proportional text was about 5–9% narrower than the current Roboto rendering; Ubuntu Mono readouts were 16.7% narrower than Droid Sans Mono.
- Source Sans Pro Regular drew about 22% less ink than Roboto Regular, and has a smaller x-height (0.486 em vs 0.528 em).
- Requested 700 mapped to the bundled SemiBold (600) with no synthesized bold; requested 500 mapped to Regular. Today 700 is a real Roboto 700 instance, so the weight hierarchy would flatten.
- Across 22 theme × surface pairs: 0 line-count changes, 0 truncation flips, and 0 changes to the measured block heights and line boxes (explicit line-heights govern them). Fixed and container geometry stayed identical, including the 120px mini-player, the ~65×65 full-player and ~40×40 mini-player play buttons, the full-player cover, controls and panel, and the modal panels. Content-sized elements (navbar tabs, the library selector pill) narrowed by a few pixels.
- One behavior change: the borderline mini-player title "The Name of the Wind | Chapter 12" scrolls as a marquee today but fits statically in Source Sans Pro. `WrappingMarquee` decides once, at `init()`, from the width measured at that moment. Typography can change behavior even when container geometry is stable.

The experiment is not proof of zero geometry risk. Not covered: WebView font scale 1.3, non-Latin fallback, a cold start (font-swap timing), complete item-detail matching, several Gate E controls (toggles, checkboxes, dropdowns, search cards), and tables or collection/playlist rows.

The home-screen widget is **not** affected. It renders with native RemoteViews typography (system sans-serif and `fontFamily="monospace"`) and never consumes the WebView `@font-face` rules. Gate G does not reopen Gate F.

**Phase 2C typography policy:** for the rest of Phase 2C, the inherited WebView font-loading behavior is not repaired as part of LLAMA. LLAMA keeps the same effective typography as the other themes and does not get its own font family unless a later typography or customization phase authorizes it. This keeps the baseline under which Gates A–F were designed and accepted.

## Accessibility and hardening (Phase 2C Gate H)

Gate H was the final Phase 2C hardening gate: an audit, then bounded fixes. It did not redesign LLAMA and changed no geometry. The 22-role comparison stayed at 22 roles and 0 Dark-vs-LLAMA differences, identical to Gate F, with the 120px mini-player.

Method: on the Pixel emulator (API 35, WebView 124), contrast was measured on the rendered WebView. The foreground is the computed color times its alpha and ancestor opacity. The background is sampled from a DevTools screenshot under each text or icon element, inside its border and bevel, so the steel sheen, overlays and translucency are included; the worst sample counts. Thresholds are WCAG 2.2 AA: 4.5:1 for text, 3:1 for large text and for icons and state indicators; disabled controls are exempt. 13 surfaces were measured in LLAMA and Dark, plus Black and Light for the success and button surfaces. Suspicious results were checked against crops and direct pixel samples.

Fixes:

- **Actionable success (all themes).** White on `state.success` (`#4CAF50`) is 2.78:1, below 4.5:1, in every theme. The new shared token `state.success-action` (`#2E7D32`) is the fill of success buttons (`ui/Btn` with `color="success"`: item Play/Stream, playlist and collection Play, bookmark and playlist Create/Update, and the other success actions) and of the Bookmarks "Create Bookmark" row, which also drops its 80% text opacity.

  | Element                         | Before                  | After                       |
  | ------------------------------- | ----------------------- | --------------------------- |
  | Success button text, plain fill | 2.78:1                  | 5.13:1 (Dark, Black, Light) |
  | Success button text, LLAMA      | 2.79–2.80:1 (sheen top) | 4.68–4.84:1 (sheen top)     |
  | Item Play/Stream icon           | 2.78–2.79:1             | 4.81:1 (LLAMA), 5.13:1      |
  | Bookmarks "Create Bookmark" row | 2.30:1 (text and icon)  | 5.13:1                      |
  | Disabled success button         | 4.18:1                  | 7.16:1 (exempt either way)  |

  `state.success` itself is unchanged, and so is everything else that uses it: finished progress bars, toggle-switch on, `text-success`, and (outside LLAMA, which uses the accent there) the filter and membership markers and the Dialog selection wash. `bg-success`, `text-success` and the toggle-on fill resolve to `rgb(76 175 80)` in every theme (verified at runtime).

- **LLAMA destructive icon keys.** LLAMA's near-white glyph on the sheened error fill was 2.80:1, below the 3:1 non-text minimum. One paint rule makes the glyph on bordered `bg-error` icon keys pure white: 3.25:1. The error fill is unchanged.
- **CI.** The Build APK workflow now runs the JS suite (`node --test tests/*.test.mjs`) after `npm ci` and the Kotlin JVM/Robolectric suite (`testDebugUnitTest`) after `assembleDebug`, and uploads the test reports when it fails. Before Gate H no workflow ran either suite.

Audit results that needed no change:

- LLAMA text passes on every audited surface, including muted text (the Checkpoint F margins), toggle segments (5.51:1 unselected), selected rows, readouts on recessed wells, steel keys (6.6–7.3:1 for the player key glyphs), dialogs and the theme picker.
- Non-text: the focus outline (accent, 7.6–12.3:1), played vs track (7.98:1), checkbox edge and check, toggle-on fill vs content (4.32:1) and the accent markers pass. Buffered vs track (2.21:1) is supplementary, and input wells are identified by fill and bevel, not the 2.35:1 border alone; both are accepted Gate A–E designs.
- Keyboard: every reached control shows the 2px accent `:focus-visible` outline.
- Semantics are consistent: amber for playback position; the accent for readouts, active filters, membership, checked state, focus and selection; success for finished state and positive actions (`state.success` for state, the darker `state.success-action` fill for actionable controls). `ToggleSwitch` on keeps the success color, as accepted at Checkpoint F; its state also reads from thumb position.
- Font scale: Android's font scale reaches the WebView live (the root font size goes from 16px to 20.8px at 1.3). Nothing is lost at 1.3, but two shared geometry effects remain (see `docs/future-work.md`).
- Widget: the generated LLAMA widget palette pairs pass (text 4.6–13:1, readouts on the recessed well 12.3:1).

Validation at the end of Gate H: JS 127/127, Kotlin/Robolectric 109/109, `lintDebug` 0 errors and 143 warnings (the same warning set as Gate F), the widget palette check, generate and `assembleDebug`, and a CI run that executed and passed both test steps.

Shared issues found and deferred (each in `docs/future-work.md`): Dialog keyboard support, font-scale 1.3 geometry, error-button text contrast, Dark muted-text and item-header contrast, and the success toast colors.

## Phase 2C closure

**Phase 2C — LLAMA theme implementation and hardening — is COMPLETE.** The accepted candidate baseline is `2d48b307` on `feature/llama-theme`. A documentation-only closure commit follows it (find it in the Git history of this file); it changes no runtime behavior.

Phase 2C delivered and validated the LLAMA theme architecture and presentation: Gates A–F (recipe foundation, player, overlays, browsing, forms and dialogs, widget), Gate G (typography audit and deferral) and Gate H (accessibility and hardening). It does not claim to solve every inherited Audiobookshelf+ accessibility, branding, playback-session or responsive-layout issue. Those were deliberately kept out of scope because they are inherited, shared across themes, architectural, product decisions, or outside Phase 2C. The future-work list (`docs/future-work.md`) is not evidence that Phase 2C failed.

Final accepted validation of `2d48b307`:

| Check                                     | Result                                  |
| ----------------------------------------- | --------------------------------------- |
| JS suite                                  | 127/127                                 |
| Kotlin/Robolectric unit tests             | 109/109                                 |
| `lintDebug`                               | 0 errors / 143 warnings (unchanged set) |
| 22-role Dark-vs-LLAMA geometry            | 0 differences                           |
| Mini-player height                        | 120px                                   |
| Full-player / mini-player play control    | 65×65 / 40×40                           |
| Gate E geometry regression                | 0 changes                               |
| Widget freeze and containment validation  | pass                                    |
| CI (Build APK with both test steps, i18n) | green on `2d48b307`                     |
| Galaxy S26 Ultra physical app validation  | pass                                    |
| Galaxy S26 Ultra physical LLAMA widget    | pass                                    |

The S26 validation used the persistent-key Debug Test APK built from exactly `2d48b307`. The version stayed `0.14.2-beta` / versionCode 129, versionCode 130 stayed unused, and no merge, tag or release happened during the Phase 2C acceptance.

Findings from the final acceptance, recorded as future work rather than blockers: the seek handle touch target (relatively high priority), widget controls after a book finishes or the player closes (a product/UX decision), a stale widget snapshot seen once, the connect-screen title and store-listing branding, and the corrected font-scale 1.3 measurements. Library cover art that carries Audible branding is user content, not an app defect; public Audiobookshelf+ imagery must avoid it (`docs/app-identity.md`).

## LLAMA secondary control finish (Phase 4B)

Paint only, LLAMA only. Phase 4A measured that the player keys were not short of contrast (key against deck was 1.20:1, the reference about 1.24:1). What differed was polarity (the keys were lighter than the deck, the reference's are darker), the legend (75% near-white outline glyphs), and a shallow edge. Phase 4B changes exactly those on the keys that already carry the `player-key` hook. Every box, size, position, hit area, text style, and the silver play button are unchanged.

- **Dark key face:** `PLAYER_KEY` fills the key with a translucent `surface.recessed` (50%), so it follows the deck's own gradient and stays darker than whatever it sits on. The collapsed player's deck is already close to that color, so `PLAYER_KEY_FACE_MINI` (85%) keeps the same step there.
- **Edge depth:** a lit upper-left inner edge with a faint second highlight line, a dark lower-right inner edge, a dark outer ring (the channel the key sits in), a short drop shadow and the existing soft sheen. All of it is background or shadow paint on the key's existing box.
- **Pressed:** `PLAYER_KEY_PRESSED` drops the sheen and the drop shadow, inverts the inner bevel and cuts in an inner shade, keeping the ring. It reads as engaged without moving anything.
- **Unavailable:** unchanged. A `key-disabled` key matches none of these rules, so it has no face, ring or legend color, only its dimmed glyph. That keeps it distinct from resting, pressed and available. (Phase 4J replaced this with a sunken socket; see "LLAMA control deck".)
- **Amber is playback only (Phase 4B; superseded for the control deck by the Phase 4 finishing pass, see [LLAMA control deck](#llama-control-deck-phase-4j-and-the-phase-4-finishing-pass)):** the two jump keys and chapter start/end (`jump-icon`, `next-icon`) take `PLAYBACK_LEGEND`, the played-progress amber, for the glyph and the "10s" label. The utility keys (queue, bookmark, sleep, chapters) keep their neutral glyphs. The round play button is not a player key and is untouched: amber on its silver face measures about 2.9:1 on the lower-lit and 1.8:1 on the upper-lit region, so it would fail the 3:1 icon minimum.
- **Heavier legends:** the bundled Material Symbols font has only a `FILL` axis (no `wght`). `FILL` leaves the stroke-based transport glyphs unchanged and would make the bookmark solid, which already means "has bookmarks". So the transport glyphs get `PLAYBACK_GLYPH_WEIGHT`, a 0.5px `-webkit-text-stroke` in their own color. It adds ink only, and the utility keys stay at regular weight, which keeps the transport legends the heavier tier. If a device renders the stroke poorly, removing that one primitive restores the regular weight.

Rendered contrast, measured from emulator pixels (emulator, Book A paused):

| Pair                                                    | Ratio                             |
| ------------------------------------------------------- | --------------------------------- |
| Amber legend on the full-player key face                | 8.7:1                             |
| Amber legend on the mini-player key face                | 10.5:1                            |
| Neutral glyph (bookmark, sleep) on its face             | 5.6–5.7:1                         |
| Chapters glyph (75% primary) on its face                | 7.3:1                             |
| Key face against deck: transport / secondary row / mini | 1.22–1.23 / 1.09–1.13 / 1.11–1.12 |

`tests/theme-llama.test.mjs` keeps the contract: LLAMA-only scoping, no geometry properties, amber limited to the transport keys, the unavailable state excluded, and the contrast of the new faces computed from the theme tokens.

Deferred (not part of this change):

- **Top-bar controls:** the collapse, cast and overflow glyphs are bare `span`s in the fullscreen overlay, which sits outside `#playerContent`. Framing them needs a hook class and a new scope, and their boxes are glyph-sized (touch-target and geometry questions).
- **Heavier icons beyond the stroke:** a true weight axis needs a different icon font file, which is global.
- **Title and author readout, typography, and the widget finish** are separate, later work.

## LLAMA full-player metadata readout (Phase 4E)

LLAMA only, full player only. After Phase 4B the full player's title/chapter and author floated on the flat chassis between the mounted artwork and the deck, with about 75px of unused chassis below them (412×842 CSS px). Phase 4E makes that block a recessed information display mounted in the chassis. It is the one place where the recipe changes geometry, and only the block's own box.

- **No template change.** The existing block (`.title-author-texts`, with its marquee wrapper, `title-text` and `author-text`) is the readout. The rules apply only under `.fullscreen`, so the collapsed player's block is untouched.
- **Display (`METADATA_READOUT`, paint):** the `surface.recessed` face with the shared `RECESSED_WELL` edges on the key radius, mounted in a bezel plate drawn with outer shadows: a dark seam, a 2px plate in the deck color (`--color-bg`), a lit upper-left lip, a dark lower-right lip and a soft drop. Shadows add no layout. The seek well has no plate, so the two displays share depth and edge language but stay distinct: the readout is an information module and the seek well a playback-position module.
- **Layout (`METADATA_READOUT_LAYOUT`, the authorized geometry):** `left: 24px` and `width: calc(100% - 48px)` put the readout in the seek wells' column, `padding: 6px 12px` is its inner inset, and `bottom` moves its anchor 22px lower (`+ 28px` instead of `+ 50px` in the player's own cover-relative formula). The block stays bottom-anchored, so it grows upward with the font scale like the original, and the expand/collapse transition (which animates `bottom`) is kept. `max(…, 210px)` keeps the plate clear of the 200px deck. Portrait never reaches it, but landscape does: there the block sits beside the artwork, right on the deck, and would otherwise cover the seek timestamps.
- **Total-track display:** when "use chapter track" and "use total track" are both on, the total-track display sits just above the deck. A sibling rule (`.fullscreen .total-track ~ .title-author-texts`) raises the floor to 249px: the display's 215px offset, its height at the default font scale (19.2px line + 4px channel) and 10px for the plate and chassis. It has to be a px value: the system font scale zooms text in the WebView, but `rem`/`em` lengths stay at 16px, so CSS cannot follow the line's growth.
- **Text is unchanged and neutral:** no LLAMA rule colors the title or author, and no amber or phosphor green goes on the readout. The title can be the live current chapter or the static book title (fallback), and the author is static, so one color rule would mean different things at different times. The enclosure carries the equipment character. Fonts, sizes, line heights, the chapter/book-title selection, the marquee and the author truncation are untouched.

Measured on the emulator (412×842 CSS px, Book A paused; the before values are `733b34f8`):

| Font scale | Readout (x, y, w × h)     | Artwork bottom → readout        | Readout → deck | With the total-track display: readout → its top |
| ---------- | ------------------------- | ------------------------------- | -------------- | ----------------------------------------------- |
| 1.0 before | 41.2, 516.8, 329.8 × 50.4 | 19.7                            | 75.2           | 37.0                                            |
| 1.0 after  | 24, 526.8, 364.2 × 62.4   | 29.7 (23.7 past plate and ring) | 53.2           | 15.0 (11 past the plate)                        |
| 1.3 before | 41.2, 501.6, 329.8 × 65.5 | 4.5                             | 75.2           | 31.2                                            |
| 1.3 after  | 24, 511.6, 364.2 × 77.5   | 14.5 (8.5 past plate and ring)  | 53.2           | 9.2 (5.2 past the plate)                        |

- Every role outside the block is unchanged before and after: artwork, deck, seek and total-track wells, controls, play (65×65), jump and chapter keys, the seek touch target's size and row, the top-bar chevron and playback-method label. The 22-role Dark-vs-LLAMA geometry check stays at 0 differences, and the mini-player stays 120px with its metadata block identical across all four themes. Dark, Black and Light are identical to `733b34f8` at both font scales.
- Long chapter titles and long book titles (fallback) scroll in the marquee inside the 340px content box. The marquee's fade mask stays inside the well. Long authors keep their ellipsis. With an empty author the readout holds the title only. Arabic, Hebrew, Japanese and Thai fit inside the readout with no clipped marks at both scales. Tapping the readout still opens the item page.
- Rendered contrast rises because the face is darker than the chassis: title 16.7:1 (13.7:1 before), author 9.0:1 (7.9:1 before), at both font scales.

**Short screens with the total-track display (Phase 4E-R1).** Below about 792 CSS px the artwork is height-limited (`coverH = height - 400`), so its bottom sits a fixed 320px above the viewport bottom. The total-track display's top is at 244px at font scale 1.3, which leaves a constant 76px band. The full readout needs 77.5px plus its plate. A sweep at 412px width, font scale 1.3, total track on, showed the plate overlapping the artwork frame below about 815px: −2.4px at 812, −7.3px at 802, and a constant −12.4px from 793 down to 679. At 821 it cleared by only 2.1px.

- One media rule handles it: `EQUIPMENT_MEDIA_RULES`, emitted by `presentationMediaRules` through the Tailwind theme plugin, under the LLAMA root only. It applies to `@media (orientation: portrait) and (max-height: 824px)` on the total-track readout selector only. The 842px reference screen, total track off, landscape, the collapsed player and Dark, Black and Light are outside it.
- `METADATA_READOUT_COMPACT` keeps the readout's face, well, radius, 24px column, horizontal inset and neutral text. Only the vertical extent changes:
  - the plate becomes side rails (a 1px seam all round, with the plate and its lips offset sideways only);
  - the vertical inset is replaced by a fixed band height with the text centered in it (`display: flex`, column, centered);
  - `bottom: 247px` puts the readout 2px plus the seam above the total-track display at font scale 1.3;
  - `min-height: min(calc(50% - var(--cover-image-height) / 2 - 133px), 70px)` is the band under the artwork's 2px frame with 3px of chassis (67px where the artwork is height-limited), capped at 70px above that.
- It uses `min-height` and never `height`, so text larger than the band grows the box upward and is never clipped. Font scale is not visible to CSS, so 1.0 gets the same band, with about 8px around the text.
- Results, at 412 wide with the total track on:
  - at font scale 1.3, from 822px down to 669px: at least 3px of chassis under the artwork frame (3px wherever the artwork is height-limited, 15px at 822px) and 2px above the total-track display;
  - at font scale 1.0: 3px and 7.8px.
  - With the total track off, the same screens keep the Phase 4E readout (23.6px under the artwork, 24px above the deck at 1.0).

**Landscape with the total-track display (Phase 4E-R2).** In landscape the block sits in the right half (the player's own `left: 50%; width: 50%`, both `!important`), between the top-bar controls and the total-track display. With the total track on at font scale 1.3, the 249px floor put the readout at y 40.8–115.2 (915×364). That covered the lower part of the cast and overflow controls (y 24–63): 15 of 25 hit points on each resolved to the readout, so a tap there opened the item page. Under the controls, above the total-track display, there are only 57px at font scale 1.3, less than the 62px of landscape text, so no vertical placement or compaction fits there.

- A second media rule (`(orientation: landscape)`, the same total-track readout selector) adds `METADATA_READOUT_LANDSCAPE`: `margin-left: 24px` and `max-width: calc(50% - 135px)`. `max-width` still limits the `!important` width.
- The readout keeps its vertical placement, paint and text. It starts in line with the seek well (x 481.5) and ends 111px short of the right edge: the cast control's 64px offset plus its 39px width at font scale 1.3, the 4px plate and 4px of chassis. At 915px wide that is 322.5px.
- Results at 1.3: the cast and overflow controls resolve to themselves on every point outside the DIRECT label's box. Real taps on their lower parts open the overflow menu and reach the cast handler (before the fix, both opened the item page). A tap on the readout still opens the item page. The total-track and deck clearances are unchanged (5.0 and 49px). Font scale 1.0 gets the same column, with the readout below the controls' row.
- Long titles, Arabic and Thai scroll in the marquee in the narrower readout, and long authors keep their ellipsis.
- With the total track off, the landscape readout keeps the right half's full width and runs to the screen's right edge, as in Phase 4E (deferred). It stays 16.8px below the controls at 1.3 and 10px above the deck.
- Pre-existing and not changed: the DIRECT playback-method label's box spans the full width (y 16–31, 16–35.5 at font scale 1.3) above the top-bar controls, so taps on their upper part reach the label. In landscape the artwork (from y 50) also covers the lower part of the collapse control. Dark behaves the same.

`tests/theme-llama.test.mjs` keeps the contract:

- the readout primitive is paint-only and built from the shared well;
- geometry properties appear only on the two readout selectors, and only the named ones, with exact values;
- the player's own geometry it rests on is unchanged;
- the title and author are never colored, amber or phosphor;
- text contrast holds on the face;
- the rules compile under the LLAMA root only;
- the short-screen variant is a LLAMA-only media rule with exact values that changes only the vertical extent;
- the landscape variant is a LLAMA-only media rule with exact values that changes only the horizontal extent.

## LLAMA primary Play/Pause key (Phase 4H)

LLAMA only, except for one theme-neutral fix. Phase 4F chose a dark face with an amber legend for Play/Pause, and Phase 4G chose a squared 4px equipment key with a larger glyph (the user's option E). Phase 4H implements both, in the full player, the mini-player and the widget (see `docs/widget-architecture.md`). The real app stays authoritative for geometry: the 65×65 and 40×40 boxes, their positions and the transport deck are unchanged. Only the painted shape and the glyph size inside the box change.

**Seek-loading spin (theme-neutral, its own commit).** While a seek was pending, `animate-spin` sat on the outer `.play-btn`, so the whole face turned with the autorenew glyph. In LLAMA the steel highlight and shadow orbited, and the button's hit-test box grew while it turned (65 → up to 86 CSS px across themes). The class now sits on the glyph span, so only the glyph spins and the face stays still. `seekLoading`, seek and playback behavior, click handling and the boxes are unchanged. In Dark, Black and Light, rest, pressed and loading are identical to `5a18d5d2`. The only visible change is that the face no longer turns.

- **Face (`PRIMARY_KEY`):** the dark player-key face. It is a translucent `surface.recessed` layer (72%; 85% collapsed, `PRIMARY_KEY_MINI`) over the player's own inline fill (`surface.raised`), under the player-key sheen. It is a background layer because the player sets the fill inline. It sits in the same family as the jump and chapter keys.
- **Shape:** squared on `RADIUS.key` (4px). No new radius.
- **Primary bezel:** the player key's lit upper-left and dark lower-right inner edges with the faint second highlight, a 1px dark channel, a lit return ring outside it (`0 0 0 2px`, edge-light at 30%) and a `0 3px 5px` drop (player keys use `0 2px 3px`). No screws, texture, gloss, glass or LEDs.
- **Pressed (`PRIMARY_KEY_PRESSED`):** the sheen and drop go, the face deepens (86%; 94% collapsed), the inner edge inverts with an inner shade, and the channel and ring stay. Nothing moves or resizes.
- **Legend:** Play, Pause, the seek-pending autorenew glyph and the loading spinner all take `PLAYBACK_LEGEND`, the playback amber, as one legend position. The spinner was `#262626`, about 1.15:1 on a dark face. Green stays reserved for readouts.
- **Glyph size (the recipe's second authorized geometry):** `PRIMARY_GLYPH_SIZE` sets 2.8rem (44.8px, from 33.6px) in the full player and 1.875rem (30px, from 24px, the jump glyphs' size) collapsed. It sizes the glyph inside the unchanged box only. The 4E geometry test now allows exactly these two `font-size` selectors besides the readout.
- **Primary status without color:** larger box, central position, squared key, double bezel with return ring, deeper drop and a large solid glyph. A grayscale render keeps Play/Pause clearly primary against the jump, chapter and utility keys.

Measured on the emulator (412×842 CSS px, Book A paused, same session before and after):

| Surface / state            | Ink (CSS px), % of box | Clearance to bezel | Amber vs face in the glyph rows | Face top (no ink reaches it) |
| -------------------------- | ---------------------- | ------------------ | ------------------------------- | ---------------------------- |
| Full, Play                 | 18.7 × 22.9, 29 × 35%  | 18.6               | 8.8:1                           | 5.8:1                        |
| Full, Pause                | 22.1 × 25.9, 34 × 40%  | 17.0               | 8.6:1                           | 5.8:1                        |
| Full, seek-pending         | 34.7 × 30.1 (turning)  | 12.9               | 8.3:1                           |                              |
| Full, loading spinner      | 15.6 × 15.6            | 20.1               | 9.2:1                           |                              |
| Full, pressed (Play/Pause) | as resting             | as resting         | 10.5–10.6:1                     | 10.5:1                       |
| Mini, Play                 | 12.6 × 15.6, 31 × 39%  | 9.8                | 9.5:1                           | 7.4:1                        |
| Mini, Pause                | 14.9 × 17.5, 37 × 44%  | 8.7                | 9.4:1                           | 7.4:1                        |
| Mini, seek-pending         | 24.4 × 19.8 (turning)  | 5.6                | 9.3:1                           |                              |
| Mini, loading spinner      | 15.6 × 14.9            | 7.9                | 10.0:1                          |                              |
| Mini, pressed (Play/Pause) | as resting             | as resting         | 11.0:1                          | 11.0:1                       |

- Every legend state clears the gate's 7:1 target against the face behind it. The sheen's lightest band, the top 3px of the face, measures 5.8:1 (full) against the amber, but no glyph ink can reach it: the glyph box starts 10px down. The test checks the token math at the glyph box's top edge (7.65:1 full, 8.3:1 mini).
- Play's ink sits 2.1px (full) and 1px (mini) right of center: that is the icon's own optical offset, unchanged from before. Pause has more ink (8.4% vs 6.1% of the full box) and still keeps 17px of clearance.
- Geometry: the 65×65 and 40×40 boxes, every jump and chapter key, the 22-role Dark-vs-LLAMA check (0 differences, and 0 against `5a18d5d2`) and the 120px mini-player are unchanged. During a seek the button's box stays 65×65 / 40×40 and the glyph turns around the key's center. In the mini-player the ring leaves 4–5px of visual gap to the jump keys.
- Dark, Black and Light: identical to `5a18d5d2` in rest, pressed, loading and geometry (computed paint and bounds compared). Only the seek spin moved to the glyph.

`tests/theme-llama.test.mjs` keeps the contract:

- the spin is on the glyph and not on `.play-btn`;
- `PRIMARY_KEY` is squared on `RADIUS.key`, with its dark face, exact bezel layers and exact pressed treatment;
- amber covers Play/Pause, seek-pending and the spinner, and never green;
- the glyph sizes are exact and are the only geometry besides the readout;
- the 7:1 token math holds;
- the rules compile under the LLAMA root and outrank the player's own glyph rules;
- Dark, Black and Light get no rules.

Every new contract was mutation-tested (round radius, glyph sizes, amber, face depth, drop, inner shade, return ring).

**Not the final fidelity gate.** Phase 4H covers only the primary control. The rest of the non-widget control area moves closer to the mockup in later, separately authorized passes, listed in `docs/future-work.md` under "LLAMA non-widget control fidelity (after Phase 4H)".

## LLAMA control deck (Phase 4J and the Phase 4 finishing pass)

LLAMA only, except for one theme-neutral fix (the playback-speed value, below). Phase 4J gave the secondary keys deliberate equipment faces. Physical S26 testing then found two failures and judged the keys still undersized against the mockup, and the Phase 4 finishing pass recomposed the deck. The real app stays authoritative for behavior, order and accessibility. The artwork, metadata readout, seek display, the player's 200px deck and the mini-player's 120px height are unchanged.

**Keys are their own boxes.** Every key's own box is its hardware: a fixed CSS px size (`DECK`, `KEY_BOX`) with the face painted on the element itself. The visible key and its touch region are the same box at every font scale. There is no pseudo-element, z-index, isolation or pointer-events trick in any player rule. Phase 4J first drew enlarged `::before` faces around glyph-sized boxes, which needed a `content: ''` exception in the security test; that exception is gone and the recipe has no quoted value at all. Fixed boxes also keep the deck's geometry independent of the font scale. Before, the system font size grew every glyph box and with it the keys, which at large scales ran into the seek track and each other.

| Role                                               | Size (CSS px)                 | Finish                                                                                                                                        |
| -------------------------------------------------- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Primary Play/Pause (Phase 4H)                      | 65×65, lifted 3px in the bank | `PRIMARY_KEY` (unchanged)                                                                                                                     |
| Full transport: chapter start/end, both jumps      | 60×60, one bank, 8px gaps     | `TRANSPORT_KEY`: the primary's face, sheen, inner bevel and channel, a weaker lit return (0.18, primary 0.3) and a shorter drop. Amber legend |
| Utility: queue, bookmark, inactive sleep, chapters | 54×52                         | `UTILITY_KEY`: same family, restrained bezel (no lit return). Amber legend (`UTILITY_LEGEND`, the transport keys' token) at regular weight    |
| Readouts: speed, running sleep timer               | 52 high, width from 54 up     | `READOUT_BOX`: recessed display, phosphor green text, never a raised face                                                                     |
| Top chrome: collapse, cast, overflow               | 44×44, on one line 20px down  | `CHROME_KEY`: the utility family, neutral legend (`CHROME_LEGEND`, primary text at 0.8), 2rem glyph                                           |
| Playback method (Direct/Local/Transcode)           | text size, centered on line   | `PLAYBACK_METHOD_READOUT`: small recessed green display                                                                                       |
| Collapsed transport: both jumps                    | 34×34                         | `TRANSPORT_KEY_MINI`: deeper fill on the darker collapsed deck. Amber legend                                                                  |

**Composition.** The five transport keys are packed and centered as one bank: 4×60 + 65 + 4×8 = 337px of the 364px content width at 412px. The primary keeps its box and margin 0, lifted 3px (`top`, so nothing moves around it). The seam between the two panels sits 5px lower than the player puts it (`DECK.seam`): the transport row grows by a 5px bottom padding as its offset shrinks from 78px to 73px, so the bank keeps its place and the deck its 200px. The secondary keys clear the seam by 7.5px and the primary by 8px, which takes their drop shadows (6px and 8px) off the seam line; before, 2.5px and 3px. The utility keys sit centered in the 73px section below it (11px above, 10px below; no padding, the row's own offset), two distinct equipment panels as in the mockup. The bank won over the Phase 4J layout (46×50 faces around small boxes, spread by `justify-between`), a 54×56 bank with 12px gaps (still scattered) and a 62×62 bank (heavier, no visible gain). Runtime prototypes were compared in matched contact sheets in color and grayscale, at font scale 1.0 and 1.3, and playing, paused, pressed, disabled and loading.

**Legends.** The jump arrow is 2rem with the bold, dynamic duration (never a fixed "10s"), and the two sit 2px lower so they are centered as one legend in the key. At font scale 1.3 the legend is 31.6×51.8 and 4.1px from the key's top and bottom (2px clear of the bevel), at 1.0 24.4×40. The chapter glyph is 2.25rem (18.7px of ink at 1.0, 24 at 1.3); that is safe now that the glyph no longer sizes the key. The collapsed jumps' arrow is drawn at a fixed 0.78 scale, 1px lower (23.2×26.3 in the 34×34 key at 1.3). The WebView's text zoom multiplies every computed font size (rem, px, `vw` and `min()` alike, measured) and ignores `text-size-adjust`, so a legend cannot be capped by font size. At much larger One UI steps (2.0 measured) the zoomed legends exceed their keys, but no key moves or overlaps another.

**States.** Pressed (`*_PRESSED`, every tier and the chrome keys): the sheen and drop go, the face deepens, the bevel inverts with an inner shade, nothing moves. Unavailable (`key-disabled`): a sunken socket in the same place (`KEY_SOCKET`) with a strongly dimmed legend (`KEY_SOCKET_LEGEND`, 1.9:1), unavailable by shape and luminance. A disabled key's handler ignores taps, as before. The queue key's "Q" badge sits 3px inside its key's lower-right corner with a thin dark ring. The filled bookmark state is unchanged. Semantics (the LLAMA equipment rule, below): amber is an illuminated physical-control legend, green is a readout, neutral is the top chrome.

**Equipment color rule (final decision of the finishing pass).** The Phase 4B rule "amber is playback only" is superseded for the LLAMA control deck. Amber (`progress.played`, `--color-track-cursor`) is the illuminated legend of every physical deck key: the transport keys, the primary and the utility keys (queue, bookmark, inactive sleep, chapters). Green (`accent.primary`) is information on a readout or live display: speed, the running sleep timer, the playback method, the seek and metadata displays. Neutral stays on ordinary text and on the top chrome (navigation and menus, not deck controls). The deck no longer needs color to separate transport from utility: the tiers differ by size (65, 60, 54×52), bezel strength, row and glyph weight (the transport glyphs carry a hairline stroke, the utility glyphs the font's regular weight), as in the approved mockup, which lights its physical controls amber and its displays green. State still reads by shape: the bookmark key fills when the item has bookmarks, the queue key's "Q" badge keeps its neutral ring and letter, the active sleep timer replaces the moon key with a green readout, and an unavailable key (chapters with none) is a sunken socket with a strongly dimmed legend, never amber. Rendered contrast of the utility amber: 10.4–10.5:1 at rest and 10.9:1 pressed (transport 10.4 and 10.9). LLAMA only: Dark, Black and Light are unchanged, and `state.success` (finished, complete) and `state.success-action` (the fill of actionable success controls) remain separate concepts, untouched by this rule.

**Top chrome.** Collapse, cast and overflow keep their clicks on the same elements, now the whole key: collapse 48px to 44px, cast and overflow 30px (39px at 1.3) to 44px. Cast keeps its 64px slot, 4px from the overflow key, so the landscape metadata readout (Phase 4E-R2) still ends before it (readout 111px from the right edge, plate 4px, cast face 108px).

**Divider and utility size (final refinement after the S26 functional pass).** On the S26 the seam still sat too close under the 60px bank, and the 50×46 utility keys looked undersized beside it. Runtime prototypes, compared in matched contact sheets: the seam lowered 3, 5 and 7px, and with it utility keys of 50×46, 52×50 and 54×52. A 3px drop left the keys' drop shadows on the seam; 5px is the smallest that seats the bank, and 7px squeezes the utility section (10px and 9px margins with 54×52 keys). 54×52 keys read as hardware beside the bank and stay a clear tier below it (65, 60, 52), with 23.5px between them. The worst readout case at font scale 1.3 ("1.75x" and a running sleep timer, 104px and 67px wide) leaves 7.8px between the utility controls, about the bank's own gap. The straight seam now looks right, so the notch around the primary stays unimplemented.

**Physical S26 failures (root causes).**

- **Raw speed and no recovery after a live font change (pre-existing, amplified by Phase 4J).** The native player reports its speed as a 32-bit float, so 1.4x arrives as `1.399999976158142`. The player copied it raw whenever the UI reattached to a running native session. A system font-size change triggers exactly that: `fontScale` is not in the activity's `configChanges`, so the activity and its WebView are recreated while playback continues. Every font change in either direction reattached and kept the raw value until the app was swiped away. The label overflowed the secondary row in every theme (since the reattach feature, `ae0ffa16`), and in LLAMA the Phase 4J readout window ran over the utility keys. Fix (theme-neutral, `utils/playbackRate.js`): speeds entering from native (reattach state and the speed-changed event) are normalized to two decimals, the precision of every offered speed, and the label is concise ("1x", "1.4x", "1.75x"). Verified with live changes 1.0 → 1.3 → 2.0 → 1.0 without restarting the app: "1.4x" throughout and the original layout restored.
- **Playback Speed did not close above or below its panel (pre-existing, every theme).** A closer S26 review narrowed the speed-modal failure: the speed rows work, and taps left and right of the panel close it, but taps directly above or below it did nothing. The shared modal (`Modal.vue`) closes only when a tap lands on its own `.modal-bg` backdrop, and its content box is the modal's width (200px) by the full screen height. The empty space above and below a centered panel is therefore inside that box, on the full-size column wrapper, not on the backdrop. Every other modal using the wrapper (sleep, chapters, bookmarks, Up Next and the app's other list modals) closes from it with `@click="show = false"` while its panel stops its own clicks; Playback Speed was the only one without the handler. It now closes from the column through `modalInput`, so a speed set with the -/+ stepper is saved as on a backdrop tap. Real taps on the emulator at font scale 1.0 and 1.3, three rounds each, close it left, right, above, below, far above, far below and at the four outside corners; speed rows, the stepper and the footer stay interactive. `tests/modalDismissal.test.mjs` keeps every column wrapper closing and every panel stopping its clicks. The earlier emulator checks had tapped beside the panel only, which is why they did not reproduce it.
- **Queue backdrop (found while testing, pre-existing, not changed).** The Up Next modal is 95% wide, so its side backdrop is a ~10px strip. A tap there is moved into the panel by Chromium's touch adjustment and does not dismiss it. Dismissal works above and below the panel and with the close key.

**Measurements (emulator, 412×842 CSS px).**

- **Contrast (rendered):** amber 10.4:1 at rest and 10.9:1 pressed, duration 10.4–10.6:1 (1.0 and 1.3), collapsed amber 11.2:1, utility 9.7–9.9:1, readout green 12.3:1, socket 1.9:1.
- **Hit testing:** every key's hit region is its box (at 1.3 a key's zoomed glyph adds at most 2.25px, inside the gaps). The smallest gap is 8px inside the bank and 18.5px between rows. Real taps 2px inside every corner act for all transport keys and both collapsed jumps; a disabled key does nothing. A tap in an 8px bank gap goes to the nearest key (Android touch adjustment).
- **Collapsed player:** with fixed 34px keys its controls no longer grow with the font scale, so at 1.3 the forward key ends at 406px instead of 416px (the old responsive overflow is gone). The primary moved 4px right (its box is unchanged). The mini-player is 120px.
- **Geometry:** the 22-role Dark-vs-LLAMA check differs only in the two deliberate roles (`fullControls`, `miniControls`). Artwork, metadata readout, seek display and deck geometry are unchanged, and Dark, Black and Light are pixel-identical.

`tests/theme-llama.test.mjs` (Control deck section) and `tests/playbackRate.test.mjs` keep the contract:

- the fixed px boxes per tier and the faces painted on them;
- the packed bank (gap, lift, fits 412px, primary box frozen);
- no pseudo-element, z-index, isolation or pointer-events in player rules;
- the hooks;
- the pressed and socket states;
- amber, neutral and green roles;
- dynamic durations and legend sizes;
- the recessed readouts;
- contrast;
- LLAMA-only compiled CSS;
- the concise, normalized speed on both native entry points.

Twenty-five mutations of these contracts all fail the suite.

## LLAMA B+ faceplate (Phase 6B)

LLAMA only. The portrait full player becomes one equipment faceplate of stacked modules: a restrained top chrome plate, a mounted artwork bay, the recessed metadata readout, a dedicated recessed seek/time module and one console plate around the unchanged transport and utility banks, with controlled seams instead of open chassis. The design came from runtime prototypes (Phases 6A–6A.3); this section is the production contract.

**Architecture.** One presentation projection, `theme/playerLayout.js`, owns every number. It follows the `presentation.cover-color` pattern (`theme/coverPresentation.js`): the player reads the validated theme once (`presentationTheme`) and passes it to both projections. `fullscreenLayout(theme, viewport)` returns the artwork width plus hook classes, `--faceplate-*` variables and which decorative plates to render. The player puts the classes and variables on `#streamContainer` only while the full player is shown, and renders the plates (`.player-plate-top`, `-seek`, `-console`: `aria-hidden`, `pointer-events: none`, so never touch targets). Its own stylesheet consumes them (geometry and type). The LLAMA recipe in `theme/presets.js` only paints the plates, the artwork bay and the deck (`FACEPLATE_PLATE`, `FACEPLATE_TOP_PLATE`, `ARTWORK_BAY`, `FACEPLATE_DECK`). There is no theme-id check, no duplicated player and no pseudo-element plate (the Phase 4J rule against pseudo-element faces stands).

**Paths.**

| Path        | When                                                                                              | Geometry                                                                   | Presentation                                                                                                                                                                     |
| ----------- | ------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `standard`  | every theme without `presentation.finish: 'equipment'` (Dark, Black, Light)                       | previous, exactly (`legacyCoverWidth` is the old formula, moved unchanged) | none: no classes, no variables, no plates                                                                                                                                        |
| `faceplate` | LLAMA, portrait, one rail (Chapter Track off, the default)                                        | full B+ (below)                                                            | plates, bay, condensed readouts, 24/22px times                                                                                                                                   |
| `compat`    | LLAMA, portrait, two rails (Chapter Track + Total Track), or where B+ would cost too much artwork | previous                                                                   | condensed type, 16px times, top plate where it clears the artwork; with two rails the dual-track instrument above the console, otherwise the console plate around the whole deck |
| `landscape` | LLAMA, landscape                                                                                  | previous two-column                                                        | condensed type, 16px times, console plate; no top plate, no bay                                                                                                                  |

**Budgets (CSS px, each sized for font scale 1.3).** Above the artwork: top plate bottom + seam + bezel + 2px ring. Below: deck + seam + 4px readout plate + band + 4px + seam + bezel + ring. The artwork takes what is left, capped at the previous width allowance minus 8px for the bay.

| Tier    | Top plate | Seam | Bezel | Band | Deck | Times |
| ------- | --------- | ---- | ----- | ---- | ---- | ----- |
| regular | 12–72     | 10   | 7     | 76   | 247  | 24    |
| compact | 12–70     | 6    | 5     | 72   | 239  | 22    |

The console plate is the deck's bottom 161px (the banks are unchanged); the seek/time module sits above it with its well inset 10px. On wide screens the console plate follows the 414px control column. Each bank is vertically centered in its own framed section (S26 correction): the transport section runs from the plate's top edge to the engraved divider (which stays where the deck puts it), and the transport row is centered there by its key envelope (the lifted Play key included), 11.25px above and below. The utility section runs from the divider to the plate's bottom edge (6px above the deck bottom), with the utility row 7.5px above and below; the same utility centering applies on the two-rail and landscape paths, whose console plate has the same bottom edge. Key sizes, their horizontal layout, the divider and the plate are unchanged (`FACEPLATE_TRANSPORT_BANK`, `FACEPLATE_UTILITY_BANK`).

**Responsive rules.**

- **Compact** applies where the regular budget would make the artwork height-limited: a property of the artwork, not a device height. At the boundary both tiers give the full width, so the artwork never jumps.
- **Tall:** room left over when the artwork is width-limited is split into three equal seams (above the bay, bay to readout, readout to seek module).
- **Floor:** if the faceplate would leave less than 0.93 of the previous artwork width (about 13.5% of its area), the `compat` geometry is used instead. The approved costs are about 4% (412×842), 7% (412×799 and 762×986) and 12% (412×734).
- **Two rails:** the second (whole-book) rail would cost the artwork 21–35% inside the faceplate, so Chapter Track + Total Track keeps the previous geometry with the presentation only. Settings, defaults and rail semantics are unchanged.
- **Two rails are one dual-track instrument (S26 correction).** Before, the book rail was a thin strip of its own 215px above the screen bottom, with smaller times (12.8px) and a 4px rail, separated from the chapter rail inside the console by 23px, so it read as a foreign strip. Now, with both rails (`faceplate-dual`, portrait and landscape), the two existing rows are two equal 30px sections stacked with no gap (book above, chapter below), bottom-anchored above the console from `playerLayout.FACEPLATE.dual`. One plate (the seek plate, extended over the book section) frames them, and they are one recessed well split by an engraved seam: `DUAL_TRACK_UPPER` ends on the seam's dark line, `DUAL_TRACK_LOWER` starts on its faint light line (ENGRAVED_SEPARATOR's colors), with square inner corners (`RADIUS.none`) and the key radius outside. Both sections use 16px condensed green times on the same 10px inset and a 6px rail. The console plate's top edge (153.5px above the deck bottom) centers the transport bank where it already sits (7.5px above and below), so no key moves; the utility bank is centered as on the other paths. The instrument's top stays at least 6px below the Phase 4E readout floors. At 412×842: book 616.8–646.8, chapter 646.8–676.8, plate 610.8–682.8, console from 688.8. Progress, seek (only the chapter rail is seekable, as before) and the rails' behavior are unchanged; no labels were added. Book-only and chapter-only keep the normal faceplate.
- **Landscape:** previous geometry. The bay and top plate are omitted because the artwork sits under the chrome line there.

**Artwork scaling (required).** The cover component pins `min-width` to its `width` prop and `.cover-wrapper` hides overflow, so a component wider than the bay crops instead of scaling (the first runtime prototype did exactly that). Production sizes the component, the bay and `--cover-image-*` from the same layout width, and under the faceplate the component also gets `width/height: 100%`, `min-width: 0` and `max-width: 100%` (the landscape rule's technique). Square, 1.6 portrait and placeholder covers were checked: the image always fills the bay exactly.

**Typography.** Readouts (times, speed, sleep, playback method) and title/author use the system `sans-serif-condensed` family with tabular figures. No font asset is added and the global `@font-face` situation is unchanged (see Typography findings). On the emulator it renders as condensed Roboto; Samsung's mapping must be checked on the S26. Times: 24px regular, 22px compact, 16px compat/landscape (the 200px deck there: anything larger pushes the unchanged seek hit target onto the transport keys at font scale 1.3). Title and author stay neutral in color; the title's line-height is 1.25 inside the fixed band.

**Measurements (emulator, production build, Book A at 94.6s).**

| State              | Path              | Artwork             | Readout | Seek module  | Console | Times |
| ------------------ | ----------------- | ------------------- | ------- | ------------ | ------- | ----- |
| 412×842            | faceplate         | 384 at 93–477 (−4%) | 503–579 | 601–662      | 681–836 | 24    |
| 412×799            | faceplate compact | 378 (−7%)           | 478–550 | 566–625      | 638–793 | 22    |
| 412×734            | faceplate compact | 313 (−12%)          | 413–485 | 501–559      | 573–728 | 22    |
| 412×933            | faceplate         | 384, seams 43/43/48 | 564–640 | 692–753      | 772–927 | 24    |
| 762×986            | faceplate compact | 565 (−7%)           | 665–737 | 753–812      | 825–980 | 22    |
| 412×842, two rails | compat            | 392 (0%)            | 527–589 | well 650–672 | 644–836 | 16    |
| 915×364            | landscape         | 433×279 (unchanged) | 94–154  | well 172–194 | 166–358 | 16    |

Every state matches the approved runtime prototype within 1px. Key boxes are unchanged everywhere (60×60 transport, 65×65 primary, 54×52 utility), and the seek hit target is 28×32 with 22–35px clearance above the keys (6px at 1.3 on the previous-geometry paths, where it was 2px before). At font scale 1.3 the band holds the title and author and the seek module stays clear of the console (6px at least). Dark, Black and Light are unchanged (no classes, no variables, no plates, the previous artwork formula), and the mini-player stays 120px.

`tests/player-layout.test.mjs` keeps the contract: the legacy no-op over a grid of viewports and aspect ratios, path selection, tiers and the boundary, tall seams, the floor, the 1.3 budgets, the no-crop guarantee, plates being decorative, the faceplate stylesheet never touching controls, the seek target or the mini-player, and the LLAMA-only paint.

**Retained findings (out of scope, unchanged).** With Chapter Track on: toggling it while paused leaves the second rail's played bar at 0% until the next playback tick; a book without chapters shows two identical whole-book rails; the menu labels do not explain the pair. Wide screens at font scale 1.3 with both rails overlap the artwork and readout by about 12px (previous geometry, before 6B too). Display-size changes can make the home-screen widget's `APPWIDGET_UPDATE_OPTIONS` handling ANR. See `docs/future-work.md`.

## LLAMA mini-player (Phase 7B)

LLAMA only, collapsed player only. The mini-player takes the accepted B+ full player's type and readout language in reduced form. It stays a visibly simpler, persistent control surface, not a small faceplate. It is Candidate B of the Phase 7A runtime audit, implemented as prototyped: type and paint only, with no geometry. The panel stays exactly 120px, and the artwork (46×46), the title/author block, the three keys, the seek row, the rail and every offset and touch target keep their bounds at font scales 1.0 and 1.3, on narrow (343px), standard (412px), wide (762px) and landscape screens.

**What changes** (recipe rules in `theme/presets.js`, all under `#streamContainer:not(.fullscreen)`, so the full player never matches them):

- **Metadata** (`MINI_TITLE_TYPE`, `MINI_AUTHOR_TYPE`): the system `sans-serif-condensed` face the faceplate uses (`CONDENSED_FACE`), 0.01em / 0.02em spacing. Sizes, color (neutral), scrolling and truncation are unchanged. The condensed face fits about 9% more title and 7% more author in the same slot.
- **Times** (`MINI_TIME_TYPE`): condensed, tabular figures, 14px (was 12.8px), still the green readout color. The line height (1.3714286) keeps the previous line box exactly: 19.2px, and 24.96px at font scale 1.3. So the time row and the rail under it do not move.
- **Readout strip** (`MINI_READOUT_STRIP`): the existing seek row (`#playerTrack`) gets the recessed face with a shaded top edge, in place of the engraved seam that was drawn at the same line. Two offset shadows of the row's own box carry the face 6px below the rail and end it with a 1px lit line. Shadows are paint outside the box, so nothing moves and no hit area is added. The rail stays non-seekable in the mini. The times and the amber rail now read as one compact readout.
- **Artwork** (`MINI_ARTWORK_MOUNT`): the existing frame plus one soft mount shadow (`0 2px 4px 2px`). No bezel, ring, size, crop or radius change.

**One template hook.** The seek row's two times used an inline `font-size: 0.8rem`, which a recipe rule cannot override. They now carry a `track-time` class, and the player stylesheet sets the same 0.8rem (`#playerTrack .track-time`). Every theme computes the identical size. The full player's faceplate times still win there through their existing `!important` rule. The book rail's times keep their inline style.

**Deliberately not transferred** (Phase 7A, Candidates C and beyond):

- A console plate behind the keys: cramped, 2px from the screen edge, a third framed box.
- A recessed metadata well: Phase 4E found it infeasible without geometry.
- An artwork bay or ring.
- An inset seek well: at font scale 1.3 its top edge lands on the artwork.
- A top plate, faceplate geometry, larger time line boxes, new controls, labels or decorative elements.
- Removing the empty ~35px band under the rail.

**Clearances retained, not changed.** At font scale 1.3 the existing mini geometry leaves only about 2px between the artwork and the seek row, and the metadata block extends about 1.2px above the panel. The default "Book | Chapter" title already scrolls at 412px. Candidate B does not worsen any of these: the strip's top edge is where the seam was. See `docs/future-work.md`.

Dark, Black and Light get no rules; their mini-player and full player are unchanged. `tests/theme-llama.test.mjs` (Phase 7B tests) pins the rules to the frozen primitives and the collapsed player, the line-box arithmetic, the paint-only strip and mount, the template hook, the 120px geometry anchors, the absence of any Candidate C element, and the LLAMA-only compiled CSS.

## LLAMA browsing chrome (Phase 8B)

LLAMA only. Home, Library and navigation are the content environment around the frozen equipment (the Phase 6B full player and the Phase 7B mini-player). They take the players' language in restrained form and stay content-first: artwork dominates, and only real controls become keys. This is Candidate B of the Phase 8A runtime audit, implemented as audited. Paint and type only: every box keeps its geometry, and no behavior changes.

**What changes** (recipe rules in `theme/presets.js`):

- **Navigation strip** (`#bookshelf-navbar`, `BROWSE_NAV_CHASSIS`): the content chassis (base surface, chassis sheen, faint lit top lip, dark seam below) instead of the brighter steel strip. The app bar, navigation and content now read as one chassis. Unselected icons rise from 5.0:1 to 7.2:1.
- **Selected tab** (`BROWSE_NAV_SELECTED`): a recessed dark key pressed into the strip, the players' key language. It keeps the accent underline and its text label (unselected tabs show an icon), so the state never reads by color alone.
  In podcast libraries every tab shows its icon and its label (BookshelfNavBar `labeled`, 2026-10-13); the selected tab keeps the key, the accent and a semibold label, and LLAMA sets every tab label in the condensed face.
- **Shelf headings** (`shelf-heading` hook, `SECTION_LABEL_TYPE`): the system `sans-serif-condensed` face, neutral, same size and weight, 0.03em spacing.
- **Shelf heading rule** (`shelf-heading-band` hook, `SHELF_HEADING_RULE`): the heading band paints an engraved line (a dark seam over a faint lit return) 6px above its bottom edge, so 6px above the artwork, inset 20px. It uses background layers only, with no element and no box. A rule on the band's edge would sit directly on the artwork.
- **Toolbar count** (`toolbar-count` hook, `COUNT_TYPE`): condensed with tabular figures, neutral (a count is not a live readout). Only its text box narrows (about 4px, about 6px at font scale 1.3) and the flex spacer absorbs it; no control moves. The series-name line keeps its face.
- **Search section labels** (`search-section-label` hook): the same condensed neutral label type.
- **List-row play button** (`list-play-key` hook, `LIST_PLAY_KEY`): the mini-player's key face (`TRANSPORT_KEY_MINI`, squared on `RADIUS.key`) with the playback amber legend, on the button's unchanged box. The legend measures 10.9:1, against 5.3:1 for the previous white-on-grey.

**Deliberately unchanged.**

- Navigation labels and the library-selector label. Both are sized by their content, so a narrower face would move the tabs (about 2px) and shrink the selector (about 5.7px), changing hit areas.
- Cards: artwork frame and shadow, amber progress, success when finished, neutral title and author (titles, authors and long metadata are never condensed).
- The app bar, its library-selector key, and the bare search, menu and Back glyphs.

**Rejected (Candidate C and variants).** Raised heading strips, green or amber headings, green counts, a green selected-tab label or accent ring, a recessed green library-selector name, and any card chassis. In the audit these read as decoration rather than meaning, and the app became noisy.

**Retained (not changed).** At font scale 1.3 the library selector already truncates its name. Alternative-view card titles sat tight against the artwork; that was fixed for every theme on 2026-10-10 (see below). See `docs/future-work.md`.

Dark, Black and Light get no rules. The players carry none of the hooks, and none of these rules reaches them. `tests/theme-llama.test.mjs` (Phase 8B tests) pins:

- the hooks;
- the frozen primitives;
- the selected tab's non-color cues;
- the typography freeze on the navigation, the library selector and the cards;
- the paint-only heading rule and play key;
- the absence of Candidate C;
- LLAMA-only compiled CSS.

## LLAMA faithful finish (2026-10-10)

The accepted LLAMA design. The faithful pass, material polish, final consistency pass and finishing corrections layer paint over the rules above in `theme/presets.js` (a repeated selector merges into its earlier entry). Dark, Black and Light get no rules. `tests/theme-llama.test.mjs`, `tests/llama-finishing.test.mjs` and `tests/closure.test.mjs` pin it.

- **Materials:**
  - brushed steel (`BRUSHED`, `STEEL_FACE`, `STEEL_PLATE_EDGE`, `steelKey`);
  - steel frames (`STEEL_FRAME_OUTSIDE`);
  - recessed glass (`RECESSED_WELL`);
  - green phosphor readouts (`LCD_TEXT`);
  - amber legends (`--color-legend`);
  - the graphite page with faint scan lines (`SCANLINES`, white 0.007 every 3px).
- **Colour roles:**
  - green: selection, focus, readouts and active states;
  - amber: ordinary action legends;
  - steel blue: keys and structural frames;
  - near-black: recessed displays and pages.
- **Dialogs and actions:**
  - every app dialog panel (`.modal .rounded-lg.bg-primary`) is `DIALOG_PANEL`, recessed black glass in a steel frame;
  - success-coloured primary buttons (`.btn.bg-success-action`) are `ACTION_KEY` steel keys, with pressed and unavailable states;
  - Android-controlled dialogs are not restyled.
- **Play legends:** browsing Play controls are amber through purpose-named hooks (`series-play-action`, `row-play-btn` glyphs, `episode-play-control`, `local-play-action`; `LIST_PLAY_LEGEND` = `PLAYBACK_LEGEND`), so no rule names a success class.
- **Home shelf covers:** the shelves scroll sideways and clip vertically, so there the frame is painted inside the cover (`ARTWORK_FRAME_INSET`) on each card's inert `card-frame` element. Elsewhere the outer frame stays.
- **Collapsed player:**
  - `theme/playerLayout.js` `miniLayout` gives the equipment finish `mini-compact` and `--mini-trim: 28px`: a 92px plate without the empty band under the seek strip; every module moves down by the trim;
  - `AudioPlayer.vue` publishes `--mini-player-height` (120px otherwise);
  - consumers: `#content.playerOpen` (`assets/app.css`), the reader toolbars and frames (comic, EPUB through `utils/miniPlayerHeight.js`, MOBI, PDF), the downloaded-book reorder bar and the player's swipe zone;
  - the downloaded-media lists fill their page instead.
- **Widget:** steel housing and socketed steel keys with amber legends (see `docs/widget-architecture.md`).

Shared fixes made alongside, in every theme:
- the offline bookmark key;
- refocus positions (`utils/livePosition.js`);
- series-toolbar wrapping;
- reader toolbar stacking;
- downloaded-media lists;
- grid titles under the cover (`utils/textScale.js`; the WebView scales font sizes but not px, em or rem lengths).

They are listed in `CHANGELOG.md`.

## E-ink (built-in)

A light theme for e-paper readers (tested target: Boox Note Air 5C, a color Kaleido panel). E-paper renders mid-tones, tints and translucency as dither, smears soft shadows and gradients, and ghosts whatever changes, so the theme is paper and ink with as little in between as possible. Cover art keeps its colors.

- It is a built-in like the others (`id: 'eink'`, label `LabelThemeEink`, `colorScheme: 'light'`, `eink` + `theme`), listed last in Settings. The non-EPUB reader uses the light shell.
- **Palette** (`theme/builtins.js`): paper `255 255 255` for every surface and the overlays (all `solid`), ink `0 0 0` for text, borders, the played line, the accent and every `state.*`, and two grays only: `204 204 204` for tracks, selected rows and selected toggle segments, `85 85 85` for secondary text (7.5:1 on paper). States are ink because colored fills dither on e-paper; they read by icon, label and shape. This makes E-ink the one theme where `state.success` and `state.success-action` are not the shared values (white on ink is 21:1).
- **`presentation.cover-color: 'theme'`**: the player and item header are paper instead of the cover's average color.
- **The `eink` recipe** (`EINK_RULES` in `theme/presets.js`, emitted only under `html[data-theme='eink']`) is paint-only: `background-color`, `background-image: none`, `box-shadow`, `border-color` and `color`, with values that reference token variables only. Lines are hard box-shadows (1px, no blur, inside the box or a ring around it), so no size or position changes. Tests reject gradients, translucent values, soft shadows and any other property.
  - shadows off: `box-shadow-book`, `box-shadow-md`, `box-shadow-progressbar`, Tailwind `shadow`, `shadow-sm`, `shadow-md`, `shadow-lg`;
  - gradients and textures off: the modal's top fade, the card play corner (solid ink), the wooden shelf (paper, ink divider, paper placards with ink frame);
  - lines: under the app bar and the bookshelf navigation, above the mini-player, on the drawer's page edge; 1px ink frames around card and player artwork, dialog panels and the library selector; the current navigation tab is inverted (ink with paper text);
  - modals: an opaque paper backdrop instead of a 75% one, so the titles and close glyph (white over the old dark fade) are ink; the selected option is a solid gray row instead of a translucent success wash; the drawer has no dimming backdrop;
  - progress: the seek channels are solid gray in an ink outline; the played line is ink in the settled and the pending seek state and on cover, list and chapter bars (finished bars are the ink success color), with a paper line separating cover bars from the art; the play button is a paper key with a 2px ink ring and ink glyph (its theme fill is paper);
  - toggle switches: ink outline and thumb edge (on is ink, off is paper).
  - visibility on paper: foregrounds the components hardcode for dark themes become ink (`text-gray-50`, yellow check and folder marks, the read-status key's white glyph, white borders, timestamp links, now underlined) or the secondary gray (`text-gray-200/300/400`, a finished episode's play control); they stay paper on dark boxes (author name overlay, card play corner). Borderless text fields get an ink outline, checkboxes an ink edge and mark, and buttons and icon keys on the ink state fills (`bg-error`, `bg-info`, `bg-warning`) a paper label.
- Geometry, layout and behavior are unchanged; `playerLayout` treats E-ink as standard. The home-screen widget has no E-ink palette: `WidgetTheme.fromThemeId` maps `eink` to the standard widget, which already draws a static snapshot.
- Animations are not part of the theme. The app honors the system "remove animations" setting for every theme (`prefers-reduced-motion`, `assets/app.css` and `utils/reducedMotion.js`), which Boox turns on. Spinners keep turning and the toast timer bar keeps running (its end closes the toast); pulsing and bouncing state indicators (a download in progress) become static, since their icon already says what is happening.
- Redraw frequency is not part of the theme either. The player menu's **Update once a minute** (`playerSettings.updateEveryMinute`, off by default) keeps polling the native position every second, since bookmarks and the refocus checks read it, but draws times as H:MM, the seek bars in minute steps and the sleep timer in minutes, and writes to the page only when one of those values changes (`utils/playerClock.js`). A step is a minute of what the elapsed readout shows (listening time when it is scaled by speed, media time otherwise; the buffered bar always steps by a minute of media), counted from the chapter start on the chapter track; the end of the book is drawn exactly. A drag is drawn exactly while the finger is down; a seek or a released drag is drawn at once, already at the coarse position playback will then report, so the bar and elapsed time are not redrawn a second later (the remaining time follows on the next poll).
- Not covered yet (no e-ink-specific rule): the remaining translucent tints (`bg-*/10` notices, `bg-black/…` badges on cards, `bg-fg/10` count badges), the podcast "new episodes" yellow badge and the stats chart. They are small; check them on the device.

## Content-derived color (intentionally not tokens)

- **Player and mini-player background:** the average color of the cover art (`utils/coverAverageColor.js`), with `coverBgIsLight` choosing dark or light icon colors. The theme overlays (`overlay.player`, `overlay.mini-player`) sit on top of it.
- **Item page header:** the same cover-average color, under `overlay.item-header`.
- These stay content-derived for Dark, Black and Light. From Phase 2, a theme opts out with `presentation.cover-color: theme` (LLAMA does); see [Presentation policies](#presentation-policies-phase-2).

## Remaining hardcoded presentation values

These are still hardcoded, by decision, and documented rather than forced into tokens.

- **Tailwind palette classes** (about 170 uses; e.g. `text-white`, `bg-black`, `bg-yellow-400`):
  - white/black text and badges over artwork, which should stay legible whatever the theme;
  - the yellow listening-progress bar on book cards (repainted amber for LLAMA only);
  - `bg-black-400` (not a real class; it has no effect).
- **`assets/app.css`:**
  - `.box-shadow-*` (`#111111xx`);
  - the bookshelf wood and alternative shelf styles (`.bookshelfRow`, `.bookshelfDivider`, `.shinyBlack`, `.altBookshelfLabel`; repainted by the equipment finish);
  - `.default-style a` link color `#5985ff`.
- **Components:**
  - `Modal.vue` top gradient (`from-black`) and white close icon;
  - `LazyBookCard.vue` badge colors (`#78350f`, `#cd9d49dd`);
  - `ToggleSwitch` (repainted by the equipment finish) and `MultiSelect` gray palette classes;
  - `vue-toastification` default CSS.
- **Theme-id conditionals:**
  - `AudioPlayer.vue` (`theme !== 'black'`), which reads the attribute non-reactively;
  - `TextInput.vue` (Light time-picker icon invert).
- **Kept separate on purpose:**
  - the readers (`Reader.vue`, `EpubReader.vue`) have their own ebook reader theme;
  - year-in-review canvas colors are share images.
- **Native Android:**
  - `styles.xml`/`colors.xml` `#232323` (window and system bars);
  - the widget (`MediaPlayerWidgetTheme` `#232323`/`#373838`, `widget_button_bg.xml`);
  - the splash screen, the media notification and the native `Dialog` alerts, which follow the system day/night mode.

## Security model and trust boundary

- **Trusted:** code in this repository, including `theme/builtins.js`, which is reviewed like any code.
- **Untrusted (future):** any theme data that does not come from the repository, such as imported or user-edited themes.
  - It must pass `validateTheme`.
  - It never reaches CSS except through `themeDeclarations`, which re-validates every value and serializes only integers, fixed-range numbers and fixed keywords into a fixed set of property names.
  - Selectors are built only from ids matching the id pattern.
- Consequences, each covered by tests:
  - **No CSS injection:** values can't contain `;`, `{`, `}`, quotes, `url(`, `@import`, `expression` or backslashes.
  - **No HTML/JS injection:** theme text is never inserted as markup, and labels are i18n keys, not free text.
  - **No remote loading, URLs or file paths:** the schema has no such fields.
  - **No prototype pollution:** only own schema properties are read.
  - **No unrestricted fonts:** no font fields exist yet, and future presets must be enums over bundled or system families.
- The persisted selection is only an id, resolved against built-ins.

## RICE customization (branch `feature/rice-customization`)

The imported/user themes, Follow Device and dynamic-color items below are implemented as RICE (`docs/rice-customization.md`). It reuses this engine:

- **Data:** RICE snapshots carry the same 29 tokens.
- **CSS:** `themeDeclarations` stays the only serializer.
- **Boundaries:**
  - `html[data-theme]` stays a built-in base;
  - one runtime `<style>` scoped under `data-rice` overrides the token variables;
  - a strict validator (`theme/rice/schema.js`) runs before anything is serialized.
- **Built-ins:** the four built-in definitions, their compiled CSS and their recipes are unchanged.
- **Galaxy Themes:** no Galaxy Themes or Theme Park import, since there's no public API for it.

## Future work (not implemented)

- **Imported/user themes:** a versioned JSON format (`format`, `version`, `metadata`, `base`, `tokens`), validated with `validateTheme` against the default theme, with size and depth limits, and flattened when exported.
  - Runtime application would add one `<style>` element whose text comes only from `toCssText({ [themeSelector(id)]: themeDeclarations(tokens) })` for a validated id and validated tokens.
  - Import and export would reuse the diagnostics Save-to-device pattern. Nothing arbitrary is ever executed.
- **Follow Device/System provider:** a token _provider_ that picks or derives a theme from the Android system light/dark setting. It plugs into `ThemeService` (for example `apply(provider.resolve())`), not into components. It must re-apply when the system appearance changes.
- **Material You / dynamic colors:** investigate reading Android 12+ dynamic colors through a small native method and mapping them onto the semantic tokens, with contrast checks.
- **Samsung One UI / Galaxy Themes:** investigate what, if anything, Galaxy Themes exposes to third-party apps beyond the standard system appearance and dynamic colors. No compatibility is claimed.
- See `docs/future-work.md`.
