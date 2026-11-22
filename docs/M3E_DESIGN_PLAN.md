# Material 3 Expressive plan for the stable release

Built from a full read of m3.material.io (all 91 pages: foundations, styles, every component's
overview/specs/guidelines/accessibility tabs and token values, and the M3 Expressive posts) and an
audit of the app against it. Platform facts that shape the plan:

- MDC-Android **1.14 is the last Views release** (maintenance mode). Polish what exists in Views;
  build new surfaces in Compose. No wholesale migration.
- Compose Material3 1.5.0-alpha22 (pinned) already has `LargeFlexibleTopAppBar`,
  `AppBarWithSearch`, `HorizontalFloatingToolbar`, `ShortNavigationBar`, `WideNavigationRail`.
- One UI mode / one-handed mode stay: they fit M3E's "component flexibility" tactic. They become
  real flexible app bars, not spacers.

Effort: S = hours, M = a day or two, L = several days.

## Phase 1 - foundations (tokens first, everything else inherits)

| # | Change | Why | Effort | Status |
|---|---|---|---|---|
| 1.1 | **Motion tokens.** Set the six `motionSpring*` attrs (Expressive overlay, Standard overlay when "Expressive animations" is off). Fix `res/interpolator/m3e_emphasized_decelerate.xml` (it is the standard curve, not 0.05,0.7,0.1,1). One `Motion` helper over `MotionUtils.resolveThemeSpringForce`; `applySpringTouch` on the fast-spatial spring. | Four motion systems today; MDC components use defaults | S | Done `e0269430` |
| 1.2 | Replace timed `Overshoot*`/legacy-curve animators with springs (spatial may overshoot, alpha/colour never). Compose: `MaterialTheme.motionScheme.*Spec()` instead of hand tweens. Durations on the token ladder (50/100/150/200, 250-400, 450-600). "None" intensity means 0. | Interruptible, consistent physics | M | Done `d33bdf1c` springs, `2eb3675f` duration ladder. Compose header/Hub/search specs ignore animation intensity (like other M3 components) |
| 1.3 | **Shape scale.** Add `shapeAppearanceCornerLargeIncreased` (20), `ExtraLargeIncreased` (32), `ExtraExtraLarge` (48) to every shape overlay; snap off-scale radii (6/18/26/36/44) to the scale; move hard-coded `cardCornerRadius`/drawable radii to shape attrs; one Compose `Shapes` per style so XML and Compose match (XL is 32 vs 28 today). | Same card looks different between XML and Compose | M | Done `5db87761` `17d7bbe7` `0f01a002` `b166dda8`; drawable radii left (shape attrs are style refs, not dimens) |
| 1.4 | **Type scale.** "Bold typography" = remap to the 15 *Emphasized* styles, not blanket bold. Body back to 400. One UI overlay sizes keep the hierarchy (titleLarge 26 > headlineSmall 24 today). Nothing below 11sp (badges are 9sp). | Hierarchy inversions, illegible small text | M | Done `83fcb740` `85d0ea10` (body stays regular under Bold) |
| 1.5 | **Colour.** Regenerate the 9 accents as full schemes from seeds (tone-80 dark primaries, matching neutrals, inverse and fixed roles; today only 12 roles change and dark primaries are neon). Remove alpha-faked containers (System Hub). Status/RISKY/ONE UI colours as harmonized custom roles. | Neutrals clash with accents; contrast unpredictable | L | Gated: Experimental > Full colour schemes (`28b3ce8a` `b7830d1a`, off = released accents). Alpha containers + harmonized custom colours done `34869d97` |
| 1.6 | **Contrast.** Medium/High contrast overlays + `ColorContrast.applyToActivitiesIfAvailable`; the in-app "High contrast" applies the HC scheme (today it only recolours outlines). | System contrast ignored | M | Gated with 1.5 (own contrast levels from the generator, not ColorContrast); HC fixes ship on `82f302a7` |
| 1.7 | AMOLED/Black: segments, slider track, shape tiles get a role that stays visible on black (known defect). | 1.1:1 contrast | S | Done `6127358a` |

## Phase 2 - components

| # | Change | Effort | Status |
|---|---|---|---|
| 2.1 | **Switch checkmark is invisible** (`thumbIconTint=?colorOnPrimary` on an on-primary handle). Remove the tint, icon on both states (check / X), one theme-level `materialSwitchStyle` so every switch matches. | S | Done `35657a74` `12ae448c` |
| 2.2 | Buttons: retarget direct `Widget.Material3.Button*` to `Widget.Material3Expressive.*` (press/selected shape morph); drop the Full shape overlay in `CardButton`; one filled button per card (Start), others tonal/text, side by side in a `MaterialButtonGroup` instead of stacked. Remove "… button" content descriptions. | M | Done `6912ca9d`. Deferred: side-by-side MaterialButtonGroup on cards (3 labelled buttons overflow at large fonts/long languages) |
| 2.3 | Toggle groups: one pattern (connected group, label-large, 16dp padding, 8dp inner corner, `selectableGroup()`), replacing the outlined update-card group and the custom onboarding/automation groups. | M | Done `65265f47` + update channel `cdaaf8f5` |
| 2.4 | Progress: legacy `ProgressBar` -> `LoadingIndicator` (<5 s waits) or determinate linear (longer); in buttons tint to the label. | M | Done `9dda9339` (no progress shown inside buttons) |
| 2.5 | Settings rows to the Lists spec (56/72/88dp, 16dp side padding, 24dp icon in a 40dp container, body-large label + body-medium supporting text, trailing switch). Compose rows match XML rows (36dp icon and summary-under-control today). | M | Deferred to the device pass: XML row icons go through IconStyleHelper insets in the library icon frame; resizing (36 vs 40dp) needs on-device measurement |
| 2.6 | Sliders only for ordinal values, inactive track `secondary-container`, value applied while dragging (or live preview), TalkBack state description. Categorical choices (accent, shape style) become swatch/shape tile pickers with `selected` semantics. | M | Done: tile pickers `1bce2d01`, TalkBack step names `b119b1db`, track role 1.7, badge preview while dragging (applying live would recreate on every step) |
| 2.7 | Home cards: drop `checkable`, clickable only with a root action, one card type (filled or outlined), no chips on a clickable card. | S | Done `781dc73c` (all cards keep long-press for edit mode, so stay clickable) |
| 2.8 | Help: no tooltip on labelled rows; visible info affordance; help sheet with `BottomSheetDragHandleView` and a text-button close. Badges readable (11sp+) and in the accessible title. | M | Done `b46fe091`. Deferred: visible info icon per row (row layout; device pass) |
| 2.9 | **About / Device info:** the update-settings card is narrower than the screen and boxed awkwardly; rebuild About and Device info as spec list sections (full-width segmented rows, one card treatment, connected button group for update channel, no 12sp text). | M | Done `cdaaf8f5` `f212640b` |

