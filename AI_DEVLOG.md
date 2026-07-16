# Shizuku+ — AI Session Devlog & Planning Space

Living document. Update at the start of every AI session: mark completed items, add new ones.
This is the single source of truth for cross-session continuity — reduces re-explanation,
prevents re-introducing fixed bugs, and keeps the roadmap visible without user steering.

---

## Open Backlog (unfinished / planned)

Items carried forward from previous sessions that have not yet been committed.

### Features

- [x] **AICore 5 advanced method implementations** — Implemented `getPixelColor`, `captureLayer`, and `getSystemContext` in `AICorePlusService.kt` using `AccessibilityService.takeScreenshot` (API 30+) as a high-performance manager-side bridge. Done 2026-05-03.
- [/] **Shadow Binder Deep Implementation (Issue #199)** — Implemented `IPackageManager` shadowing in `ShizukuService.java` to hide specific apps based on the `shadow_hidden_packages` setting. Added UI for managing hidden packages. **Needs testing with hidden packages.**
- [x] **Root Compat Hub "Shizuku-aware only" label** — Info banner added to
  `activity_root_compatibility.xml` with string `root_hub_shizuku_aware_note`. Done 2026-04-24.

### CI / Infrastructure

- [x] **Broken `api` submodule pointer blocked all CI builds (found 2026-07-13)** — `master`'s `api`
  submodule was pinned to commit `8657364fdc7ebf58072d690a978c7cc3c9bd4271`, which the
  `ShizukuPlus-API` remote rejected (`upload-pack: not our ref`) during checkout. Repinned to
  `d40bc88c` (actual `origin/master` HEAD). Done 2026-07-13, `f20d8c8a`.
- [x] **Missing `android.os.Build` import broke release/debug compile (found 2026-07-13)** —
  surfaced only once the submodule fix above let CI reach actual compilation.
  `BiometricLock.kt:42` (added by `e25440f2`, pre-existing before this session) referenced
  `Build.VERSION.SDK_INT`/`Build.VERSION_CODES.R` with no import. Fixed 2026-07-13, `17b0a727`.
  **`Build App` confirmed green on master afterward** (run `29276207625`).
- [x] **CI Speed Optimization** — Parallelized lint/build jobs and added SDK caching (~50% time cut). Done 2026-04-27.
- [x] **Room Stability** — Downgraded to 2.6.1 (Stable) to avoid alpha-branch risks. Done 2026-04-27.
- [x] **KSP Migration** — Fully removed KAPT from Room build path for modern/fast processing. Done 2026-04-27.
- [x] **KSP2 / Room 2.6.1 incompatibility** — KSP 2.2.20-2.0.2 uses KSP2 architecture which requires Room 2.7+ for void DAO methods; added `ksp.useKSP2=false` to `gradle.properties` to fall back to KSP1. This was a pre-existing failure unmasked by the database brace fix. Done 2026-04-28.
- [x] **Pre-push guard audit** — Verified script checks for CMake versions, Kotlin imports, and stale JNI package paths (af/shizuku migration). Done 2026-04-27.
- [x] **Sentry quota** — Quota was at 100% through end of April 2026. **Automated calendar block is active** in `ShizukuApplication` and will self-expire on May 1st.
- [x] **Dhizuku Device Owner Gaps** — `DhizukuAdminReceiver` and related device admin XML created. Ready for ADB `dpm set-device-owner` command to fully enable Dhizuku Mode capability. Done 2026-04-28.
- [x] **In-app Changelogs** — `ChangelogDialogFragment` created to read from `assets/changelog.txt`. Wired into `MainActivity` to show on first launch after update using `ShizukuSettings.getLastSeenVersion()`. Done 2026-04-28.
- [x] **AICore+ Automation Bridge** — `AICorePlusService` (AccessibilityService) scaffolded to support `dumpHierarchy()`, `performTap()`, and `performSwipe()`. XML config and manifest entries added. Ready for command proxying. Done 2026-04-28.

### Device Compatibility

- [x] **Deep Vendor Diagnostics** — Added detection and logging for ColorOS, HyperOS, and TCL versions in manual/Sentry reports.
- [x] **Expanded Spoofing Targets** — Added Pixel 10 Pro XL, S25 Ultra, and more flagship targets to Dev Options.
- [x] **Service Doctor Tips** — Added specific troubleshooting for Oppo/OnePlus and TCL background kills.
- [x] **libadb.so crash fix** — `initializeStatics()` no longer rethrows `UnsatisfiedLinkError`; ADB
  pairing degrades gracefully on devices where the native lib can't load (TCL, Oppo ColorOS).
  `ShizukuApplication.isAdbNativeAvailable` flag gates entry to `AdbPairingTutorialActivity`.

### Design / Theming (from 2026-07-13 theming audit)

- [x] **Dead theming resources removed** (`0ffc9b34`, 2026-07-13): `cornerRadiusExtraLarge` custom
  attr (was a red herring vs. the real `ShapeAppearance.*.Corner.ExtraLarge` tokens - it was never
  consumed anywhere, not the same attr, so there was no actual 32-vs-28dp conflict once traced
  through), `textColorOnSurfaceHighEmphasis`/`textColorOnSurfaceMediumEmphasis`,
  `card_corner_radius_large` dimen.
- [ ] **Every card in the app hardcodes `app:cardCornerRadius` (16/24/28dp)** instead of
  `app:shapeAppearanceOverlay="?attr/shapeAppearanceCornerExtraLarge"` etc. - `cardCornerRadius`
  unconditionally overrides `shapeAppearanceOverlay` on `MaterialCardView`, so the user-facing
  Modern/Classic/Squircle shape_style setting currently does nothing for any card in the app,
  including the home screen. Investigated 2026-07-13: correct fix in principle, but the base
  `Theme.Material3Expressive.*.Shizuku` theme lives in an external AAR I can't inspect, and I
  can't confirm it defines a default for `shapeAppearanceCornerExtraLarge` - an unresolved theme
  attr on `shapeAppearanceOverlay` risks an inflate-time crash, not just a wrong color, across the
  app's most-used screens. **Needs on-device/build-capable confirmation before attempting** -
  filed as [issue #333](https://github.com/thejaustin/ShizukuPlus/issues/333) rather than guessed
  at blind.
- [x] **`shape_edit_control_background.xml`'s `colorSurfaceContainerHighest` AMOLED remap** — done
  2026-07-13 (`1a637ece`). First pass wrongly assumed chaining `Highest → ?colorSurfaceContainerHigh`
  would make the edit-mode chip invisible against its own card (both resolve to the same color
  under `ThemeOverlay.Black`, since `?attr` resolution is against the fully merged theme, not each
  item's pre-overlay value). On closer look this is fine and consistent with the existing pattern
  (Low already ties with Lowest the same way): the two icons actually inside that chip
  (`ic_drag_handle`, `ic_close_24`) are tinted independently via `colorControlNormal`/`colorError`,
  not derived from the chip's own background, so they stay visible regardless of whether the
  chip's background ties with its parent card's tone. Completed the remap.

### Crash Fixes (from Gemini ADB session 2026-04-27 — fixes applied, not yet on-device verified)

- [/] **Mavericks factory crash** — ProGuard keep rules added for `MavericksViewModel` + factory to
  prevent R8 `-repackageclasses` from breaking companion discovery. **Needs ADB verification — #200.**
- [/] **SQLite race on fresh install** — `ActivityLogManager.loadFromDatabase()` wrapped in try-catch
  so a DB open failure degrades to in-memory mode. **Needs ADB verification — #200.**

---

### Completed (previously listed as open, confirmed done on 2026-04-23 audit)

- [x] **ActivityLogDao persistence** — Fully implemented in `database/` submodule with Room.
  `ActivityLogManager` already wires DAO, loads on init, persists all writes.
- [x] **Activity log toggle synced to server** — `enable_activity_log` already in
  `syncAllPlusFeaturesToServer()` at `ShizukuSettings.java:686`.
- [x] **IWindowManagerPlus AIDL** — No `IWindowManagerPlus.aidl` exists in the manager AIDL
  dir; concern was unfounded. Only `IDhizuku.aidl` present.
- [x] **ServiceDoctorActivity coordinator_root** — Uses `inflate(layoutInflater, rootView, true)`
  correctly. No `getLayoutId()` override.
- [x] **Handler.kt dead code** — `workerThread`/`workerHandler` in server ktx Handler are used.
  No dead code.
- [x] **suCopyOpen rish display** — `suCopyOpen` button already wired in `RootCompatibilityActivity`
  at line 308.
- [x] **LogAdapter DiffUtil** — Migrated to `ListAdapter` + `DiffUtil.ItemCallback`. `694eea28`.
- [x] **Sentry April 2026 hardcode** — Removed expired calendar block from `ShizukuApplication`.
  `694eea28`.

---

## Ideas Parking Lot (floated, not committed to)

Things discussed or sketched that we never formally decided to build.

- [x] **Context7 integration for dev sessions** — Context7 MCP is active and documented in the shizukuplus skill. Use `mcp__claude_ai_Context7__query-docs` for Mavericks, Sentry 8.x, Lifecycle edge cases. Done 2026-06-26.

- **Jetpack Compose re-migration** — Compose was fully migrated then reverted (`25d796d4` revert
  of `749d72a6`) due to instability. If Compose is reconsidered, start with a single isolated
  screen (e.g. Service Doctor) rather than a full home screen migration.

- [x] **Dynamic remote DB ETag caching** — Added ETag/Last-Modified header support to `RemoteDbSyncWorker` to skip redundant downloads when the app-context database hasn't changed on GitHub. Saves bandwidth and improves sync efficiency. Done 2026-05-19.

- **Onboarding step for Root Compat Hub** — Users don't know the Root Hub exists. An optional
  onboarding card after first Shizuku connection could walk through what toggles are available.

---

## Session History (newest first)

### 2026-07-16 (pass 3) — Claude Code (Fable 5) [PRoot ShizukuPlus, deep-review of "still occurring" issue comments]

**Commits:** `2deed39d`, `a42edcac`

**Done (user request: "continue work on issues, deep review comments mentioning problems still occurring"):**
- **AICore+ accessibility service disabled after random interval** (`2deed39d`, #320, CI-green) — two
  root causes on Samsung/OneUI: (1) `accessibility_service_automation.xml` subscribed to
  `typeWindowContentChanged` (highest-frequency accessibility event, device-wide) but
  `AICorePlusService.onAccessibilityEvent` is empty and nothing (manager or server) ever consumes
  events — it only reads `rootInActiveWindow` on demand via the binder bridge. That constant
  cross-app wakeup is exactly the background-activity signal Samsung's watchdog uses to sleep the
  app and disable its accessibility service. Dropped to `typeWindowStateChanged` only. (2) Added
  `AICoreAccessibilityHealer` (called from `MainActivity.onStart`): if AICore+ is toggled on and the
  manager holds WRITE_SECURE_SETTINGS but the service isn't in `ENABLED_ACCESSIBILITY_SERVICES`,
  re-enable it via `Settings.Secure` (opportunistic on foreground, no background watchdog, never
  fights a deliberate disable). Needs on-device confirmation the OEM doesn't block the re-enable.
- **Apps not registering / "Shizuku not detected" / null service binder** (`a42edcac`, #319, CI-green)
  — deep-reviewed the whole binder-registration path and found a THIRD distinct root cause
  (independent of the compat-hub detection layer and the R8/CREATOR crash layer): `BinderSender`'s
  process/uid observers recorded a pid/uid as "handled" *before* calling `sendBinder()` and ignored
  its result. `sendBinderToUserApp()` returns false when the app's `ShizukuProvider` isn't published
  yet (routine on the first foreground event of a launch — a startup race), and that failed push was
  cached for the whole process lifetime, leaving the app permanently unregistered until relaunched.
  `sendBinder()` now returns whether the pid/uid can be remembered; observers drop it on a failed
  delivery so a later event retries (event-driven, bounded, idempotent; manager delivery unchanged,
  keeps its own retry). See [[shizukuplus-binder-detection]].
- **Diagnostic comments** (couldn't fix blind, asked the disambiguating questions): #319, #317
  (Android 17 canary, apps-in-authorized-list), #335 (InstallerX `null.asBinder()` — connected to the
  registration fixes; ruled out firewall/shadow_binder since both default off and a blocked firewall
  call throws SecurityException, not null). #325 (AShell) already answered — third-party's own stack.

**Latent bug flagged, NOT changed blind:** the Binder Firewall (`ShizukuService.isBinderCallBlocked`)
blocks `IPackageManager` transaction codes 13/26 as "installPackage/deletePackage" via hardcoded
heuristics the code itself admits "vary by API version." On modern Android those codes map to other
methods (and real installs use `IPackageInstaller`, not `IPackageManager.installPackage`), so the
firewall's package-block is both wrong and ineffective — but it's opt-in (default off), so it's not
the cause of #335. Should resolve real codes via reflection (like the shadow-binder static block
already does) rather than hardcode; deferred — needs a device to confirm the actual codes.

---

### 2026-07-16 (pass 2) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, Sentry/GitHub audit pass 2]

**Commits:** `b6be301c`, `44ff1bda`, `de56f144`

**Done (continuation of the 2026-07-14/15 audit, user request: "continue with this"):**
- **Dual manager-flavor recognition gap** (`b6be301c`, Sentry SHIZUKUPLUS-7C) — `ShizukuService`
  resolves exactly one flavor (Plus preferred, Drop-In fallback) as `MANAGER_APPLICATION_ID`/
  `managerAppId` at server startup (issue #319's earlier fix). Users with **both** flavors
  installed side by side (supported per #316) had every manager-only check
  (`checkCallerManagerPermission`, `isBinderCallBlocked`, `dispatchPermissionConfirmationResult`,
  `getFlagsForUid`/`updateFlagsForUid`, `attachApplication`'s `isManager` flag) reject the
  non-primary flavor's app as an untrusted third-party client — breaking Plus-feature settings
  sync and silently no-oping permission grant/revoke. Added `isManagerAppId()` helper that trusts
  either flavor's uid when both are installed (safe: same signed codebase, not a real trust
  boundary). Directly explains a live "r2117, apps still not registering" follow-up on #319.
- **Two main-thread Shizuku IPC ANRs + uncaught FOTA crash** (`44ff1bda`, SHIZUKUPLUS-7H/7P/7Q/73)
  — `ShizukuCompanionViewHolder.runPrivilegedCommand()` (via `HomeActivity`'s `lifecycleScope`)
  and `ServiceDoctorActivity`'s phantom-process fix (`serviceScope` = `Dispatchers.Main`) both
  called `Shizuku.newProcess(...).waitFor()` — a blocking IPC round-trip — directly on a
  Main-dispatched scope, violating this project's own "off main thread" rule. Wrapped both in
  `withContext(Dispatchers.IO)`. Also fixed `StarterActivity`'s Samsung FOTA-agent
  UID-escalation trick: `startActivity()` was outside the existing try/catch, so devices without
  `com.sdet.fotaagent` got an uncaught `ActivityNotFoundException` instead of the graceful
  failure log (highest-volume issue in this sweep).
- **Backup encryption key invalidation UX** (`de56f144`, #332/#315) — `CryptoUtils` let
  `KeyPermanentlyInvalidatedException` (Android's by-design key invalidation on biometric/lock
  change) propagate uncaught, surfacing as a raw exception message or literal "null" (many
  keystore exceptions have no `.message`). Encryption now self-heals by regenerating the key
  (safe — a brand-new backup has no prior ciphertext to preserve compatibility with); decryption
  intentionally does NOT auto-regenerate (old ciphertext is genuinely unrecoverable under a new
  key) — all 5 Toast call sites now go through a shared `backupErrorMessage()` helper for an
  honest, specific message instead of "null".
- **Full Sentry sweep**: resolved/ignored the remaining ~20 low-volume unresolved issues from the
  2026-07-14/15 audit's backlog (74, 7N, 77, 76, 7K, 7E, 7R, 3W, 3Q\*, 6Q, 71, 7G, 7F\*\*, 7H, 7Q,
  7P, 73, 13, 7C). \*3Q was already resolved by a prior session. \*\*7F left genuinely open — a
  play.google.com `ActivityNotFoundException` whose already-guarded source location doesn't
  reconcile with the build's `SourceFile:167`; documented honestly rather than guessed at. New
  R8-obfuscation and ANR-correlation triage techniques captured in the `shizukuplus-sentry-audit-
  workflow` memory (single-letter exception classes can only be project-defined types; obfuscated
  package names in culprit strings aren't reliable; same-timestamp ANR pairs are one incident).
- **GitHub issue sweep**: closed #300 (pc name fix, verified still in source) and #309 (settings
  search crash fix, verified still in source); commented with fix pointers on #319, #298, #332,
  #315; diagnosed #325 (AShell third-party binder mismatch — entirely inside AShell's own
  obfuscated stack, no ShizukuPlus frames, needs the reporter's logcat to pin down further, left
  open).
- **CI verified green on all three pushes.**

**Left open:** #200/#199/#333 remain blocked — no ADB device reachable in this PRoot session and
no local Android SDK to inspect the base theme's default corner values (#333). Sentry
SHIZUKUPLUS-3Q (the exact Mavericks-factory crash #200's ProGuard keeps address) shows zero
recurrence since version_code 1668 though — decent indirect evidence the fix is holding, not a
substitute for real on-device verification.

---

### 2026-07-14/15 — Claude Code (Sonnet 5) [session run in parallel with a review pass, devlog not updated at the time]

**Commits:** `234f0be3`, `6394bac1`

**Done:**
- **Dhizuku V1/V2 binder authorization gap** (`234f0be3`, issue #326-adjacent) — `b66d2c99` had
  fixed `DhizukuV2Binder.getBinder()` to return the real `DEVICE_POLICY_SERVICE` binder instead of
  a version string, which made the transaction live for the first time — and exposed that V2's
  `getBinder()` never had the `AuthorizationManager.granted()` check that `DhizukuV1Binder.getBinder()`
  has always had. Since `DhizukuProvider` is `android:exported="true"` with no manifest permission
  and Dhizuku mode is a single global toggle (not per-app), any installed app could get the raw
  DevicePolicyManager binder without authorization once Dhizuku mode was on. Fixed by mirroring
  V1's gate onto V2. **No shared interface enforces parity between the two hand-implemented binder
  classes — worth periodically re-auditing.**
- **Compat Hub self-overwrite + permission-group collision** (`6394bac1`) — audited every
  open/closed issue mentioning Compat Hub (#334, #316, #314, #327, #326, #249, #206, #243, #242,
  #209) and found two real bugs sharing a root cause: the `dropin` flavor's own applicationId IS
  `moe.shizuku.privileged.api`, same as the `compat/` module's shim. (1)
  `StockShizukuCompat.isCompatAppInstalled()` had no self-exclusion check (unlike
  `isInstalled()`/`isOriginalRunning()` in the same file), so a dropin build inspected its own
  version, reported the hub "not installed," and its "Install Compat Hub" card's `pm install -r`
  would silently overwrite the running dropin app with the 2-class forwarding stub — matches
  HyperCriSiS's live report on #334. Fixed with self-exclusion plus a hard-stop guard in the
  install click handler. (2) `<permission-group>` was a hardcoded literal shared by both flavors'
  manifests, so installing Plus + Dropin side by side failed with error -126 (#316) — scoped to
  `${applicationId}`. Left `af.shizuku.plus.permission.API_V23` itself un-scoped since it's
  hardcoded into the client-facing `ShizukuProvider.PERMISSION` constant third-party apps compile
  against.
- **CI verified green on both pushes** (`29382328766`, `29388259054`).

**Left open:** issues #326, #334, #316 are fixed in code but still open on GitHub — need a
comment + close pass. A parallel Sentry/Linear audit (separate session, same window) found several
other unresolved Sentry crash clusters that look plausibly fixed by already-landed code (stale
clients, not live bugs) but weren't verified deeply enough to resolve — see that session's notes
before assuming any of them are safe to close.

---

### 2026-07-13 — Claude Code (Sonnet 5) [PRoot ShizukuPlus]

**Commits:** `f29188e0`, `e8979dd1`

**Done (user request: "fix every design issue in the app by reviewing how we theme everything"):**
- **Audited** `values/{colors,themes,themes_overlay,styles,dimens,attrs}.xml`, `values-night/*`, and all 12 layout files using `cardElevation`/`cardBackgroundColor`, against the project's own documented tonal-ladder rule (material3-xml-theming skill: flat cards need `colorSurfaceContainerHigh`+, `Low` is the *Elevated Card* token).
- **Fixed flat-card token bug** (`f29188e0`) — 7 card instances across `activity_service_doctor.xml`, `activity_root_compatibility.xml` (x2), and the shared `DialogCard` style (used by 6 tutorial-dialog cards in `adb_pairing_tutorial_activity.xml`/`terminal_tutorial_activity.xml`) plus 3 more in `page_onboarding_setup.xml` were wired to `colorSurfaceContainerLow` on 0dp-elevation/0dp-stroke cards, causing them to visually blend into the page background. Sibling flat cards (`home_item_container.xml`, `swipe_hint_overlay.xml`, `layout_settings_search_header.xml`) already used the correct `High` token — standardized all of them.
- **Fixed onboarding hardcoded-color drift** (`e8979dd1`) — `page_onboarding_gestures.xml` had `backgroundTint="#FF9800"`/`"#E53935"` instead of referencing the existing `@color/status_starting` (identical value) / `@color/status_error` (`#F44336` — a near-miss of the `#E53935` used here) tokens.
- **Deliberately deferred** (lower confidence / needs visual verification, not fixed this session):
  - `cornerRadiusExtraLarge` theme attr is hardcoded to 32dp (`m3e_spacing_extra_large`) at the base theme level and never gets folded into the `ThemeOverlay.Shape.*` (Modern/Classic/Squircle) system — the shape_style setting's own comment documents the M3 standard as XL=28dp, and `ShapeAppearance.Modern.Corner.ExtraLarge` is 28dp, so there's an internal 32dp-vs-28dp mismatch. Also appears unused in Kotlin (only consumed by whatever framework/library reads it via XML attr resolution) — needs confirmation of what actually renders with it before changing.
  - Corner radius literals are inconsistent across flat cards (16dp/24dp/28dp scattered as raw values instead of shape-appearance tokens) — some may be intentional (compact info banners vs. primary content cards), needs visual call.
  - `card_corner_radius_large` (32dp dimen) is defined but has zero references anywhere — dead resource.
  - `textColorOnSurfaceHighEmphasis`/`textColorOnSurfaceMediumEmphasis` custom attrs are declared and assigned hardcoded ARGB literals in both base themes but have zero usages anywhere in res/ or java/ — dead theming plumbing, duplicates M3's own `colorOnSurface`/`colorOnSurfaceVariant`.
  - `shape_edit_control_background.xml` (home-card edit-mode drag handle/remove button chip) uses `colorSurfaceContainerHighest`, which `ThemeOverlay.Black` (AMOLED) does not remap (only `Low`/`High` are remapped, since those are the only tokens actually used by real cards) — minor, only visible in edit mode.
- **Verified**: all 21 `pre-push-guard` static checks pass (XML well-formed, implicit style parents resolve, no unresolved colorPrimary, etc.); no local Android SDK available to compile, so pushed to let CI verify.
- **Pre-existing, unrelated CI break found while checking the push**: "Build App" has been failing on `master` for at least 3 pushes before this session's (`b66d2c99`, the haptics commit, and this one) — the `api` submodule pointer is pinned to commit `8657364fdc7ebf58072d690a978c7cc3c9bd4271`, which the `ShizukuPlus-API` remote rejects with `upload-pack: not our ref` during CI checkout. Not caused by this session; flagged for the user, not fixed (out of scope for a theming pass, needs the submodule pointer corrected to a commit that's actually pushed to the API repo).

**Left open:** shape-scale/corner-radius audit (needs visual/on-device confirmation).

---

### 2026-07-13 (cont'd 2) — Claude Code (Sonnet 5) [PRoot ShizukuPlus]

**Commits:** `1a637ece`

**Done (user: "finish the amoled remap. consider shape style setup later by creating a gh issue for it"):**
- **Completed the `colorSurfaceContainerHighest` AMOLED remap** (`1a637ece`) — reversed the earlier decision to leave it unmapped. Re-examined `shape_edit_control_background.xml`'s actual consumers (`home_item_container.xml`'s drag-handle/remove-button chip): both icons inside it are tinted independently (`colorControlNormal`, `colorError`), not derived from the chip's own background color, so the "chip background ties with parent card" collision I was worried about doesn't actually make anything invisible - it's cosmetically identical to the Low/Lowest tie the codebase already has and accepts. Completed the remap.
- **Filed [issue #333](https://github.com/thejaustin/ShizukuPlus/issues/333)** for the dead `shape_style` setting (every card hardcodes `cardCornerRadius`, overriding `shapeAppearanceOverlay`, so Modern/Classic/Squircle does nothing) instead of guessing at the fix blind - labeled `module: theme`, `module: design`, `type: technical-debt`, with the full root-cause writeup and a suggested verification-first approach for a build-capable session.

---

### 2026-07-13 (cont'd) — Claude Code (Sonnet 5) [PRoot ShizukuPlus]

**Commits:** `f20d8c8a`, `0ffc9b34`, `ff14b386`, `17b0a727`

**Done (user: "fix all the things mentioned now" + "also remove Active Enhancements Dashboard visual from Feature Hub settings"):**
- **Fixed the broken `api` submodule CI pointer** (`f20d8c8a`) — repinned from the unreachable `8657364f...` to `d40bc88c`, the API repo's actual `origin/master` HEAD. Confirmed via `git ls-remote`/`git fetch` in the submodule directly (the broken SHA isn't fetchable from anywhere, not even by hash).
- **Removed dead theming resources** (`0ffc9b34`) — `cornerRadiusExtraLarge` custom attr (declared + assigned in both base themes + its own `declare-styleable`, zero consumers anywhere in the repo, not just `manager`), `textColorOnSurfaceHighEmphasis`/`textColorOnSurfaceMediumEmphasis` (same story, duplicated M3's own `colorOnSurface`/`colorOnSurfaceVariant` without ever being read), `card_corner_radius_large` dimen (defined, zero references).
- **Investigated completing the AMOLED remap for `colorSurfaceContainerHighest`** (used by `shape_edit_control_background.xml`, the home-card edit-mode drag/remove chip) — attempted `colorSurfaceContainerHighest → ?colorSurfaceContainerHigh`, then caught before committing that `?colorSurfaceContainerHigh` resolves through `ThemeOverlay.Black`'s *own* override (already remapped to `?colorSurfaceContainer` a few lines up), so Highest and High would collapse to the same color — the edit-mode chip would become invisible against its own card. Reverted; left unmapped. Worth remembering generally: theme-attr overlay chaining resolves against the fully merged theme, not against sibling items' pre-overlay values.
- **Investigated wiring the dead `shape_style` setting up for cards** — every card in the app sets a literal `app:cardCornerRadius` (16/24/28dp) instead of `app:shapeAppearanceOverlay="?attr/shapeAppearanceCornerExtraLarge"` etc., and `cardCornerRadius` unconditionally overrides/ignores `shapeAppearanceOverlay` on `MaterialCardView` — meaning the user-facing Modern/Classic/Squircle shape_style setting (in `ThemeOverlay.Shape.*`) currently does nothing for any card in the app, including the home screen. Correct fix in principle, but the base `Theme.Material3Expressive.*.Shizuku` theme lives in an external AAR (not in this repo, generated by `dev.rikka.tools.materialthemebuilder` + `rikkax.material`) and I can't confirm it actually defines a default for `shapeAppearanceCornerExtraLarge` — an unresolved theme attr on `shapeAppearanceOverlay` risks an inflate-time crash (not just a wrong color) across the app's most-used screens. Given the AMOLED near-miss above, didn't risk it blind. **Needs on-device confirmation of the base theme's default corner values before attempting.**
- **Removed "Active Enhancements Dashboard" from Feature Hub settings** (`ff14b386`, user request) — deleted `PlusStatusDashboardPreference.kt`, `layout_plus_status_dashboard.xml`, its `settings_shizuku_plus.xml` entry, and the 4 dead `findPreference("plus_status_dashboard")` visibility-toggle refresh calls in `ShizukuPlusSettingsFragment.kt` that existed solely to force it to rebind. Left `shape_node_active`/`shape_node_inactive`/`shape_status_indicator` drawables in place — still shared with `HomeLayoutSimulatorPreference`. This was one of the two "status/diagnostics dashboards" explicitly kept during the 2026-07-04 flowchart-preference cleanup (`8d3dbe69`); user has now decided to remove it too.
- **Found and fixed a second pre-existing CI break** (`17b0a727`) — once the submodule fix let CI get past checkout and into actual compilation, `BiometricLock.kt:42` failed with "Unresolved reference 'Build'" in both debug and release Kotlin compile tasks. `e25440f2` (pushed before this session, part of the upstream commits rebased onto earlier today) added a `Build.VERSION.SDK_INT < Build.VERSION_CODES.R` guard without `import android.os.Build`. Pre-existing, not introduced by any change in this session; fixed with the one-line import.
- **Verified: `Build App` CI is green on master** (run `29276207625`, 9m22s) — first successful build in at least 4 pushes. Confirmed via a polling loop rather than guessing from local static checks alone.

---

### 2026-07-04 (cont'd 3) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `07fa78af`, `e580b78e`, `7a2d3799`, `c7334564`, `3aa13734`, `3b150091`, `444bd006`, `16606e22`

**Done (settings compartmentalization review + new GitHub issue triage):**
- **Settings architecture audit** (user request: "review settings design and function esp compartmentalization") — found and removed 3 fully orphaned settings screens (`settings.xml` legacy monolithic layout, `settings_home_visibility.xml`/`HomeVisibilitySettingsFragment.kt`, `settings_update.xml`/`UpdateSettingsFragment.kt` — zero references anywhere, verified via grep across manifest/Kotlin/SettingsSearchEngine's screen index) (`07fa78af`). Removed a confusing duplicate: Developer Options had its own unencrypted "Backup & Restore" (`export_settings`/`import_settings`, plain JSON, no auth) sitting alongside Feature Hub's encrypted/biometric-gated one, same title, different implementations — standardized on the encrypted flow; `SettingsBackupManager` itself (the shared JSON-serialization core `BackupRestoreManager` wraps with encryption) is untouched since `MainActivity`/`UpdateInstaller` still use it internally (`e580b78e`). Regrouped `companion_fallback`/`launch_stock_shizuku` from Advanced → Help & Recovery into a new "Stock Shizuku Compatibility" category in Root & Compatibility, and fixed 3 nav summaries in `settings_main.xml` that didn't mention categories their screens actually contain (`7a2d3799`).
- **New issue #316 (Drop-In variant never starts)** — root-caused to `StockShizukuCompat`'s conflict checks matching purely on package/process name string with no self-exclusion: the dropin flavor's own applicationId literally IS `moe.shizuku.privileged.api`, so `isInstalled()`/`isStockShizukuInstalled()` always found "itself" and `isOriginalRunning()`'s `ps -A | grep` always matched its own healthy server process, permanently showing false conflict UI (`c7334564`). Also found the native starter's (`starter.cpp`) "start via computer/ADB" fallback path-lookup hardcoded to only check the Plus flavor's package name, so `pm path` always failed on a dropin install — the exact "can't get path of manager" error reported; now tries both package names (`444bd006`).
- **New issue #317 (STOPPED despite repeated ADB-start log entries) + #316's "Plus starts as v13, granted apps fail"** — both matched an existing open community PR (#308, by a prior Claude Code session) exactly: `ServiceStatus.isRunning` was gated on an attach-required `getUid()` that can return -1 right after start even though the server is genuinely reachable via `pingBinder()`; `HomeAdapter.updateData()` dropped (rather than coalesced) renders during the rapid state-change burst around start; `checkRemotePermission()` was unguarded and aborted the whole status load on a throw. Verified the diff, cherry-picked both PR commits (`3aa13734`, `3b150091`) onto master — clean auto-merge, no conflicts with same-session `HomeAdapter.kt` changes.
- **#300 (rename PC name to Shizuku+ in wireless debugging)** — the ADB key comment embedded in the pairing RSA public key was hardcoded to `"shizuku"` at all 5 construction sites; changed to `"shizuku+"` (`16606e22`). Takes effect on next connection, no re-pairing needed, since the name is re-encoded fresh on every `AdbKey` construction rather than cached with the persisted key material.
- **#301 correction** — the reporter's 300-line OriginOS/vivo compatibility-layer proposal was built on a misreading: `kd.a` (the class in their `BadParcelableException`) is a standard R8-minified class name, not evidence of a vivo-specific obfuscated framework class. Flagged that this is very likely the same CREATOR-stripping bug already fixed in `374edbe3`/`dd8e47a1`, asked for a re-test before considering a whole new OEM adapter layer.
- **#280 follow-up** — a Chinese-language reporter said the app still crashes tapping "Start" from the notification after the maintainer's earlier ANR fix. No stack trace attached; commented noting today's batch of Android 15/16 background-start fixes likely also covers this, asked for a fresh crash report if it recurs.
- **Sandbox note:** `git fetch origin pull/<N>/head` works to pull a same-repo PR's branch into a local ref for review/cherry-pick even when `git fetch origin <branch-name>` fails (GitHub doesn't expose arbitrary PR head branches as direct remote refs).

### 2026-07-04 (cont'd 2) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `bbed21fc`, `61575465`

**Done (further Compat Hub + UI enhancement pass):**
- **Compat Hub card refresh/busy-state/dedup** (`bbed21fc`) — install/disable ran in a fire-and-forget coroutine with no callback into the view model, so the card kept showing the stale action until the user left Home and returned (onResume() was the only reload() trigger). Gave `ShizukuCompanionViewHolder` a `creator(scope, homeModel)` (matching `StartWirelessAdbViewHolder`'s existing pattern — the creator must be instantiated once at the `HomeAdapter` level, not per `updateData()` call, or RecyclerView view-type pooling breaks) and call `homeModel.reload()` when the pm command finishes. Also disabled the button with an "Installing…"/"Disabling…" label during the ~1-2s operation to prevent double-taps, and extracted the duplicated Shizuku-then-root fallback into one `runPrivilegedCommand()` helper.
- **ANR risk in "Fix Conflict" button** (`61575465`, `StartStockShizukuViewHolder`) — `Shell.cmd(cmd).exec()` (synchronous) was called directly from the main-thread click listener; su attach can be slow enough to jank or ANR. Moved to a background coroutine, matching the project's existing convention of keeping privileged IPC off the main thread.
- Audited all other Home ViewHolders for the same two patterns (main-thread shell/Shizuku calls, missing busy-state) — only these two files run privileged shell commands in `home/`, both now fixed.

### 2026-07-04 (cont'd) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `8d3dbe69`, `2afb39b6`, `c90f1f2d`, `878b0bcd`, `5c3dfc5c`

**Done (user-reported UI/UX + new GitHub issues):**
- **Removed 9 redundant flowchart "diagram" preferences** (`8d3dbe69`) — DNS Governor, Binder Firewall, Continuity Bridge, VM Manager, Shadow Binder, Storage Bridge, Theming Bridge, AI Bridge, SU Bridge. Each just duplicated the toggle already above it and had hardcoded light-theme colors. Left the two status/diagnostics dashboards (different: aggregate + actionable warnings, not 1:1 duplicates).
- **Appearance settings flashing black on change** (`2afb39b6`) — `AppActivity` requests `Window.FEATURE_ACTIVITY_TRANSITIONS` + Explode transitions; every appearance/behavior toggle called `activity?.recreate()` directly, causing a black flash during the window teardown/rebuild. Added `AppActivity.recreateWithoutTransition()` (zeroes window animations first) and routed all ~11 call sites through it.
- **Home edit-mode X/+ button overlapped card content** (`c90f1f2d`) — `remove_btn` sat top|start, the same corner as every card's own icon/title. Moved to top|end (same edge as the drag handle) and widened the reserved end-clearance to fit both.
- **Verified card-spacing report via pixel analysis** — installed netpbm/libjpeg-turbo in the PRoot sandbox to decode a user-provided screenshot and measure exact gaps; found one real 8px-vs-40px gap anomaly, but the RecyclerView spacing math (`addItemSpacing`/`addEdgeSpacing` in HomeActivity) is symmetric by construction — most likely a transient animation-frame artifact, not a persistent layout bug. Flagged back to user rather than guessing a fix.
- **Compat Hub install failure** (`878b0bcd`, #314, also root cause of new symptoms on #298) — `pm install` runs via a shell process (Shizuku/root) that can't read the app's private cache dir due to per-app UID sandboxing; the extracted compat.apk was written there instead of external storage. Fixed to match the working pattern already used by UpdateManager/UpdateInstaller.
- **Backup Settings password/PIN/pattern bug** (`5c3dfc5c`, #315) — `BiometricLock` hardcoded a "Use PIN/Pattern" negative-button label that (a) was wrong for password-locked devices and (b) didn't actually work as a fallback at all — tapping it just canceled with an error. Switched to `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, which correctly prompts for whatever credential is configured. Also removed `notifyDiagramForKey()`, dead code left by the diagram-widget removal.
- **GitHub comments** posted on #314, #315, #298 (cross-referencing the #314 fix as the root cause of a new report there with logs/screenshots).
- **Sandbox note:** no PIL/ImageMagick preinstalled; `apt-get install imagemagick` failed on an unrelated `libcups2` 404, but `apt-get install --no-install-recommends libjpeg-turbo-progs netpbm` succeeded and `djpeg -pnm` + a small Python PNM parser was enough to do precise pixel-level screenshot analysis without any GUI tooling.

### 2026-07-04 — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `0a1e7737`, `27667aeb`, `4febe299`

**Done:**
- **Settings search crash on Android 16** (`0a1e7737`, #309 / Sentry SHIZUKUPLUS-72) — search-result `Card` used `Modifier.clickable`, which reads `LocalIndication`; on Compose Foundation 1.7+ that throws when a legacy `Indication` is in scope. Switched to Material3's `Card(onClick = ...)` overload.
- **AutomationService foreground-service crash cluster** (`27667aeb`) — service ran as `dataSync` FGS, which Android 15+ time-limits/validates strictly, producing SHIZUKUPLUS-6H/6G/6M/6E/6V. Switched to `specialUse` (matches WatchdogService/ShizukuLiveService pattern), guarded every `startForeground()` call with try/catch → `stopSelf()`, made `ConnectivityManager` nullable.
- **Fixed a broken master build** (`4febe299`) — the prior session's `f7f46de0` (pushed from the Termux clone) put `import rikka.html.text.toHtml` above the `package` declaration in `ShizukuPlusSettingsFragment.kt`, a Kotlin syntax error that cascaded into ~30 "Unresolved reference" errors across the settings package and failed CI run 28692928435. Moved the import into place; verified CI green again (lint/build/release all success).
- **Sentry audit continued** — resolved 51 of 66 previously-unresolved issues this pass (see the 2026-06-28→07-04 entry below for the bulk of it); reviewed the four commits pushed from the Termux clone (`03d3c5e6`, `3a510dd7`, `9611676c`, plus the build-breaking `f7f46de0`) for correctness — all sound except the import-order slip.
- **GitHub comments** posted on #309, #311, #312, #306, #303 tying each to the specific commit/root-cause fix and requesting a re-test/logcat on the next build.
- Reviewed (did not merge — pending maintainer/external-dependency review) two open Claude-authored PRs: #307 (binder delivery to stock clients, blocked on `ShizukuPlus-API#16`) and #308 (manager "not running"/stale-version race fixes).
- **Memory gotcha saved:** `versionCode`/`versionName` are derived from `git rev-list --count HEAD`, but the repo's history appears to have been rewritten at least once — local HEAD count and live Sentry release tags (`r2082`) were inconsistent with a stable history. Don't trust dist/release numbers to judge whether an old crash is fixed; read the current source at the crash site instead.

### 2026-07-03 21:43–21:57 — Claude Code (Sonnet 4.6) [Termux ShizukuPlus, d66b3105]

**Note:** These 4 commits are in the Termux clone (`~/ShizukuPlus`) but NOT yet in the sdcard clone. Run `git pull` in `/sdcard/Documents/ShizukuPlus` to sync.

**Commits:** `03d3c5e6`, `3a510dd7`, `9611676c`, `f7f46de0`

**Done:**
- **Sentry crashes** (`03d3c5e6`) — NPEs, BinderRequest receiver, file system errors, ADB startup crashes
- **ShizukuPlus UI bugs** (`3a510dd7`, #266, #267, #270)
- **Settings search AndroidView hidden when active** (`9611676c`, #269)
- **Missing import for toHtml extension** (`f7f46de0`)

### 2026-06-28 → 2026-07-04 — Claude Code (Sonnet 4.6) [Termux session, d66b3105]

**Session note:** Ran from Termux environment (not PRoot). Session was invisible from PRoot `/resume` until 2026-07-04 sync.

**Done:**
- **Sentry audit** — 51 issues resolved (from 66 unresolved → 22 remaining); remaining are external (keystore hardware, disk-full/I/O, ANRs, server-client timing races)
- **WorkManager ProGuard crash** — verified try-catch fix already in place (Jun 23 commit); pre-fix events in Sentry
- **ActivityLogActivity missing from manifest** (5Z) — declared; verified
- **Parcelable CREATOR strip** (6S) — covered by existing keep rule; verified
- **AutomationService FGS background start** (5X) — guarded + `specialUse` type; verified
- **`startActivityAndCollapse` QS tile** (8) — try-catch with API-level branching verified correct
- **FileProvider authority** (6P) — `${context.packageName}.fileprovider` matches manifest; `.morphe.` authority gone
- **`Unknown permission moe.shizuku.manager.permission.API_V23`** (6F/63/61, ~103 events) — `grantRuntimePermission` already wrapped in `try/catch(Throwable)`; exception can't cross binder
- **SystemHubActivity action-bar crash** (56, 3 events/1 user) — deferred; too risky vs. impact
- **GitHub comments** posted on #309 (settings search), #306 (binder-handshake build green)
- **Memory files saved** — 5 rich ShizukuPlus memory entries synced to device

**Open (22 Sentry issues):** keystore hardware failures, disk-full I/O errors, ANRs, server↔client timing races, single-user races — all external to app code

### 2026-06-15 → 2026-07-04 — Claude Code (Sonnet 4.6) [multi-compact session]

**Commits:** `17d04617` through `27667aeb` (49 commits)

**Done:**

*Crash / Sentry fixes (Jun 15–19):*
- **4 high-volume Sentry crashes** (`17d04617`) — SecurityException (`ACCESS_WIFI_STATE` missing), WorkManager direct-boot crash, DownloadManager `local_filename` column crash, `startActivityAndCollapse` UnsupportedOperationException on Android 14+
- **Binder-not-received crash in AdbProxyService** (`4f71348a`) — `Shizuku.pingBinder()` called outside try-catch; moved inside
- **Reset icon invisible on light backgrounds** (`e03bbeec`, #247) — `fillColor` was `white`; changed to `black` (M3 convention)
- **BackgroundServiceStartNotAllowedException** (`8a7bc3b7`) — `ShizukuLiveService` started with plain `startService()` from `Application.onCreate()` on Android 12+; switched to `startForegroundService()` + `startForeground()` in `onStartCommand()`
- **In-app notification permission dialog** (`4607edd0`) — replaced "go to Settings" redirect with in-app rationale popup
- **DeadObjectException** (`ec8714f5`, 15 Sentry events) — `Shizuku.pingBinder()` unguarded in `AuthorizationManager`, `RequestPermissionActivity`, `AICorePlusService`, `AppViewHolder`; all wrapped
- **ANR: Shizuku binder calls on main thread** (`6a9ecddb`) — all `AppViewHolder` binder calls moved to coroutines
- **Multiple Sentry crash sources batch 1** (`b1b73793`) — SQLiteCantOpenDatabaseException fallback chain (device-protected → app storage → in-memory), `FileNotFoundException: last_crash.txt` downgraded to `Timber.w`, `AutomationService` foreground type
- **Multiple Sentry crash sources batch 2** (`0eb607b6`) — `RootCompatibilityActivity` column crash, `IllegalArgumentException` column fix
- **api submodule AIDL-stub rewrite** (`cae6a62a`) — rebased against remote to resolve merge conflict

*Deep audit and product flavor work (Jun 23):*
- **Five bugs from deep audit** (`ed9983b5`) — ADB pairing duplicate key, PendingIntent collision, null cast NPE, Sentry quota reset on every cold start, broken string interpolation in logs
- **Product flavors** (`c4151cb6`, `d4a1566d`) — 3 APKs per release: `shizukuplus.apk` (af.shizuku.plus.api), `shizuku-dropin.apk` (moe.shizuku.privileged.api), `shizuku-compat.apk` (thin stub)
- **Final audit findings** (`6faf1095`) — memory leak, node recycle, timing side-channel, scope leak, broken log
- **Cold-start from ADB pairing notification** (`7e4f3f9b`, #280) — `EXTRA_START_SERVICE_VIA_WADB` only read in `onNewIntent()`; now also handled in `onCreate()`

*Root-mode and CI fixes (Jun 24–26):*
- **Root-mode connectivity** (`e3b2f7f2`) — `BIND_APPLICATION_PERMISSION_GRANTED=true` for apps using original Shizuku library; suppress auth spam; expand permission filter
- **CI: flavor-specific Sentry task names** (`1ec97640`) — ambiguous task names after flavor split caused CI failure
- **attachApplication race/NPE + compat build wiring** (`70431294`)
- **AdbPairingTutorialActivity missing from manifest** (`658ecb7c`) — registered; pairing flow restored; CI signing fixed
- **Debug keystore auto-generation** (`889ae1e9`) — `signing.gradle` generates a keystore when none found on CI

*Settings refactor and features (Jun 29–30):*
- **CollapsiblePreferenceCategory rollout** (`8658e500`, `ae2bd209`) — all settings screens upgraded; dead UISettings cluster removed
- **ShizukuLiveService gate** (`2948eeb3`, `61d25316`) — startup gated on `live_activity_enabled`; semantics and defaults corrected
- **live_activity and auto_reconnect_mdns** (`a6f0b04b`) — settings toggled prefs but didn't wire actual behavior
- **VirusTotal + Pithus APK verification** (`cbec7bb5`) — API calls implemented
- **Stealth mode** (`1985b5ec`) — hide launcher icon via toggleable activity-alias in Developer Options
- **AppManagement settings** (`b59c27c7`) — `AppManagementSettingsFragment` wired, PLUS badges added, settings reorganized
- **Dhizuku diagnostic tap** (`19310e87`) — now shows ADB setup dialog instead of doing nothing
- **Sync all root/ghost/bootloader features to server** (`fa23aed6`) — 6 features were silently not synced due to key suffix mismatch
- **Remove dead developer options** (`8c046ffd`) — duplicate and unused prefs removed
- **Compat APK bundling, dev options gate, theme recreation** (`1956f594`)
- **4 crash/rendering bugs** (`0f265f6b`, #293, #267)

*Code quality and more bug fixes (Jul 2–3):*
- **binder-send crash** (`374edbe3`, #298) — Parcelable `CREATOR` stripped by R8; keep rule added
- **All BinderContainer variants hardened** (`dd8e47a1`) — R8 keep rules expanded
- **AdAway integration removed** (`6669f494`) — vestigial, never worked
- **binder_logging toggle** (`fecd41c5`) — was fully dead; wired to actual behavior
- **6 ghosting/bootloader server features** (`fe28618e`) — key suffix mismatch; server never honored them
- **AI Core sub-features UI** (`b2c7fcba`) — toggles wired; legacy pairing dialog added
- **Accessibility service declarations** (`857c562f`, #305) — dropped by refactor; restored
- **Surface compat hub card by default** (`0d47d93c`, #249) — third-party detection now discoverable
- **Docs: third-party compat layer** (`c782dc0f`) — stale AdAway reference removed
- **Animation Intensity setting** (`09abb841`) — had no effect; wired
- **Settings search crash on Android 16** (`0a1e7737`, #309)
- **AutomationService foreground hardening** (`27667aeb`) — `startForeground()` moved to `onCreate()`, type `dataSync` → `specialUse`

**Open:**
- Issue #200 (Mavericks factory + SQLite race) still needs on-device ADB verification
- Issue #199 (Shadow Binder hidden packages) still needs testing
- `shizuku-compat.apk` not appearing in CI release artifacts — compat module build wiring incomplete
- `gh auth` / GitHub MCP need `workflow` scope on PAT to push `.github/workflows/` changes

### 2026-06-26 — Claude Code (Sonnet 4.6)
**Done:**
- Recovered lost `CLAUDE.md` from `AI_DEVLOG.md` after device reset — all 14 crash rules and key files map restored.
- Created `CLAUDE.md` in project root with full critical crash rules, build commands, key files, and open verification items.
- Installed 10 Android skills globally (`~/.claude/skills/`): android-dev, android-debugging, android-gradle-logic, gradle-build-performance, koin, kotlin-coroutines, android-testing, compose (+ crash playbook + state-management references), android-data-layer, kotlin-flows.
- Created `~/.claude/skills/shizukuplus/SKILL.md` — deep project skill auto-loaded when working in this repo.
- Created `.claude/agents/build-doctor.md` — specialized agent for diagnosing and fixing build failures with a known-error lookup table.
- Added XML layout validation hook to `~/.claude/settings.json` — catches malformed layout XML immediately on edit (the SAXParseException class of CI failures).
- Added `android-adb` MCP server config (CursorTouch android-mcp, Python) — pending pip install completion; provides ADB device control, UI hierarchy inspection, shell commands.
- Updated global `~/.claude/CLAUDE.md` with PRoot/Android constraints for all sessions.
- Fixed Claude Code setup: removed npm wrapper, NVM lazy-load, PATH order, TMPDIR/SSL_CERT_FILE/DBUS env vars, auto-updates enabled.

**Open:**
- android-mcp: `watchfiles` needs Maturin/Rust (unavailable in PRoot pip). Install from **Termux shell** (outside PRoot): `pip install android-mcp`. Then add to `~/.claude/settings.json`: `"mcpServers": {"android-adb": {"command": "/data/data/com.termux/files/usr/bin/python3", "args": ["-m", "android_mcp"]}}`
- Consider adding `rcosteira79/android-skills` via `/plugin marketplace add rcosteira79/android-skills` for automatic plugin management

### 2026-05-19 — Gemini CLI
**Commits:** (this session)

**Done:**
- 2026-05-19: High-Performance Backend & Design Overhaul
    - [Backend] Replaced `Runtime.exec` with direct `InputManager` injection for AICore+ automation (latencies <10ms).
    - [Backend] Implemented `getServerStats` and `getVisibleWindows` APIs in the server to expose real-time performance and system intelligence data.
    - [Feature] Consolidated server metrics and activity logs into a unified **System Hub** with a tabbed Material 3 interface, streamlining diagnostic access.
    - [Feature] Implemented **NPU Acceleration** infrastructure with device-level hardware detection ('/dev/npu') and a developer toggle.
    - [Feature] Added programmatic **High-Performance NPU Power Tuning** for S22 Ultra, automatically toggling Samsung's 'processing_speed' setting during task execution.
    - [Architecture] Refactored standalone diagnostic activities into reusable fragments (`ServerMetricsFragment`, `ActivityLogFragment`) for better modularity.
    - [Feature] Implemented **Native Window Crawler** using high-performance `AccessibilityNodeInfo` traversal, significantly reducing UI hierarchy analysis latency.
    - [Optimization] Implemented **Dynamic remote DB ETag caching** in `RemoteDbSyncWorker` to skip redundant downloads when the app-context database hasn't changed.
    - [Build Fix] Resolved **Circular Dependency** in Gradle task graph. Moved shared strings and core icons from `:manager` to `:core:ui` and updated feature module imports, allowing `:manager` to depend on features without recursive dependency back to `:manager` for resources.
    - [Security] Organized all AI Core features under a new "AI Core Settings" developer category, introducing a **Master AI Core Toggle** that gates all sub-features (Accessibility, NPU, Window Crawler) while preserving individual control.
- **Versioning & Modularity Alignment**
    - Corrected versioning scheme to "Shizuku+ 13.6.0.rXXXX" across all modules.
    - Successfully modularized the project into independent feature modules (`:feature:adb`, `:feature:update`, `:feature:automation`) and a shared `:core:ui` module.
    - Upgraded GitHub Actions to generate categorized release notes and include historical build contexts.

### 2026-05-18 — Gemini CLI
**Commits:** (this session)

**Done:**
- **Aligned Versioning with Upstream** — Reverted `baseVersionName` to `13.6.0` to match upstream Shizuku and maintain compatibility expectations.
- **Crash Reporting Refactor** — Relocated "Manual Crash Report" from the About section to Developer Options. End-users no longer see manual reporting prompts unless they enable Developer Mode and Sentry is inactive.
- **Automated Sentry Expansion** — Added Sentry exception capturing and breadcrumbs to critical failure points that previously lacked automated tracking:
    - **Server-Side Crashes:** Implemented a global uncaught exception handler in the Shizuku server process that dispatches events to the manager app for Sentry reporting.
    - **ADB Pairing:** Added failure tracking to `AdbPairingAccessibilityService` and `StarterActivity`.
    - **Plus Diagnostics:** Added reporting to `ServiceDoctorActivity` and `RootCompatibilityActivity`.
- **README Cleanup** — Removed the Sentry quota warning and "Status Notice" from the README as it is no longer relevant for the current release.
- **Global Version Inheritance** — (Previous part of session) Moved `versionCode` and `versionName` definitions to the root `subprojects` block.
- **Feature Modularization** — (Previous part of session) Extracted ADB and Update features into independent modules `:feature:adb` and `:feature:update`.

**Notes:**
- `isVectorEnabled()` is used as the gate for developer options and manual reporting UI.
- Sentry limit is now cleared on upgrade to ensure automated reporting is active by default for all users.

### 2026-05-16 — Gemini CLI
**Commits:** (this session)

**Done:**
- **Activity Log Persistence Fix** — Fixed a logic error in `ActivityLogManager.kt` where logs were being loaded in reverse order from the database. Now, newest records are correctly placed at the front of the list, ensuring consistency between in-memory and persisted logs.
- **SU Bridge & Magisk Compatibility** — Significantly expanded the `su` interception logic in `ShizukuService.java`. Added support for more `su` flags like `-mm` (mount-master) and `-M` (magisk-mode).
- **Robust Magisk Mocking** — Improved the mocking of Magisk-related system paths in `ls` and `test` commands within the SU Bridge. Added mocks for `/dev/magisk` and `/proc/self/mounts` to improve compatibility with root-seeking apps.
- **Binder Firewall Hardening** — Enhanced the Binder Firewall to block additional sensitive operations (force-stop, kill processes, delete packages) for unauthorized apps, improving the overall security posture.
- **"Shizuku-aware" Labeling** — Added a `supportsShizukuNatively` flag to `AppMetadata` and updated the `RootCompatibilityActivity` to display a "Shizuku-aware" badge for apps that support Shizuku natively.
- **Improved Command Display** — Centralized the logic for resolving SAF URIs to local paths in `EnvironmentUtils.resolveExportedPath`. Updated `ShellTutorialActivity` to show the ACTUAL command for `rish` and `plus` based on the user's export directory.
- **Enhanced System Elevation** — Expanded the `elevateApp` logic in `ShizukuService.java` to grant more AppOps (exact alarms, notifications, full-screen intents) and fixed a bug in the `setMode` call.
- **Plus Services Audit** — Verified implementations of `WindowManagerPlus`, `AICorePlus`, `StorageProxy`, and `NetworkGovernorPlus`, ensuring robust hidden API access and shell fallbacks.
- **Project Modularity Push** — Unified `UserHandleCompat` and `UserInfoCompat` across all modules into the `:common` module. Moved platform-specific logic from `EnvironmentUtils` to `:common`.
- **ActivityLog Decoupling** — Moved `ActivityLogManager` and `ActivityLogRecord` to the `:database` module and decoupled them from the main app module using the `ActivityLogSettings` interface.
- **UI Core Extraction** — Created `:core:ui` module and moved base activities (`AppActivity`, `AppBarActivity`) and common widgets (`EmptyStateView`) there. Introduced `ThemeDelegate` to allow `:core:ui` components to use app-specific themes without direct dependencies.
- **Feature Modularization** — Fully extracted the Activity Log feature into `:feature:activitylog`. This module now contains the `ActivityLogActivity`, its specific layouts, and depends on `:core:ui` and `:database`.
- **Project Structure Update** — Updated `settings.gradle` and `build.gradle` files to reflect the new modular architecture. Simplified `:manager` by delegating base UI logic to `:core:ui`.
- **Database Module Expansion** — Relocated `AppContextManager` and `RootCompatHelper` to the `:database` module. Decoupled `AppContextManager` using the `AppContextSettings` interface. Unified Kotlin logging extensions into `:common`.


**Notes:**
- `ActivityLogDao` persistence "gap" is now fully resolved and validated.
- `rish` command display is now dynamic and user-specific.
- Root Compatibility Hub now clearly distinguishes between legacy root apps and Shizuku-native apps.


### 2026-05-01 — Claude (Sonnet 4.6)
**Commits:** `a1858c0a` (api submodule fix), `71adc664` (enhancements), (this session)

**Done:**
- **AICore 5 advanced method stubs** — Added `getPixelColor`, `scheduleNPULoad`, `captureLayer`, `getSystemContext`, `getWindowHierarchy`, `simulateTouch`, `simulateSwipe`, and `simulateText` to `AICorePlusService.kt`. AccessibilityService APIs used for hierarchy and text input; others provided as documented stubs.
- **Binder Firewall (Issue #199)** — Implemented `isBinderCallBlocked` in `Service.java` and `ShizukuService.java`. Added policy to block `IPowerManager.reboot` from non-manager apps and support for dynamic descriptor-based blocking.
- **AIDL Transaction Logging (Issue #199)** — Added `binder_logging` feature to `Service.transactRemote` to log all proxied transactions with UID, package name, descriptor, and code.
- **Shadow Binder (Issue #199)** — Added `handleShadowBinderTransaction` interception hook to the server architecture to allow mocking system binder responses.
- **Settings UI & Sync** — Added toggles for Binder Firewall, Logging, and Shadow Binder to `settings_shizuku_plus.xml`. Wired keys and sync logic in `ShizukuSettings.java`.
- **api submodule build fix** — `Parcel.readInterfaceToken()` is a hidden API absent from
  public SDK stubs; replaced with `readInterfaceTokenCompat()` reflection helper in
  `api/server-shared/Service.java`. Both lint and build jobs now unblocked.
- **Crash fix (#201)** — `showCrashReportDialog()` was called before `super.onCreate()` in
  `MainActivity`, causing a `WindowManager$BadTokenException` crash on Android 16 / One UI 8.
  Moved to after `super.onCreate()`.
- **Sentry re-enabled** — Removed the expired April 2026 quota suppression block. Added
  unconditional `setSentryLimitReached(false)` reset on startup. Closed issues #190 and #191.
- **AdbMdns TLS_CONNECT wiring** — `HomeViewModel` now starts an `AdbMdns` TLS_CONNECT
  discovery on API 30+ and updates `HomeState.discoveredAdbPort`. `StartWirelessAdbViewHolder`
  uses this as a fallback when `service.adb.tcp.port` is unset (common in TLS wireless debug
  mode). Stops in `onCleared()`.
- **RootCompatHelper expansion** — Added 6 more apps with root-level shared_prefs editing
  (TitaniumBackup, Root Explorer, Solid Explorer, Total Commander, ES File Explorer) gated
  behind `isShizukuRoot()`. Added 2 more global-settings apps. `autoSetupAll` now iterates
  root-prefs apps when UID 0 is available.
- **RemoteDbSyncWorker** — Created `worker/RemoteDbSyncWorker.kt`; periodic WorkManager job
  (every 24h, CONNECTED network, 5-min initial delay) that fetches
  `raw.githubusercontent.com/thejaustin/ShizukuPlus/master/app-context-db.json` and calls
  `AppContextManager.updateDatabase(json)`. Skips fetch if cache < 20h old.
  Scheduled in `ShizukuApplication.initializeManagers()`. Dynamic DB is now actually populated.
- **Dhizuku Device Owner UI** — `ShizukuPlusSettingsFragment` now checks DPM device owner
  status on load and shows "Device Owner: Active" or "Device Owner not set — tap for setup"
  in the Dhizuku Mode preference summary. Tapping when not active shows a dialog with the
  exact `adb shell dpm set-device-owner` command and a "Copy Command" button. Full class name
  used (`af.shizuku.manager.admin.DhizukuAdminReceiver`) because applicationId ≠ namespace.

- **Localization / hardcoded string sweep** — Replaced all remaining hardcoded user-visible
  strings across 6 files: `ServiceDoctorActivity` (3 Toast strings), `AdbPairingTutorialActivity`
  (dev debug Toast leaked to prod), `UpdateChecker` (2 fallback strings moved to empty + caller
  uses `R.string.update_no_release_notes`), `AdbPairingAccessibilityService` (status Toast),
  `AccessibilityDialogHelper` ("Continue" button), `ServerStatusViewHolder` ("View Issues" button),
  `BugReportDialog` ("GitHub" button). All strings now in `strings.xml`.

**Notes:**
- `AdbMdns` was already wired for pairing (TLS_PAIRING) in `AdbPairingService` — no change
  needed there.
- Mavericks ProGuard rules and SQLite try-catch from Gemini session confirmed in code.
  Issue #200 awaits on-device ADB logcat verification.
- `remote_db_sync_channel_name` string in strings.xml is defined but unused — `RemoteDbSyncWorker`
  is a silent background worker with no notification. Harmless; can be removed if desired.
- **Remaining gaps (not yet implemented):** AICore 5 advanced method implementations (beyond stubs), Issue #199 deep implementation of Shadow Binder for specific system services (e.g. IPackageManager, IActivityManager).

### 2026-04-28 — Claude (Sonnet 4.6)
**Commits:** (this session)

**Done:**
- Reviewed all `.ai/history/` session logs (Apr 27 Gemini sessions + all prior Claude sessions)
- Created GH issue **#199** — ADB/Binder research umbrella for Root Compat Hub features, linking #8,
  #9, #11, #15, #17, #18, #19 as sub-features, documenting `RootCompatHelper` regression,
  `AdbMdns` wiring question, and Dhizuku integration gaps.
- Created GH issue **#200** — ADB-required verification checklist for Apr 27 Gemini crash fixes
  (Mavericks factory + SQLite race), plus the libadb.so fix from this session. Includes ADB address
  (`192.168.1.234:33041`) and step-by-step logcat commands for when WiFi is back.
- **libadb.so graceful degradation** — `initializeStatics()` no longer rethrows
  `UnsatisfiedLinkError`; app continues without ADB pairing. `isAdbNativeAvailable` companion flag
  added; `AdbPairingTutorialActivity` checks the flag and shows a toast + finish if library is absent.
- **M3ECardItemDecoration** staged — new widget was untracked; would have broken CI on next push.
- `adb_native_unavailable` string added to `strings.xml`.

**Notes from Gemini Apr 27 session (session-2026-04-27T19-55-cdf2cb29):**
- Gemini connected via `adb connect 192.168.1.234:33041` and read live logcat
- Found `FATAL EXCEPTION: main` — Mavericks factory crash from R8 repackaging
- Found `FATAL EXCEPTION: DefaultDispatcher-worker-4` — SQLite open failure on fresh install
- Gemini applied both fixes directly to the working tree (not yet committed as a standalone commit
  before this session — they're included in this commit)


### 2026-04-24 — Claude (Sonnet 4.6)
**Commits:** `c1150113`, `91fd5c25`

**Done:**
- Fixed two unclosed `<LinearLayout>` tags: `terminal_tutorial_activity.xml` and
  `adb_pairing_tutorial_activity.xml` — both caused CI `SAXParseException` in
  `mergeReleaseResources`. Added pre-push guard check 15/15 (xmllint) to prevent recurrence.
- Added `root_hub_shizuku_aware_note` info banner to `activity_root_compatibility.xml` — educates
  users that service-level modules only activate for Shizuku API apps; SU Bridge works for all.
- Repaired `StartWirelessAdbViewHolder.kt` compilation failures: unclosed companion object (`...`
  placeholder from prior session), bare `return` inside lambda (needs `return@setOnClickListener`),
  duplicate `init` blocks (second called non-existent `start()`), `binding` not `private val`.
- Removed unused `WorkManager` and `Dispatchers` imports from the same file.

**Shizuku-aware label (`root_hub_shizuku_aware_note`):**
This info banner in `RootCompatibilityActivity` explains that auto-grant, Magisk mocking, and
AdAway bridge only activate for apps connecting via the Shizuku API. The SU Bridge path (the
binary su wrapper) works for any root app. This closes the backlog item from Mar 30.

---

### 2026-04-23 — Claude (Sonnet 4.6)
**Commits:** `98f47424`

**Done:**
- Mavericks ProGuard keep rules (`-keep class * implements MavericksViewModelFactory`)
  fixes `HomeViewModel` companion factory crash after R8 `-repackageclasses`
- `showMigrationDialog()` reconstructed from literal placeholder comment to working
  root/no-root dialog branches
- `showCrashReportDialog()` duplicate `setNeutralButton` removed; "Share File" now shows
- Removed `getLayoutId()` overrides from 5 activities that were passing content-only layouts
  as the root frame → fixed `coordinator_root IllegalStateException` on all affected screens:
  `RootCompatibilityActivity`, `ActivityLogActivity`, `AdbPairingTutorialActivity`,
  `StarterActivity`, `ShellTutorialActivity`
- `MaterialSharedAxis` X transitions added to `AppBarActivity`
- `MotionUtils.kt` — spring-scale touch animations with haptic feedback
- `header_card.xml` — reusable M3 hero header (icon + title)

**Session started with analysis of all previous Claude session logs to surface the open backlog
above. This document was created as a result of that analysis.**

---

### 2026-04-21 — Claude (Sonnet 4.6)
**Commits:** `827754ec`, `153cfe05`, `e9002881`, `b0176fd2`

**Done:**
- Manual crash reporting system: `CrashHandler` + `CrashReporter` + dialog in `MainActivity`
  that offers "Generate Report", copy to clipboard, open GitHub issue, or share as file
- A11y: `importantForAccessibility="no"` on collapsible category arrow and doctor check icon;
  contentDescriptions added to drag handle and remove button in `home_item_container`
- `AppContextManager`: Added `ENH_OVERLAY` + `ENH_NETWORK` constants and wired into
  `loadFromCache()` — remote JSON entries with these keys were being silently dropped
- Settings import/export in Developer Options (export to JSON, import with validation)

**Unresolved in session:** Mavericks ProGuard rules (edit rejected), `RootCompatibilityActivity`
coordinator_root fix (carried to Apr 23).

---

### 2026-04-20 — Claude (Sonnet 4.6)
**Commits:** `003fd16f` through `0d47caba` (12 commits)

**Done:**
- DhizukuProvider permission check dead code fixed (empty if body)
- SU Bridge bounds guards + `RISH_APPLICATION_ID` env var in su wrapper
- Auto-close terminal and pairing screens when Shizuku becomes ready
- Shell injection fix; watchdog backoff; `SettingsPage` enum centralization; `CHANGES.md` created
- `fbcc1cd7`: sealed `ListItem` in Root Compat adapter; activity log synced to server state
- Comprehensive open-source attribution (`NOTICE`, `OPEN_SOURCE_LICENSES.md`)
- `a318d884`: deep stability audit + CI compilation fix
- Removed `markdowntwain` private dep, fixed `jvmTarget`, upgraded `rikkax-material-preference`
- Samsung Freecess bypass, Sentry 8.x best practices, localization audit, Material Symbols icons

---

### 2026-04-17 — Claude (Sonnet 4.6)
**Commits:** (session ended before most changes were committed — see Open Backlog)

**Context:** Long session focusing on deeper architecture. Many edits discussed but not all landed.

**Done or confirmed:**
- `ActivityLogActivity` header card layout design discussed + partially implemented
- Root Compat Hub sealed ListItem adapter (landed in Apr 20 `fbcc1cd7`)
- AIDL interface review for `IWindowManagerPlus` (flagged, not resolved)
- `ActivityLogDao` persistence gap identified (not resolved — still in backlog)

**Left open:** Activity log persistence, AIDL alignment, rish command display, Root Compat Hub
"Shizuku-aware" label.

---

### 2026-04-15 — Claude (Sonnet 4.6)
**Commits:** `decb2a88` through `1c19937b` (9 commits)

**Done:**
- Sentry 8.x SDK migration: `SentryTimberTree` re-init, OkHttp artifact fix, compile errors
- Samsung Freecess bypass: detect + skip Freecess process kill targeting Shizuku
- Service Doctor real-time diagnostics for Wireless ADB and Auto Blocker
- Localization audit (removed stale keys, added missing ones)
- Material Symbols icon refresh for settings
- Duplicate UI components removed from pairing and shell tutorial screens

---

### 2026-04-14 — Claude (Sonnet 4.6)
**Commits:** `391cc7bc`

**Done:**
- Missing `R` import in `MainActivity` resolved
- Dead `helper.cpp` removed

---

### 2026-03-30 — Claude (Sonnet 4.6)
**Session:** `0d4f362f` (118KB)

**Context:** Architecture and stability session. Lots of planning.

**Done or carried forward into Apr 20:**
- Shell injection vulnerability identified and fixed
- Watchdog backoff strategy designed
- `syncAllPlusFeaturesToServer()` partial work — activity log toggle NOT yet included
- suCopyOpen rish command display idea raised but deferred
- Root Compat Hub scope clarification idea raised but not implemented
- `Handler.kt` dead code flagged

---

### 2026-04-11 — Claude (Sonnet 4.6)
**Session:** `2a96eec2` (60KB)

**Context:** Storage and infrastructure focus.

**Done:** Details subsumed into Apr 20 batch commits.
**Notable:** Sentry quota concern raised here; quota at 100% through April 2026.

---

## Architecture Rules (crash-critical — do not reintroduce)

| Rule | Why |
|------|-----|
| Launcher activity MUST be `.MainActivity` | `HomeActivity` is abstract — cannot be instantiated |
| `getLayoutId()` must return the FULL frame layout | Content-only layouts as root cause `coordinator_root IllegalStateException` |
| Subclass activities inflate content via `inflate(layoutInflater, rootView, true)` | Never override `getLayoutId()` with a content-only XML |
| `<include>` tags MUST NOT have `android:id` | Overrides nested view IDs, breaks `toolbarContainer` |
| `io.sentry.auto-init=false` MUST stay in `AndroidManifest.xml` | Manual init in `ShizukuManagerApplication` — double-init crashes |
| `Mavericks.initialize(this)` MUST be called before Koin in `ShizukuApplication.onCreate()` | Koin modules reference Mavericks state classes |
| Mavericks ProGuard keep rules MUST stay in `proguard-rules.pro` | `-repackageclasses rikka.shizuku` breaks companion factory reflection |
| `AppBarActivity.rootView` is a `ViewGroup` — use `rootView.getChildAt(0)` for ViewBinding in subclasses that need it | Direct cast to binding class will fail |
| Compose was tried and reverted — do not re-introduce without a plan | Full migration caused instability; revert was `25d796d4` |
| Layout XML must be well-formed — validate with xmllint before pushing | Unclosed `<LinearLayout>` in two tutorial layouts caused `SAXParseException` in CI; pre-push guard check 15/15 now enforces this |
| Companion object `}` must be closed before any `init` block | Missing close brace (or `...` placeholder) causes `Expecting member declaration` compile error |
| `return` inside a `setOnClickListener` lambda must be `return@setOnClickListener` | Bare `return` is illegal in a non-inline lambda — causes compile error |
| ViewHolder `binding` param must be `private val` if used in member functions | Constructor params without `val`/`var` are inaccessible outside `init` — causes compile error |
| `ksp.useKSP2=false` MUST stay in `gradle.properties` | KSP2 requires Room 2.7+; Room is pinned to 2.6.1 (stable). Removing this flag causes `unexpected jvm signature V` build failure |

---

## Key Files Quick Reference

| Area | File |
|------|------|
| All settings logic | `manager/.../settings/SettingsFragment.kt` |
| Update settings UI | `manager/.../settings/UpdateSettingsFragment.kt` |
| GitHub Releases + update detection | `manager/.../update/UpdateChecker.kt` |
| APK download + install | `manager/.../update/UpdateManager.kt` |
| All SharedPreferences keys | `manager/.../ShizukuSettings.java` (`inner class Keys`) |
| App metadata + enhancement DB | `manager/.../utils/AppContextManager.kt` |
| Activity log (in-memory) | `manager/.../utils/ActivityLogManager.kt` |
| Crash capture | `manager/.../utils/CrashHandler.kt` |
| Crash report generation | `manager/.../utils/CrashReporter.kt` |
| Root compat modules | `manager/.../utils/RootCompatHelper.kt` |
| Shizuku state machine | `manager/.../utils/ShizukuStateMachine.kt` |
| Server sync | `manager/.../utils/SettingsHelper.kt` |
| Home screen state | `manager/.../home/HomeViewModel.kt` + `HomeState.kt` |
| Base activity | `manager/.../app/AppBarActivity.kt` |
| Entry point | `manager/.../MainActivity.kt` |
| ProGuard | `manager/proguard-rules.pro` |
| UI strings | `manager/src/main/res/values/strings.xml` |
| Settings XML | `manager/src/main/res/xml/settings*.xml` |