## Phase 3 - app shell and motion between screens

| # | Change | Effort | Status |
|---|---|---|---|
| 3.1 | **Transitions:** `AppActivity` overwrites `AppBarActivity`'s shared axis with `Explode` (not an M3 pattern). One system: shared axis X forward/back, fade through for tab switches, container transform card -> screen later. Toolbar back uses `finishAfterTransition()`. Settings sub-pages get shared-axis fragment transitions (jump cuts today). Retest the Android 16 transition crash (#483). | S-M | Gated: Experimental > Material screen transitions `62d9881c` (#483 not retestable yet) |
| 3.2 | **Nav bar:** labels always on by default, indicator `secondaryContainer`, active label `secondary`, reselect scrolls to top, no autohide with TalkBack, at least 3 tabs, no squish/bounce on the container, docked (square, full width) as the default style with floating as an opt-in look. | S | Done `05ba2ea9` (bar itself is Experimental) |
| 3.3 | **One app-bar contract:** fill-on-scroll + compress everywhere; `LargeFlexibleTopAppBar`/`MediumFlexibleTopAppBar` in Compose screens (Hub, Device control, Permission manager), headlines wrap instead of ellipsize, no back arrow on top-level destinations, the Apps header collapses (its pinned toolbar blocks the scroll range today), One UI reach height from one shared helper. | M | Partly: Permission Manager + Device control flexible bars, Hub fill-on-scroll `31061aa7`. Deferred: Apps header collapse (pinned toolbar rework), top-level tabs without back arrow done `5d844d5e` |
| 3.4 | Sheets start at <=50% and pull up; snackbars back to inverse-surface and default shape, toasts that confirm an action become snackbars (about 120 toasts vs 11 snackbars); search to `SearchBar`/`AppBarWithSearch` with predictive back. | M | Partly: snackbars `d242803c`. Toast -> snackbar partly: Settings fragments (notifyUser in BaseSettingsFragment) + Apps list; toasts kept where the app leaves right after. Still toasts (~50): Service Doctor, Root compatibility, Icon Studio, Diagnostics dashboard, Home card holders (compat hub install, automation, wireless ADB), pairing dialog, bug report. Deferred: sheets at <=50% (help/changelog sheets pin their action rows below the scroll body, which half height hides), SearchBar |
| 3.5 | Predictive back progress for Apps selection mode and Compose sub-screens (Home edit mode already does it well). | S-M | Done `0485b321` (Home edit mode already had it) |

## Phase 4 - after stable

**Status: not started (by design).**

Adaptive navigation (rail on expanded/DeX, list-detail for Settings), single-activity shell with a
persistent nav bar, container transforms for every Home card, Compose-first for new surfaces.

## Writing (runs alongside)

**Status: deferred** - waits for the translation refresh (every changed English string goes stale in all locales). Also covers the settings-audit sentence-case item.

Sentence case (about 185 Title Case strings), no "Are you sure…" titles (state the consequence),
contractions, no periods on one-line messages, exclamation marks only for real celebrations, no
ampersands in body text, consistent "Developer options".

## Status surfaces and the glass vs AMOLED treatments (2026-11-21)

Checked against the code, not assumed.

- **Home status card** (`ServerStatusViewHolder`): a flat tonal card, no blur or translucency of its own. Its colours come from theme roles, so AMOLED+ reaches it only through the theme's colour roles, not a card-specific branch. The Activity log / Diagnostics chips take the card's own "on" colour for text, icon and a 50% stroke, and an opaque background made from that colour at ~12% composited onto the card colour. Opaque matters: M3 chips draw their own surface under the background, so a translucent fill leaves a dark patch on a light container (#687).
- **Settings status banner** (`StatusBannerPreference`): painted by `BaseSettingsFragment`'s card decoration with `colorSecondaryContainer`; title, summary and the leading icon all use `colorOnSecondaryContainer`. No chips.
- **Glass vs AMOLED lives in the floating nav bar** (`FloatingNavBar`), controlled by Blur UI and AMOLED+: the bar is a tinted surface, never opaque black. Blur on = a lighter tint (55% alpha) over real window blur; blur off = denser (85%, or 90% in AMOLED+) so labels stay legible. AMOLED+ in night mode uses a near-black base (#0F1115 blended 8% with primary) instead of slate-charcoal. A docked bar is always opaque.
- Rule of thumb: never hard-code dark/light text on a status surface; use the matching `on*` attribute of the surface colour.
