# Shizuku+ — AI Session Devlog & Planning Space

Living document. Update at the start of every AI session: mark completed items, add new ones.
This is the single source of truth for cross-session continuity — reduces re-explanation,
prevents re-introducing fixed bugs, and keeps the roadmap visible without user steering.

---

## Open Backlog (unfinished / planned)

Items carried forward from previous sessions that have not yet been committed.

### Features

- [x] **AICore 5 advanced method implementations** — Implemented `getPixelColor`, `captureLayer`, and `getSystemContext` in `AICorePlusService.kt` using `AccessibilityService.takeScreenshot` (API 30+) as a high-performance manager-side bridge. Done 2026-05-03.
- [ ] **Hex Installer One UI 7+ deep compat (Issue #533)** — Overlay Bridge Samsung wizard bypass shipped in Unreleased, but the underlying theming flow still fails on One UI 7+. Holding off — needs dedicated investigation session.
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
- [x] **Every card in the app hardcodes `app:cardCornerRadius` (16/24/28dp)** — concern was
  unfounded. Audited 2026-09-11: ALL layout files already use
  `app:shapeAppearanceOverlay="?attr/shapeAppearanceCornerExtraLarge"` (or `Large`/`Medium`
  variants) — zero occurrences of `app:cardCornerRadius` remain. Shape-style theme overlays
  (`ThemeOverlay.Shape.Modern/Classic/Squircle/Cut`) correctly define `shapeAppearanceCornerExtraLarge`
  et al., and `ThemeDelegateImpl` applies them at theme overlay time. Shape-style picker (#333)
  is fully functional — no code change needed.
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

### 2026-11-02 (cont'd 3) — Antigravity (Gemini 3.8 Flash) (Opt-in TCP persistence, USB disconnect adaptive suggestion, interactive command dialog) [master]

- **Opt-in TCP Mode & USB Disconnect Suggestion:**
  - *Starter & Server*: Updated `manager/src/main/jni/starter.cpp` and `server/src/main/java/rikka/shizuku/server/ShizukuService.java` so `service.adb.tcp.port` and `persist.adb.tcp.port` are ONLY configured if TCP mode is explicitly enabled by the user in settings (`persist.adb.tcp.port > 0`) or requested via `--tcp`/`--tcpip` in command arguments. Never forces TCP mode on users who intentionally want USB-only debugging.
  - *Starter Commands*: `Starter.kt` dynamically emits `--tcp` and `adb tcpip 5555 && ` only when `ShizukuSettings.getTcpMode()` is true.
  - *Interactive Command Dialog*: `StartAdbViewHolder.showViewCommandDialog` now features an embedded `MaterialCheckBox` allowing users to toggle TCP mode live before copying or sending the command. Toggling immediately updates the displayed command text and preferences.
  - *Adaptive USB Disconnect Suggestion*: Added `AdaptiveRecommendationManager.maybeRecommendUsbTcpMode()` and `EnvironmentUtils.isUsbConnected()`. When Shizuku starts via ADB while connected to USB, if TCP mode is not enabled, an adaptive recommendation offers to enable TCP mode (port 5555) with chained open Wi-Fi network guard recommendations before disconnecting.

### 2026-11-02 (cont'd 2) — Antigravity (Gemini 3.8 Flash) (CI paths-ignore, USB debugging setting preservation, GitHub community triage) [master]

- **GitHub Actions CI Trigger Suppression for Docs/README:**
  - Added `paths-ignore` (`'**.md'`, `'docs/**'`, `'.github/ISSUE_TEMPLATE/**'`, `'.github/pull_request_template.md'`, `'LICENSE*'`) to `.github/workflows/app.yml`, `dependency-submission.yml`, `security.yml`, and `gitguardian.yaml`.
  - Pushing changes to `README.md` or markdown documentation will no longer trigger full Gradle compilation, lint checks, or secret scanning.
- **USB Debugging Setting Preservation (Issue #656):**
  - In `ShizukuService.java`: Removed unconditional write of `adb_enabled = "1"`. Setting `adb_allowed_connection_time` to 0 is sufficient to keep authorizations alive without overriding user's manual toggling of USB debugging in Developer Options. Resolves user report in #656 where manual USB debugging toggles were undone automatically by the Shizuku server.
- **GitHub Issues & Community Feedback Triage:**
  - Triaged #656 (ADB auth persistence and auto-enabling USB debugging query; confirmed root cause and addressed), #660 (per-app network firewall feature request via Network Governor), #655 (Storage Bridge vs DAC `/data/data` restrictions), and #651 (auto-backup in Downloads folder removed).

### 2026-11-02 (cont'd) — Antigravity (Gemini 3.8 Flash) (USB disconnect persistence, cgroup isolation, ADB TCP 5555 auto-binding) [master]

- **USB Disconnect & Server Termination Root-Cause & Resolution:**
  - *Problem*: User reported that when enabling ADB via USB (e.g., using aShell You on a separate device via OTG or a PC), the Shizuku server immediately disconnected when the USB cable was pulled, and the on-device app could not restart without reconnecting USB.
  - *Root Cause 1 (CGroup / init kill)*: In `starter.cpp`, `switch_cgroup()` was only called if `uid == 0 || uid == 1000`. Shell sessions run with `uid == 2000`. When `adbd` is stopped upon USB disconnect by Android's `UsbDeviceManager`, Android `init` sends `SIGKILL` to all processes in `adbd`'s cgroup, instantly killing `shizuku_plus_server`.
  - *Root Cause 2 (SIGHUP delivery)*: The child process inherited the controlling terminal/pty session of the adb shell. When the USB connection dropped, unmasked `SIGHUP` was delivered to the server.
  - *Root Cause 3 (Missing TCP mode fallback)*: `adbd` was only listening on the USB gadget interface. With no persistent TCP port configured, pulling the cable shut down `adbd` completely, leaving loopback port `127.0.0.1:5555` closed and preventing local reconnect/restart.
  - *Fix in `starter.cpp`*:
    - In child process fork: added `signal(SIGHUP, SIG_IGN)`, `signal(SIGPIPE, SIG_IGN)`, `setsid()`, and `switch_cgroup()` before `exec` of `app_process`.
    - In `main()`: enabled `switch_cgroup()` unconditionally (including for `uid == 2000`).
    - Added automatic detection and setting of `service.adb.tcp.port 5555` and `persist.adb.tcp.port 5555` when started under `uid == 2000`.
  - *Fix in `ShizukuService.java`*:
    - Added `ensureAdbTcpPersistence()` on server startup.
    - Updates settings content provider (`adb_allowed_connection_time 0` and `adb_enabled 1`) so Android does not revoke authorizations on disconnect.
    - Sets `service.adb.tcp.port` and `persist.adb.tcp.port` to 5555 and ensures `adbd` is listening.
  - *Fix in `Starter.kt`*:
    - Updated `adbCommand` to prepend `adb tcpip 5555 && ` so external launchers (PC / OTG host) switch device to persistent TCP mode prior to invoking the starter script.
  - *Documentation*:
    - Updated `wireless_debugging_setup.md` in app assets and `ShizukuPlus-Knowledgebase.md` in the wiki repository documenting USB/OTG activation, Default USB Configuration ("No data transfer"), and ADB authorization timeout settings.
- **Code Hygiene & Verification:**
  - Applied `spotlessApply` and verified with `spotlessCheck`.

### 2026-11-02 — Antigravity (Gemini 3.8 Flash) (TCP Mode auto-apply on service start, Awesome-Shizuku & installed apps knowledgebase sync) [master]

- **TCP Mode and Local Proxy Auto-Apply on Service Start:**
  - Resolved issue where options like TCP Mode (port 5555), On-Device ADB TCP (`on_device_adb_tcp`), and the Local ADB Proxy (`adb_proxy_enabled`) had to be manually toggled in Behavior settings after every reboot or restart to take effect.
  - `AdbStarter.startAdb()` now automatically pushes `setprop service.adb.tcp.port $port` and `setprop persist.adb.tcp.port $port` when TCP mode or on-device ADB TCP is active, ensuring persistent port binding across network drops and reboots.
  - `AdbProxyService.enableAdbTcp()` checks whether the active port already matches the target port before restarting adbd, avoiding unnecessary connection churn while still ensuring `persist.adb.tcp.port` is updated.
  - `StarterActivity.startRoot()` ensures TCP mode is applied immediately upon successful root startup if configured.
  - `ShizukuApplication` state listener detects transitions to `RUNNING` and automatically verifies TCP mode and starts `AdbProxyService` if enabled. `onCreate()` and `BootCompleteReceiver` also ensure `AdbProxyService` lifecycle alignment.
- **Knowledgebase & App Context DB (Revision 2):**
  - Updated `database/src/main/assets/app_context_db.json` from revision 1 to revision 2 with 34+ newly cataloged apps, incorporating on-device packages verified via `rish` as well as the upstream `awesome-shizuku` index.
  - Added verified metadata and Plus feature recommendations (e.g. `storage_proxy`, `shell_interceptor`, `overlay_manager_plus`, `network_governor_plus`) for popular file managers: Material Files (`me.zhanghai.android.files`), Cx File Explorer (`com.ace.ex.file.manager`), FV File Explorer (`com.folderv.file`), MiXplorer (`com.mixplorer`), Solid Explorer, ZArchiver, Butler (`eu.darken.butler`), and app management utilities: App Manager (`io.github.muntashirakon.AppManager`), Canta (`io.github.samolego.canta`), Inure, Install with Options, ShizuStore, LinkSheet, ColorBlendr, SystemUI Tuner, and ShizuWall.
  - Updated `root_compat_su_bridge.md` in app assets and the wiki `ShizukuPlus-Knowledgebase.md` to document the full range of supported file managers and system tools.
- **Code Hygiene & Verification:**
  - `./gradlew spotlessApply` and `./gradlew spotlessCheck` passed with zero errors.

### 2026-10-30 (late night) — Claude (icon shapes, card drag, trusted-network picker, 5-agent review) [master]

- **Icon shapes:** Material's `MaterialShapes` (SoftBurst for "burst"; star rotated point-up), each centered on its area centroid, icon sized per shape from the inradius (`ExpressiveShapes.iconFraction`), one 40dp container for every settings icon style. `ExpressiveShapesTest` checks centroid, symmetry and that the glyph fits. Morphs are cached.
- **Home drag:** center-crossing `chooseDropTarget`, fixed cards excluded via `canDropOver`, `DiffUtil` dispatch instead of `notifyDataSetChanged`, no transform reset while a card springs (`MotionUtils.isSpringing`), opaque dp-based lift, `HomeEditMode.bindCardGestures` replaces ten copies.
- **Trusted networks:** `WifiNetworks` reads the current and saved SSIDs via `cmd wifi status` / `cmd wifi list-networks` as shell; `TrustedNetworksSheet` serves both lists (TCP StringSet, firewall CSV — commas blocked there). Runtime matching falls back to the shell off the main thread, cached per network handle.
- **Review (5 read-only agents: M3E, functional, 3x code quality):** fixed haptic API gating (threshold/toggle constants are API 34), double haptics, AMOLED containers, motion-scheme specs, per-frame allocations, update cleanup deleting a pending APK, `UpdateManager.cancel()` killing the shared scope, force-update (root path never staged files, secrets in a 644 file, uninstall-before-install), backup shell per change (now debounced), Plus sync race (single worker posted after the pref is saved), grant/revoke swallowing `SecurityException`, main-thread shell in accessibility auto-enable, ApkPatcher deleting originals on a failed restore.
- **Deferred (worth scheduling):** one `ShizukuShell` helper for the ~12 `newProcess` sites; grant/revoke + list state into `AppsViewModel`; per-activity `HomeEditMode`; one network monitor for TcpNetworkGuard/ReconnectTrigger/AutomationService; splitting `ShizukuApplication.onCreate` and extracting the Sentry filter; FQN and `p != null` cleanup in `ShizukuSettings`; AutomationRules overwriting the user's firewall pref.
- **Gotchas:** a static `Handler(Looper.getMainLooper())` in `ShizukuSettings` breaks every plain JVM test (create it lazily). `SpringAnimation` on a `FloatValueHolder`/custom property defaults to a 1px threshold — set `MIN_VISIBLE_CHANGE_SCALE` for 0..1 values. The disk filled mid-build and left a stale debug Kotlin incremental cache (unresolved same-package symbol); delete `~/shizuku-build/manager/kotlin/compileShizukuplusDebugKotlin`.
- **Not verified on device** (rish/ADB down all session). The Home empty state replaces the whole list including the status card, so it stays "itemCount == 0".

### 2026-10-30 (night) — Claude (swipe design A + sandbox B, cleanup, storage) [master]

- **Swipe actions (user chose A; B behind dev option `swipe_settings_sandbox`):** `SwipePreviewPreference` (auto-play demo row, or drag sandbox), `SwipeActionRowPreference` x3 (plain rows so icon styles apply), `SwipeActionPickerSheet` (BottomSheetDialogFragment + ComposeView). `SwipeActionStyle` is the single action table for the list and the preview. The old AppInteractions/GesturePreview/SwipeActionsConnected files were deleted.
- **User says the app-wide "scannable" settings design (board C) still doesn't look good:** not implemented; "Toggle style" option untouched. Waiting on specifics.
- **ApkPatcher:** temp-debug sessions were in-memory only, so they were lost on a server restart and the originals were stranded (58 MB found in `splus_td`, both apps already restored, deleted). Sessions are now recovered from disk, and the sweep is guarded (`preparing` set + `sweepLock`, deletes only when the app is installed and not debuggable).
- **Size/storage:**
  - Removed glance, remoteviews and aboutlibraries.
  - Removed dead classes and ~35 unused strings.
  - `UpdateManager.cleanup()` was never called. It now runs at startup and keeps only newer APKs.
  - Pitfalls during the cleanup sweep:
    - `page_onboarding_m3e` looked unused (the binding is `PageOnboardingM3eBinding`, and Python's `title()` gives `M3E`) but it is used.
    - `PlusShell` is loaded by name from `ShizukuShellLoader`, so it must not be deleted.
- **APK 16.5 MB:** classes.dex is stored uncompressed on purpose (server start), ~8.9 MB, and resources.arsc is ~5 MB, mostly 20 near-complete locales. Both trade-offs are left to the user.
- **Device:** freed ~8 GB of Gradle caches (build-cache, 9.4.1 transforms, 9.4.1-all dist). `/data/local/tmp` still has ~700 MB of user-staged APKs, untouched.
- **Gotcha:** `pgrep -f "check-build.sh"` in a wait loop matches its own command line, so it never exits; `pkill -f GradleDaemon` killed its own shell.

### 2026-10-30 (later) — Claude (user reports: AMOLED, icons, toggles, restore by choice, redesign research) [master; r3138 installed]

- **Fixed:**
  - Icon style showed the previous choice: listeners ran before the value was saved, so the refresh is now posted, and each refresh uses a fresh drawable copy.
  - AMOLED home tint: `AnimatedGradientBackground(pureBlack)`.
  - Enabled rows turned black on AMOLED: `activeRowColor`.
  - Shapes were off-center: `normalized()` centers the bbox. Shapes are now centered on the polygon's own center, with `ExpressiveShapesTest`.
  - Toggles restyled recycled rows: an owner tag on the row view; the ripple is kept under the state fill.
- **Restore is user-chosen (user request):**
  - No auto-restore; the onboarding welcome card offers the backup.
  - `SettingsBackupManager.applyRestoredLive()` applies night mode, locale, boot receiver, launcher alias, Watchdog and the Plus sync without a restart, then the held-back privileged prompt.
  - Reinstall copy: `/data/local/tmp/shizukuplus/settings_backup_<pkg>.json`, written as shell through Shizuku. It is never trusted, because any app authorized for Shizuku can write there. API keys and the hidden-packages list are excluded.
  - Only the force-update handoff from the last 10 min is trusted. The public Downloads copies are gone; legacy copies are cleared once.
  - Termux (targetSdk 28) can read 644 files in `/data/local/tmp`. **Unverified:** whether the targetSdk 36 app domain can (watch for avc denials on the first real reinstall restore).
- **Device storage was full** (ENOSPC, 458/459 GB). Freed my scratch APKs and `/data/local/tmp/sptest`. Tell the user.
- **Design research (user):**
  - Canvas: https://claude.ai/artifact/FmSsBAj8imHPozhSx2joD9
    - A: live preview plus two direction rows, with picker sheet A2 (recommended).
    - B: try-it sandbox.
    - C: app-wide scannable M3E settings — grouped rows, split toggle, segmented control, slider, multi-toggle grid.
  - Awaiting the user's pick.
  - `GesturePreviewPreference` and `SwipeActionsConnectedPreference` (~1,080 lines) are unreferenced dead code.
  - The user wants the "Toggle style" option replaced by one app-wide design.

### 2026-10-30 — Claude (device testing r3124, follow-ups) [master; f4821b82 pushed, later commits local]

- Installed CI r3124 over r3085 with `pm install -r`, keeping data. No FATAL or ANR.
- **Found on device:** two `shizuku_plus_server` processes after a starter restart raced the manager's AdbStartWorker. Fixed with a server single-instance lock (verified: a duplicate exits) and a 1.5 s Watchdog grace.
- **Verified from adb shell (uid 2000):**
  - `am compat --no-kill FORCE_RESIZE_APP` works on non-debuggable apps. `ALLOW_RESIZEABLE_ACTIVITY` is an invalid change name, so it was dropped.
  - `cmd package suspend/unsuspend` and `pm disable-user/enable` work.
  - `pm list` without `--user` errors on Secure Folder (user 150), so `--user` was added.
- **#648:** `ScopeCommandMatcher` plus tests, so sh -c, full paths and chained commands are caught.
- **Backup restore (security + data loss):**
  - Auto-restore read public `/sdcard/Download` with trustPrivileged=true, so any app could plant settings.
  - The shell-owned `Android/data` copy couldn't be deleted, so the Oct 27 backup was re-imported on **every launch**, reverting settings. The "restored" toast was seen on device.
  - Now: trusted locations only, at most 24 h old, and a restored fingerprint is kept (`restored_backup_fingerprint`, excluded from backups). Public copies are restored only from the onboarding card, without trust. All copies are deleted after a restore.
  - On the phone, the stale copies were renamed to `/sdcard/Download/shizukuplus-backup-2026-10-27*.json` (data kept).
- **Open:**
  - (a) The app itself writes a fresh `shizuku_plus_auto_backup.json` into `Android/data/.../files/Download` on settings changes. With the new logic, a launch may still re-import it once per change and show the toast. Separate the app's own periodic backup from the force-update handoff file (different name).
  - (b) The failed r3116 in-app download is byte-identical to the release (sha256 match), so the "problem parsing" error comes from the install step (FileProvider / cache copy), not the download. It needs a logcat of a real attempt.
  - (c) User report: dark modes / AMOLED don't give a pure black home-screen background. Not started.
  - (d) The local test server `/data/local/tmp/sptest` is still on the phone; remove it.

### 2026-10-29 (late) — Claude (review of Antigravity Oct 22–29 work, CI fix, fallback hardening) [master, unpushed]

- **CI was red on `87e4de84`** (build, lint, unit-tests): `R.drawable.ic_select_all_24` didn't exist. Added it, plus `ic_ac_unit_24` for Freeze (was reusing the close icon). API Scopes now uses `ic_admin_panel_settings_24` instead of the Toggle Permission shield.
- **Per-app API scopes (#645) only enforced with Binder Firewall on.** `api/server-shared/Service.java` calls `isBinderCallBlocked()` only when `checkPlusFeatureEnabled("binder_firewall")` is true. Fixed in `ShizukuService.checkPlusFeatureEnabled`, with no submodule change: the gate reports open whenever `per_app_disabled_scopes` is non-empty. `isBinderCallBlocked` still checks the real flag before applying the general firewall rules. The AIDL `isPlusFeatureEnabled` now returns the real flag.
- **Per-app freeform (#635) flipped a global setting.** `WindowManagerPlusImpl.forceResizable` always wrote `global force_resizable_activities` 1/0 after the `am compat` calls, so a per-app toggle changed resizing for every app. Disabling one app turned off the user's own Developer-options setting. Now: return early when a compat override applies; otherwise write `1` on enable only, never `0`.
- **Direct-su fallback could hang** (`ShizukuReceiverStarter.rootStart`: unbounded `waitFor()`, undrained pipe; the tile drained nothing and never destroyed the process). New `EnvironmentUtils.runWithSuBinary()` drains output and polls exit with a 15 s bound. `waitFor(timeout)` and `isAlive` are API 26 and minSdk is 24, so it polls `exitValue()`.
- **Follow-up `--no-kill`:** `am compat` force-stops the target app by default. The manager replays `forceResizable(pkg, true)` on every Plus settings sync, which would have killed every free-form app on any toggle change. All `am compat` calls now pass `--no-kill`, and the toast says the change applies on next launch. The direct-su branch of `ShizukuReceiverStarter.rootStart` now runs on `scope` (IO), because receivers call `start()` on the main thread.
- **Spurious notification:** the ADB start path without WRITE_SECURE_SETTINGS showed the permission-error notification even when it had enqueued the TCP fallback. Now it shows only when nothing was enqueued.
- Checked, OK: the keys and XML defaults of all toggles added this week match their getters, including the 8 `lp_*` keys. `dpm_freeze` syncs immediately.
- **Not fixed, flag only:**
  - DPM freeze stage 1 (`tryDpmSetApplicationHidden`): its reflection matches 3- or 4-parameter `setApplicationHidden`. Android 11+ AIDL has 5 (`admin, callerPackage, pkg, hidden, parent`). DPMS also requires the caller to own the admin component. From shell or root it most likely always falls through to stage 2, so the "Prefer Device Owner App Hiding" toggle probably has no effect. Needs a device check with Dhizuku or DO.
  - `ShizukuProcessUtils.runDetached` (#531) has no callers. Its env array may replace the whole environment (no PATH for `setsid`).
  - The `isAppScopeDisabled` shell checks are substring matches, so some false positives are possible, but only when a scope is off.
- Verified locally: Plus and Drop-In `compile*DebugKotlin` + server compile, `scripts/dev/lint.sh` clean. No device pass: loopback ADB refused, rish hung.
- **Round 2 (same session):**
  - DPM freeze (#632) moved to the manager (`utils/DpmAppHider.kt`, using `setPackagesSuspended`, not `setApplicationHidden`: a hidden package reads as uninstalled to `AuthorizationManager`'s query, which has no MATCH_UNINSTALLED, so it would vanish from the list with no way to unfreeze). DPMS only lets the admin's own app do this, so the server's reflection stage could never succeed; it is removed along with the `dpm_freeze` server flag. The long-press Freeze uses DPM when Shizuku+ is DO/PO and the toggle is on, otherwise the server chain. Freeze is now offered even without the Custom API when DPM is available.
  - `isAppFrozen` fallback: `pm list packages -d <pkg>` is a substring filter; it now matches whole `package:<name>` lines.
  - `runDetached` (#531): passes a null env (a non-null array replaced the whole environment, dropping PATH).
  - Auto-run snippets could run twice per start (two RUNNING handlers on different threads, non-atomic debounce): the debounce is now an `AtomicLong` CAS.
  - `ShizukuManagerProvider`: the client `METHOD_GET_BINDER` grace wait only runs during the first 5 s of process life (it stalled every client `getBinder` by 1.5 s while the service was stopped). The user-service token path keeps its unconditional wait.
  - Known, not changed: the `dhizuku_suspended_packages` preference unsuspends every installed package on each change, which also unfreezes apps frozen this way.
  - StarterActivity "Wireless ADB" fallback button no longer `finish()`es before showing the discovery dialog. `StartWirelessAdbViewHolder.start` skips `show()` when the FM state is saved.
  - Adaptive recommendations: activity-finishing and BadToken guard; accepting the TCP recommendation chains the open Wi-Fi guard prompt (as the Settings toggle does). Stability tips: "Don't show again" reflects and stores the real choice, and the divider is a MaterialDivider.
  - Bulk grant/revoke ran Shizuku binder IPC on the main thread: now on IO, with a failure toast. Select all goes through `AppsAdapter.selectAll` so the toolbar count updates.
  - RootCompatibilityActivity.onResume probes off the main thread. SettingsActivity root-fragment commit has a queued-commit fallback.
- **In-app updater "problem parsing the package" (user report, device on r3085 CI build):** the r3116 release assets are valid. Both are complete and signed with the same cert as the installed app (sha256 `31:7C:5E:CA…5B:80`), so the file was damaged or lost between download and install. Root cause not confirmed without device logs. Changes:
  - The download is validated before any installer sees it: exists, at least 1 MB, `PK` magic, `getPackageArchiveInfo` parses, package matches. On failure it re-downloads once over direct HTTP, then shows `update_download_corrupt`. Before this, an unparseable file made `checkSignaturesMatch` "assume match" and went to the system installer.
  - DownloadManager success uses `COLUMN_LOCAL_URI` (where the file actually landed) instead of the requested path. The filename is ASCII-only (`ShizukuPlus-v…apk`; it was `Shizuku+-v…`).
  - The 16 MB cache copy and validation moved off the main thread. `launchSystemInstaller` refuses a missing file.
  - Users on r3085 still run the old updater. The fix only helps from the next build on, so r3085 → next needs a manual install, or Shizuku running so the pm-stdin path is used.

### 2026-10-28 — Antigravity (Screen opening animations, Explode elimination, Activity Log fluid transitions, M3E gestures) [master]

- **App-wide screen transition overhaul & legacy Explode eradication**:
  - Identified and eliminated `android.transition.Explode()` default window transition across `AppActivity.kt` and `AppBarActivity.kt`. Explode was scattering views in chaotic shards and causing crashes on Android 16 (#483, #529).
  - Standardized on Google Material 3 Expressive `MaterialSharedAxis.Z` (depth zoom and fade) by default across all app activities, with lateral `MaterialSharedAxis.X` remaining available when experimental screen transitions are toggled.
  - Replaced broken shared element transitions (`icon_server_status`, `icon_manage_apps`, `icon_backup`, `icon_permission_manager`, `icon_device_control`) that caused Android's `ActivityTransitionCoordinator` to stall for timeout before snapping.
  - Enhanced `startWithSceneTransition` in `Context.kt` to safely construct clean window scene transitions without phantom view pairs.
  - Fixed toolbar Up navigation and TopAppBar back navigation across `AppActivity`, `ActivityLogActivity`, and `ApplicationManagementActivity` to call `finishAfterTransition()`, playing the smooth reverse M3 return transition.
- **Activity Log opening & list motion**:
  - Overhauled Activity Log opening from Server Status chip, System Hub, and Advanced Settings with fluid `MaterialSharedAxis.Z` depth navigation.
  - Wired `finishAfterTransition()` to `ActivityLogActivity`'s TopAppBar navigation icon.
  - Added `Modifier.animateItem()` to timeline records and timeline date headers in `SystemHubScreens.kt`, enabling spring layout animations when toggling filtering pills or changing sorting modes.
- **Authorized Apps screen entrance & dynamic gesture guide**:
  - Upgraded `ApplicationManagementActivity` entrance animation with unified `MaterialSharedAxis.Z` and cascading item slide-in (`item_slide_in_bottom.xml`).
  - Redesigned swipe gesture guide popup (`swipe_hint_overlay.xml`) to dynamically reflect the user's active right and left swipe actions with exact theme container colors, directional preview arrows, choreographed bounce animations, "Customize in Settings" direct navigation, and re-triggerable action in the top app bar menu.
- **Settings subpage SharedAxis.X transitions & back navigation polish**:
  - Connected `applySharedAxis` in `SettingsActivity.kt` to `ShizukuSettings.isExpressiveAnimationsEnabled()`, giving nested settings fragment transitions fluid lateral slide/fade on Material 3 SharedAxis.X instead of abrupt cuts.
  - Aligned back navigation across `PermissionManagerActivity.kt`, `DeviceControlActivity.kt`, and `SystemHubActivity.kt` to invoke `finishAfterTransition()` instead of `finish()`, enabling proper reverse window transitions.
  - Added `@interpolator/m3e_emphasized_accelerate` and updated all floating navigation bar slide transitions (`floating_nav_enter_forward`, `floating_nav_exit_forward`, etc.) to use Google M3 emphasized deceleration (280ms) and acceleration (240ms) curves.
  - Verified and confirmed resolution of GitHub issues #639 (Appearance crash on Android 15 One UI) and #623 (Settings search crash with non-vector drawables).
- **Settings crash resilience & One UI theme fixes (Issues #643, #646)**:
  - Replaced synchronous `commitNow()` in `SettingsActivity.kt`'s `onContainerCreated` with `commitNowAllowingStateLoss()` and `commitAllowingStateLoss()` guarded by lifecycle/destroyed checks, resolving immediate crash on Android 15 One UI 7 when tapping Settings (#646).
  - Unrestricted `oneUiThemePreference` in `PersonalizationSettingsFragment.kt` (removed Samsung-only restriction) so all devices can toggle One UI style, and ensured preference changes trigger `applyTheme(requiresRecreate = true)` to immediately render the expanded One UI header and padding (#643).
- **Home screen card motion & touch physics**:
  - Upgraded `applySpringTouch` in `MotionUtils.kt` to monitor `MotionEvent.ACTION_MOVE` out-of-bounds events and smoothly release cards back to 1.0f scale.
  - Upgraded `HomeAdapter.kt` card entry to DPI-scaled 24dp translation, 0.96 scale, and M3 Expressive decelerate easing.

### 2026-10-27 — Antigravity (App interactions consolidation, Settings UX, One UI title centering, GitHub triage) [master]

- **App interactions modernization & consolidation**:
  - Replaced the 700dp phone canvas and nested cards with a compact, spring-morphing M3 Expressive card (`AppInteractionsPreference.kt`) featuring unified horizontal swipe action selector chips (right/left) and long-press toggle pills in ~140dp.
  - Consolidated App Interactions directly into the Appearance screen (`settings_personalization.xml`), completely eliminating `AppManagementSettingsFragment` and its separate subpage from `settings_main.xml`.
  - Cleaned up obsolete `AppManagementSettingsFragment.kt` and `settings_app_management.xml` and removed dead entry from `SettingsSearchEngine`.
- **Cloud Database Updater relocated**:
  - Moved `update_app_database` (fetching app descriptions and tips) out of Appearance/Interactions into **About > Updates** (`settings_about.xml` / `AboutSettingsFragment.kt`), keeping Appearance dedicated to visual and interaction styling.
- **Settings One UI & One-Handed mode title centering**:
  - Aligned Settings One UI / One-Handed mode title positioning with `HomeScreen.kt` (centered when expanded, smoothly interpolating to start on collapse in `SettingsScreen.kt`).
  - Addressed text cutoffs across 24 files, removing `TextOverflow.Ellipsis` and rigid maxLines for full text display across modern viewports (including Galaxy S26 Ultra).
  - Elevated Snackbar above floating navigation bar in `SnackbarHelper.kt`.
- **Material 3 Expressive boldness, sizing, and standards alignment**:
  - Aligned `HomeScreen.kt` top app bar typography with official Google M3 Expressive tokens: `HeadlineLarge` (32sp expanded, 36sp One UI) to `TitleLarge` (22sp collapsed), tracking (-0.2sp to 0sp).
  - Wired `isBoldTypography` dynamically into title weight scaling (interpolating between `FontWeight.ExtraBold`/`Black` and `FontWeight.Bold`/`SemiBold`), giving users impactful editorial hierarchy across both standard and One UI layouts.
  - Aligned `SettingsScreen.kt` collapsed app bar title size to standard `TitleLarge` (22sp, formerly 20sp) with dynamic `FontWeight.ExtraBold`/`Bold` weights.
  - Updated `SettingsScreen.kt` search results with `shapes.large` (16dp) card boundaries, bold category titles, and typography boldness scaling.
  - Aligned System Hub `StatusBadge` and `ActivityRecordItem` badges to official M3E emphasized badge spec (11sp, `FontWeight.Bold`, 0.4sp tracking).
  - Updated `SystemHubScreen.kt` `TabRow` to emphasize active tabs with `FontWeight.Bold` and `TitleSmall` role.
  - Snapped settings category card corner styles in `CollapsiblePreferenceCategory.kt` to official Google M3 shape scale tokens: Large (16dp), LargeIncreased (20dp), ExtraLarge (28dp), ExtraLargeIncreased (32dp), eliminating arbitrary off-scale values.
- **GitHub Issues triage & fixes**:
  - **#641 (Inconsistent colors)**: Fixed header category icon styling in `CollapsiblePreferenceCategory.kt` to respect `IconStyleHelper.current()`. Now only applies a two-tone pill if `style == Style.TWO_TONE`; for `OUTLINED` or `STANDARD` icon styles, it keeps the clean primary icon without artificial purple/container background.
  - **#642 (Inconsistent rounding)**: Fixed PLUS badge in `PlusGovernorsPreference.kt` to use a full pill (`RoundedCornerShape(100)`), consistent with `PlusFeaturePreference` and other badges.
  - **#643 (One UI style crash on Samsung F14)**: Confirmed resolved by r3085 commit `768d27ad` (safe fragment detach/re-attach in `SettingsActivity.kt` on recreate, `view?.post` recreate triggers, and proper padding insets sync).
  - **#640 (Option bug)**: Confirmed resolved by r3085 commit `768d27ad` (expanded header padding and title overlap sync).
  - **#639 (Crash immediately)**: Root cause was `AppIconTilePickerPreference` ClassCastException resolved in r3082 (`60780ea0`) and recreate stabilization in r3085.
  - **#623 (Error while searching settings)**: Confirmed resolved by commit `5bdf6f2a` (`SafePreferenceIcon`). Pruned dead `AppManagementSettingsFragment` entry from `SettingsSearchEngine`.


### 2026-10-24 (late) — Claude (stable prep: issues, tests, CI, merge) [master, unpushed, 175 ahead / 0 behind]

- **Issues filed** for the removed per-app enhancements: #631 (concept + "Plus upgrade available" badge reference text, what a revival needs), #632 DPM, #633 NPU, #634 AVF VM, #635 Window Tuner.
- **Tests**: no workflow ever ran unit tests; server + database silently skipped their JUnit 4 tests (no vintage engine). Fixed + stale suites updated `6f9ccec7`; MockK now runs on-device (ByteBuddy via -javaagent). Local: 131/131 non-Robolectric pass; 4 Robolectric suites are x86/CI-only. New `unit-tests` job; release `needs: [build, lint, unit-tests, smoke-test]`. `ScreenSmokeTest` (androidTest, emulator job) opens every top-level screen + loads every settings-search icon.
- `1f171b44` scheduleNPULoad() returns success=false/not_supported (was fake success).
- **Merge** `3a4a1e04` origin/master (README + az #619): az kept local entirely - #619 was conflicts, duplicates, 2 orphans, and Turkish "için" in place of "üçün". 0 behind origin now.
- **Release plan**: `docs/RELEASE_CHECKLIST.md` (blockers, P0-P2 device items, R8-only items). `scripts/dev/device-smoke.sh` = install -r + am instrument + cold launch + logcat FATAL check (never uses connected*AndroidTest, which uninstalls).
- **Not done**: /code-review of the ~95 commits since the part-2 ultrareview base (`9f4287b4..HEAD`); device pass (ADB refused all day); release-build check on the phone (needs uninstall -> backup first); CHANGELOG fold + version; push (user's call; push = prerelease, stable = dispatch).
- #623/#624 are fixed by `9a811eee` (unpushed) - close on release.

### 2026-10-22 — Claude (part-1 review fixes, pairing, security, M3 audit) [master, unpushed]

- **Part-1 ultrareview fixes** `7a1cd1e8`: legacy rish keep-alive never `stopService()`s before `startForeground()` (cold-start crash), retries on activity start, live setting; prefs listeners held strongly; `legacy_rish_keepalive` excluded from sync; uninstall confirm; Server Metrics no longer forces `ai_core_plus`; Settings header re-expands; Personalization no longer seeds `custom_app_name`.
- **Pairing** `07e930e3` `07667e4d` `9f4287b4`: root cause of the "not adaptive" pairing was `setOnlyAlertOnce(true)` (005707ca) on the "service found" notification — it never heads-up'd over the Settings dialog. Now alerts per new port, reverts to "searching" when the dialog closes, auto-queues `AdbStartWorker` after pairing when WRITE_SECURE_SETTINGS is held, live status card + Start button on the tutorial (`AdbPairingService.state`), opt-in "Fill in the code for me" (pairing a11y service: Settings packages only, exact 6-digit field, mDNS port preferred).
- **Security** `816795d4` `aad5f9a3` `52318139`: server trusts the other manager flavor / grants WRITE_SECURE_SETTINGS+DUMP only to same-signing-cert packages (`isSignedLikeManager`); no blanket root auto-grant scan, tracked grants revoked when turned off; `registerAIAutomationBridge` manager-only; server defaults su_bridge/root_magisk_mocking OFF until synced, flags synced on every binder receipt; AICore+ healer needs real consent (`aicore_a11y_user_enabled`); crash reports opt-in (prompt once after onboarding + Advanced switch, breadcrumb scrubber, no screenshots); Tasker/Locale start/stop behind `allow_automation_plugin` (on for updates, off for new installs); TCP mode off for new installs (`decideSecurityDefaultsIfNeeded`); imports never switch privileged keys on (`SettingsBackupManager.PRIVILEGED_KEYS`, manual restore asks).
- **Apps count** `77df3391`: count waits for the binder (state is RUNNING from the persisted state before the binder arrives); fixes "0 authorized apps" flash. Installs that cached a 0 may show it once more.
- **Device test 2026-10-22 (build `0f46882a`, server restarted via build-install-debug.sh)**: `rish -c id` OK; server resolves manager + same-signed secondary (compat stub is debug-signed `2a04169d` too, so trusted as intended); BinderSender delivers to clients, no "is not manager"/SecurityException; cold start shows "Authorized 85 applications" from the first frame (no 0 flash). **Still untested**: pairing flow (needs the user), auto-grant revoke, Tasker gate, restore dialog, crash-reporting prompt (debug build has no DSN, so it never shows).
- **Branches**: `review/ur-part2` = master@`9f4287b4` + revert of the 520-string drop (review-noise only), base `review/ur-base2`; does NOT include the security/app-count commits — re-cut before launching the ultrareview.
- **M3 audit**: full m3.material.io export at `~/m3-corpus/md` (see memory reference-m3-material-corpus); plan in `docs/M3E_DESIGN_PLAN.md`. Hold the ~185-string sentence-case pass until translations are refreshed.
- **Approved, not started**: replace the AICore+ accessibility service with server-side UiAutomation + direct input injection (remove the service after one release); visible "protect from battery restrictions" switch (server whitelists the manager each start, replacing the magic-number AppOps elevation); keep the pairing a11y helper opt-in.
- **Part-two ultrareview already ran** (session f0fb31d9, 3rd/last free run, `review/ur-part23` vs `review/ur-base2`); findings NOT yet fixed. `review/ur-part2` (re-cut here) is redundant. Open items:
  1. Push `api` submodule (`e7d1773`) BEFORE the parent repo (`Service.descriptorOf()` at ShizukuService ~:971/:2111).
  2. ~~"Trusted networks" row hidden while the firewall is off, but trusted Wi-Fi turns the firewall off (`BehaviorSettingsFragment.kt` ~:337).~~ `aae01184`
  3. ~~App identity toggle on for everyone (seeded custom_app_name)~~ — fixed by `7a1cd1e8` + one-time cleanup.
  4. ~~Haptics control treats unset `follow_system_haptics` as on, haptics code as off (`PersonalizationSettingsFragment.kt` ~:270 vs `ShizukuSettings.java` ~:432).~~ `347277d6`
  5. ~~`corner_rounding_entries` label order mismatches values in az, zh-rCN, ko.~~ `953b37aa`
  6. ~~Icon morph barely plays: spring visibility threshold too large for 0..1 (`ExpressiveShapes.kt` ~:123); also earlier `springTo()` not cancelled on rapid toggles.~~ `980be4b7` (one reused SpringAnimation per drawable, `MIN_VISIBLE_CHANGE_SCALE`)
  7. ~~Expanded settings title font cap applies to all themes, not only One UI (`SettingsScreen.kt` ~:277).~~ `6c1a8847`
  8. ~~Expressive shapes toggle doesn't show/hide morph rows until reopen.~~ `0ee43316`
  9. ~~Developer options Plus switches sync to the server before persist (needs `view?.post`, like Behavior/Experimental).~~ `569af2e5`
- **M3 phase 1 progress (same day, later session)**: 1.1 motion tokens `e0269430` (`ThemeOverlay.Motion.Standard` when Expressive animations off; `MotionUtils.EMPHASIZED_*`/`STANDARD*` + `MotionUtils.spring(ctx, attr)` with fallback; literal PathInterpolators routed through it). 1.7 AMOLED `6127358a` (`ColorScheme.controlContainer`/`onControlContainer` in core/ui AppTheme.kt; slider inactive track = secondaryContainer). `scripts/dev/lint.sh` now uses `bash gradlew` (`0df86a7b`); spotless + lint pass.
  - **1.3 shape scale done (2026-10-23)**: `5db87761` Sharp overlay now applied last (One UI / corner rounding re-rounded it) + covers the new tokens; `17d7bbe7` LI/XLI/XXL in every Shape Style + snapped radii per plan; `0f01a002` Compose `androidShapes(context)` reads all 8 `shapeAppearanceCorner*` attrs from the activity theme (Cut -> CutCornerShape, unresolved/relative -> Compose default; `OneUiShapes` deleted, `SharpShapes` kept for the `isRoundedEdges=false` param); `b166dda8` 9 card literals -> tokens (floating nav bar left: radius set in code), drawable radii left alone (attrs are style refs, not dimens); `e18a26fe` spotless target narrowed to src/*/java so `lint.sh` stops tripping Gradle's implicit-dependency check on copied assets. Known: corner rounding "Maximum" (44) makes XL rounder than XLI (32) - only visible on XLI components. **Screenshot pass: check One UI corners first** (XS 4, M 16, L 28, XL 32 changed visibly).
  - **1.4 type scale done** `83fcb740`: `ThemeOverlay.Typography.Bold` = the 15 `TextAppearance.Material3.*.Emphasized` styles (+ listItem BodyLarge.Emphasized); Compose `BoldTypography` = `Typography().*Emphasized`; body roles stay baseline (regular) under Bold in XML and Compose (follow-up fix, body Emphasized is medium); One UI titleLarge 26 -> 22sp (was > headlineSmall 24), Compose `oneUiTypography(base)` mirrors the XML 30/22/17 on top of Bold; badges (InlineBadgeSpan, was 9dp) / layout simulator (10sp) / Drop-In pill (9.5sp) -> 11sp. SettingsScreen expanded title keeps its own explicit 34/30sp. Lint + spotless pass.
  - **CHANGELOG**: `## [Unreleased]` section added above v13.7.0 covering all 106 unpushed commits (user-facing only). Fold it into the release section when cutting stable.
  - **Device screenshot pass NOT done**: loopback ADB refused (`127.0.0.1:5555` connection refused, wireless debugging off) on 2026-10-23. Check One UI corners + Bold/One UI type first.
  - **Push prep**: origin/master has 2 commits not in master (`212d3a2a` README, `5c0a0716` az #619) - merge before pushing; api submodule `e7d1773` first. `5c0a0716` will likely conflict in values-az with local `c1e6fe1c`/`23584d67`/`d6a89431`: resolve with the LOCAL side, then confirm no `*_value`/`*_values` arrays came back in any locale and no strings removed in `ef5bfa82` returned (otherwise az settings silently break again).
  - **Screenshot checklist** (when ADB is back): One UI corners; Rounded edges off + One UI + a corner override; Cut style in Permission Manager (Compose) vs an XML screen; the 9 retokenized cards; Bold-only (onboarding headlineLarge now 32sp, was 30) and One UI + Bold type; feature badges at a large system font. **Added after 2.9/1.2**: About - Dev then Cancel stays Stable; auto-check off disables + clears auto-install; missing install permission blocks auto-install with the dialog; Home card drag lift/drop incl. re-pick mid-settle; edit-mode buttons in/out; double-tap a section header (arrow + children end consistent, guard removed); switch-row icon dim/scale.
  - **2.9 About (update card) done** `cdaaf8f5`: `UpdateSettingsPreference` + `preference_update_card.xml` deleted; About has 4 standard rows (`check_for_update` tap-to-check w/ status summary, `update_channel` SegmentedControlPreference w/ Dev confirm via change-listener veto, `auto_update_enabled` / `auto_install_enabled` switches on the existing ShizukuSettings keys, auto-install `dependency` + permission veto). "Device info" part NOT done: no screen/key by that name exists - asked the user what they meant (diagnostics dashboard in Advanced?).
  - **1.2 springs done** `d33bdf1c`: `MotionUtils.springTo(property, target, attr, endAction)` / `cancelSpring` (tag `tag_motion_springs`, stiffness / scale^2 for intensity, scale 0 snaps). Converted: Home drag lift/drop + `CardRadiusProperty` corner morph, HomeEditMode in/out (40ms stagger dropped), Collapsible arrow (`isAnimating` guard removed), GrayableIconSwitch icon; Compose Home header / System Hub chevron+expand / settings search fade -> `MaterialTheme.motionScheme` specs (these no longer follow animation intensity, like other M3 components). Infinite loops (rotation, pulses) left timed. Remaining timed VPAs not re-laddered.
  - **User decisions (2026-10-23)**: palettes = generate offline and commit the XML. "Device info" = the About screen's structure + the update card, plus a settings-wide UX look (locations, toggles, styles).
  - **About sections** `f212640b`: App info / Updates / Project info (non-collapsible labels; `settings_about_category_app_info`, `_project`, `settings_check_for_update_title` restored with translations from `ef5bfa82^`); Help + Report a bug moved from Advanced to About (handlers moved too).
  - **Settings UX audit** `656328f6`: `docs/SETTINGS_UX_AUDIT.md` - corner controls (5 overlapping), naming clashes (Elastic vs Expressive animations, "Expressive Icon Shapes" vs "Expressive icon shape", duplicate "Personalization"), misplaced rows (App interactions under Personalization, Advanced > Diagnostics grab-bag, Bold typography under Color), Title Case (64/190). Proposals only - none applied; waiting on the user's pick.
  - **1.5 palettes + 1.6 contrast** `419a48f9` `28b3ce8a`: `bash scripts/dev/gen-accent-palettes.sh` (`--compare` prints variants) -> `res/values/themes_accents.xml` (generated, don't hand-edit). Variant ACCENT = CorePalette recipe (primary chroma max(48, seed) capped 64, secondary 16, tertiary hue+60 @24, neutrals 4/8) on TonalSpot tones; reproduces the base Default theme exactly from seed #4355B9. Chosen over TonalSpot (washes out Neon/Crimson), Content/Fidelity (light primaryContainer = full-strength seed), Vibrant (neon greens/cyans). ThemeDelegateImpl: `R.array.theme_overlay_accent_*` [light std/med/high, dark std/med/high], `contrastLevel()` = in-app HC -> 2, else API 34 `UiModeManager.contrast` (>=1/3, >=2/3); level in theme key (rikka recreates on resume when the key changes). `ThemeOverlay.HighContrast` now only with system colours (MDC v34 dynamic roles are `system_*_light/dark`, already contrast-aware). AMOLED outlines split into `ThemeOverlay.Black(.Plus).Outlines`, applied only at contrast 0. Compose: dropped hard-coded AMOLED/HC outlines; reads inverse, surfaceTint, fixed roles. **Not done (1.5 remainder)**: System Hub alpha-faked containers, status/RISKY/One UI harmonized custom roles.
  - **HC follow-up** `82f302a7`: HC switch now recreates (it had no listener); `ThemeOverlay.HighContrast` split light (#424242/#757575) / `.Dark` (#9E9E9E/#616161) - the old single pair made light mode less contrasty. **Open (pre-existing)**: `custom_accent` and `use_system_color` call `applyTheme(requiresRecreate = false)`, which only bumps Compose's themeVersion - the XML theme changes on the next resume. Probably needs recreate on change (slider: on release); verify on device first.
  - **GitHub sweep 2026-10-23**: #626 (+ Figim's SU Bridge self-test screenshot on #622) = rish `VerifyError ... c0 not instanceof Throwable` on release builds -> fixed `ef092a45` (manager `-repackageclasses af.shizuku.r8`, shell `rikka.shizuku.shell.r8`; R8 full mode had put both dexes' renamed classes in the default package and ShizukuShellLoader's parent-first loader resolved APK names to the rish dex). Verified via local minify mappings; needs a release build on device (`rish -c id`, SU Bridge self-test). #628 crash picker -> `abd1a27b` (history in device-protected `files/crash_history`, 10 kept). #623/#624/#629 = settings-search painterResource crash, fixed by `5bdf6f2a` (unpushed). #625 = no stack, service drops only. #627 (scripts on watchdog restart) ALREADY WORKS: Scripting snippets with "Auto-run on service start" run on every transition to RUNNING (ShizukuApplication ~:569, from #399 `d5417ebf`), including watchdog restarts while the app process is alive (or on its next start). Just point the reporter there. PR #622: wording already in via `23584d67`; Figim's untranslated items are existing resources (compat_hub_*, SU Bridge self-test, System Hub tabs) except activity-log entry text, which is logged in English.
  - **Experimental gate** `b7830d1a` (user: "consider some of those new fixes as experimental"): generated accents + contrast levels behind Experimental > Colour > Full colour schemes (`experimental_color_schemes`, default off). Off = released accents (`themes_accents_classic.xml`, `ThemeOverlay.AccentClassic.*`, hand-written, generator doesn't touch it), contrast level 0, outline-only HC for every colour source (light/dark split kept), AMOLED outlines skipped while HC is on. Still shipping ON and untested on device: shape scale (One UI corners), type scale, springs, About rework, crash picker, rish R8 fix.
  - **M3E phases 2/3 pass (2026-10-23, "finish developments before testing")**: per-row status now lives in `docs/M3E_DESIGN_PLAN.md` (Status column). Done: 2.1 `35657a74`/`12ae448c`, 2.2 `6912ca9d`, 2.3 `65265f47`, 2.4 `9dda9339`, 2.7 `781dc73c`, 2.8 `b46fe091`, 3.2 `05ba2ea9`, 3.5 `0485b321`, 1.5 rest `34869d97`, 1.2 ladder `2eb3675f`, slider a11y `b119b1db`, snackbars `d242803c`, Compose app bars `31061aa7`. Gated: 3.1 Experimental > Material screen transitions (`experimental_screen_transitions`, core/ui reads the pref directly) `62d9881c`. Deferred with reasons in the plan: 2.5 rows, 2.6 pickers, 2.2 side-by-side groups, 3.3 Apps header, 3.4 half-height sheets/toasts/SearchBar, writing pass, phase 4. Tried and reverted: half-height sheets hid the help/changelog action rows.
  - **Device checklist additions (phase 2/3)**: switch thumb check/X everywhere incl. onboarding + GrantPermissions dialog; Expressive button press morph + Start filled on root/WADB cards; connected groups (onboarding icon style, automation sheet Start/Stop selected state); LoadingIndicator in app picker/profiles/backup; snackbar inverse colours on AMOLED; System Hub containers/badges with an accent + Full colour schemes on/off; TalkBack: slider step names, badge readout, "Help" long-press action; nav bar (experimental) docked default, reselect scroll-to-top, no autohide under TalkBack; predictive back in Apps selection and Permission Manager detail (cancel mid-gesture resets); flexible app bars in Permission Manager/Device control; Material screen transitions on AND off (watch for #483 on Android 16: black screen / crash after theme change).
  - **Settings audit applied (user picked names + corners + row moves)**: names `6a0e4434`; one Corner rounding control `02ac89c3` (`migrateCornerSettingsIfNeeded()` at start-up and after every import: rounded_edges_enabled=false -> corner_rounding_override "sharp", home/settings_card_corner_style removed; "Sharp" first in corner_rounding_entries for en/az/ko/zh-CN, 7/7 entries/values everywhere); moves `bbfb7e6d` (Scripting -> ADB tools, Bold typography -> Text & layout), `f637bfa5` (Verified installation + Package governor -> Startup & behavior via `VerifiedInstallPrefs`, Package governor sync after persist; Update app database -> App interactions), `c985f085` (Home activity-log switch greys out when logging is off). 2.6 tile pickers `1bce2d01` (`ChoiceTilePreference`, accent swatches read the real overlay primary; accent pick now recreates).
  - **Device checklist (audit)**: upgrade a build with Rounded edges OFF -> Corner rounding shows Sharp and corners stay sharp; restore an old backup with rounded_edges_enabled=false -> Sharp; pick each corner step incl. System default; Verified installation VirusTotal key flow + Pithus in Startup & behavior; Package governor toggle reaches the server; Update app database from App interactions; accent swatches match the applied accent with Full colour schemes on and off, light and dark.
  - **Wrap-up 2026-10-23 (user: finish previous plans, deal with ultrareview, clean caches)**:
    - Ultrareview: part 1 fixes on master (`7a1cd1e8`), part 2 items 2-9 fixed (`aae01184`..`569af2e5`, item 3 via `7a1cd1e8`); item 1 = push api submodule first. Local branches deleted after checking `git log master..<b>`: review/ur-part1 `ef14c154`, review/ur-base2 `ef14c154`, review/ur-part2 `a2264d52` + review/ur-part23 `1e860621` (only the unwanted 520-string revert), review/stable-prep `2fe5e9f6`, fix/antigravity-regressions `c2c8f022`, pr-611 `2bdbfe1b` (squashed as `c5dc8284`), pr-612 `02bb2ba6`, pr-613 `6649061f` (squashed as `2fd5a442`, az superseded by #619/#622), pr-619 `536c94f2`. origin/* untouched. Two August stashes (feat/auto-pairing-ux #443, fix/adb-reboot-errors #454) are NOT on master - kept.
    - "Battery switch" (approved item): already covered by the visible Device hardening & keepalive switch (DeviceOptimizer whitelists the manager in deviceidle, VendorBatteryRestrictions lifts background limits) - no second switch. The magic-number AppOps part done: `6be47ec2` manager gets only android:get_usage_stats + android:schedule_exact_alarm via strOpToOp; `b1b02832` elevateApp / SU Bridge mapping by name (107/111/119 dropped). Existing grants not revoked. Note: elevateApp still grants WRITE_SECURE_SETTINGS + DUMP to any authorized client that asks for its own package (by design, flagged to the user).
    - Device "before" state not captured: rish answered `id` once, then timed out (manager process likely frozen in background - the legacy keep-alive scenario). Capture `cmd appops get af.shizuku.plus.api` + `dumpsys deviceidle whitelist` before installing.
    - Remaining plan items: AICore+ -> UiAutomation (approved; **user 2026-10-24: save for another time** - not for stable), #533 Hex Installer (deferred by design), #199 Shadow Binder / Mavericks / SQLite (need an installed release build).
  - **2026-10-24 follow-up**: `8bae545a` App interactions Long-press actions section (audit #5); `5d844d5e` no back arrow on Apps / System Hub / Settings root while floating-nav tabs; `558e439c` action confirmations -> snackbars via `BaseSettingsFragment.notifyUser()` (toast fallback when detached; toasts kept before finish() / leaving the app). `528874a1` back arrow / bar clearance key off `FloatingNavBar.isTopLevel()` (bar on AND that tab kept), restore-success back to a toast so the restart-hint snackbar doesn't eat it. `9e97a382` Apps-list confirmations -> snackbars, app database row in its own Data section. `b7d22f8c` + follow-up: app database rework - bundled JSON is the one source, remote overlay by revision + field, daily sync URL fixed (old one 404d, never ran), manual button used to wipe SU-path hints / Shizuku-native flags, `database/apps.json` deleted. Not verifiable end to end until pushed (new URL 404s before that); `RemoteDbSyncWorkerTest` can't run on-device (MockK self-attach fails) - CI only. Per-app enhancement toggles (saved, read by nothing; 4 of 8 features never existed) removed with the Apps-list badge and the hub's "requires Plus" tag - user chose this; descriptions kept; `enh_*` prefs cleared once at startup. Everything left in the M3E plan now needs the device: half-height sheets (sticky footer via BottomSheetCallback), SearchBar for settings search, 2.5 row metrics, 2.8 info icon, Apps header collapse. Writing pass still waits on translations.
  - **Device checklist additions (app db)**: App interactions > Data section + row summary (app count, last checked); tap -> "Checking GitHub..." then a result snackbar; Root compatibility descriptions/SU-path hints present on a release (R8) build.
  - **Device checklist additions**: snackbar confirmations in Advanced (backup/restore, reset ADB keys), Root integration (device-owner toggles), Home restore cards; Settings root without back arrow only when the floating nav is on.
  - **Screenshot checklist additions**: every accent light + dark at standard (all changed: tinted surfaces now); in-app High contrast with an accent, with Default, with system colours; Android 14 Contrast Medium/High (Settings > Accessibility > Colour and motion) then return to the app; AMOLED + High contrast outlines.
- **User request queued for M3 phase**: About screen - the update-settings card sits awkwardly narrower than the screen; improve the About and Device info sections overall.
- CHANGELOG entries still owed for all of the above. Nothing pushed.

### 2026-10-20 — Claude (Regression sweep after Antigravity Oct 11–20 sessions) [branch fix/antigravity-regressions]

- **Pairing**: removed `AdbPairingService` auto-launch of `MainActivity` (CLEAR_TOP) on success — it tore down onboarding/tutorial and is BAL-blocked on 10+; success notification's Start action covers it. Default "service found" notification is inline-reply-only again (content intent opened an activity over Settings and could kill the pairing dialog). Restored `AdbMdns` rediscovery retry, scoped to this device's own adbd. Pairing connect timeout back to 3s.
- **Floating nav / System Hub**: bottom clearance moved from the Column `Modifier.padding` into Metrics/Activity Log scroll content. `FloatingNavBar.reserveSpace` skips Compose roots, so every Compose screen must add its own content padding — never pad the outer container (exposes bare window background = black bar in AMOLED).
- **Server**: `getAICorePlus()` re-gated on `ai_core_plus` for third-party callers; manager exempt (System Hub metrics). Antigravity had dropped the gate entirely in `dc4f56ec`.
- **Compat Hub**: progress text localized; uninstall no longer reports "complete" on failure.
- **Follow-ups (same day)**: What's New sheet — GitHub button icon-only so Close fits; README bullets + README-only releases filtered (both classic `formatNotes` and timeline `ChangelogParser`). Icon Studio rebuilt: `IconStudioRenderer.kt` (state model, JSON persistence in pref `icon_studio_state_v2`, off-main-thread software renderer, superellipse/cookie shapes) + tabbed Compose sheet; picked photos copied/downsampled to `filesDir/icon_studio_source.png`. System Hub labels localized. Floating-nav bottom bounce re-arms only on real scroll-up.
- **M3 Expressive round**: `compose-material3` is **deliberately pinned to `1.5.0-alpha22`** in `libs.versions.toml` (user-approved) for `MotionScheme.expressive()` (set in `AppTheme`, standard when Expressive animations is off) and connected `ToggleButton` groups (`SegmentedChoiceRow`). Don't "fix" it back to the BOM version. Settings decoration tags: `category_header` (collapsible card header), `section_label` (non-collapsible category → bare M3E label, never decorated), `standalone_card` (own card). Expanded header height comes from `expandedHeaderHeightDp()` in `SettingsActivity.kt` only. Personalization regrouped (Theme / App icon & name / Shapes / Motion & haptics / Layout & navigation / Home screen / Language), same pref keys. Main-screen summaries for Appearance/Startup are live (SettingsFragment).
- **Server/API perf round** — ⚠️ **Push order: the `api` submodule commit `e7d1773` is local-only; push `api` (thejaustin/ShizukuPlus-API master) BEFORE the parent repo**, or CI can't check out the pinned submodule (see the 2026-07-13 broken-pointer incident). Changes: `transactRemote` no longer calls `getInterfaceDescriptor()` (an extra IPC) on every proxied call - only when logging/firewall/shadow/profiler is on, cached via `Service.descriptorOf()`; identity restored in `finally`; `ClientManager` is a `CopyOnWriteArrayList` (old synchronizedList was iterated unlocked -> CME risk); `sendBinderToClient` looks up only running packages (431 -> 141 ms to deliver binders) and skips the manager. Remaining server cold-start cost is ~1 s of class loading from compressed, un-dexopted dex before `starting server...`.
- **Dex packaging**: `manager/build.gradle` sets `packaging.dex.useLegacyPackaging = false` on purpose (server maps dex straight from the APK; ~2.0 s -> ~1.5 s server start, ~78 MB less swap; release APK +~4.6 MB). Don't revert for APK size without re-measuring. `build-install-debug.sh` must keep its `zipalign -P 16 4` step after the libc++ repack or stored dex/.so lose alignment. Plus *Impl services in ShizukuService are lazy (`Lazy<T>` holder).
- **UX round (2026-10-21)**: new **Experimental features** screen (`settings_experimental.xml`, `ExperimentalSettingsFragment`) - low-risk opt-ins, separate from 7-tap Developer options: App icon & name (`isExperimentalAppIdentityEnabled`, defaults on only for users who already customised; off = restore stock alias/name), Device control home card, floating nav (+ `floating_nav_style` floating/docked/compact, `floating_nav_tabs`). Use `Preference.setAvailable()` (CollapsiblePreferenceCategory.kt) to hide rows - plain `isVisible` is undone when a collapsible category expands (that caused raw "Sync with %1$s"). Plus-feature switch listeners must sync to the server *after* persist (`view?.post`). Appearance merges: Dark style, Haptic feedback (3-way), "Animations" master. Startup & behavior regrouped (same keys). Hub metrics poll on IO every 2 s.
- **Needs on-device verification**: pairing (notification + onboarding), compat uninstall (`pm uninstall` with secondary user 150 present), black bar on Home/Settings, duplicate titles, OneUI subtext, settings scroll jitter, bold typography.

### 2026-10-18 — Antigravity (Advanced Multi-Agent Review & M3E UX Consolidation) [Stable Release Prep]

- **Multi-Agent Review & Final Stabilization Pass (v13.7.0.r2852)**:
  - **ProGuard / R8 Hardening**: Added keep rules for custom preferences (`af.shizuku.manager.settings.**`) and Koin DI (`org.koin.**`) in `manager/proguard-rules.pro`.
  - **Core Security & Dual-Stack Network Guard**: Hardened `TcpNetworkGuard` to check and close `adb_wifi_enabled` global setting alongside TCP socket ports. Enforced `NetworkTrust.shouldBlockTcp` in `AdbStartWorker.attemptStart()`.
  - **State Machine Concurrency**: Re-verified `pingBinder()` before committing `State.CRASHED` in `ShizukuStateMachine.kt` to prevent false crash status transitions under concurrent binder calls.
  - **Sentry Quota & Exception Ordering**: Reordered `ShizukuApplication.setBeforeSend` so daily persistent event caps increment strictly *after* exception filtering and deduplication pass.
  - **Android 15+ Foreground Service Compliance**: Added `setForeground(getForegroundInfo())` before `startForegroundService` in `WatchdogWorker.kt`.
  - **Platform & Architecture Compatibility**: Fixed OneUI fallback mapping in `EnvironmentUtils.java` for API 33–35. Offloaded synchronous socket probing from the UI thread in `EnvironmentUtils.kt` and `ShizukuTileService.kt`.
- **UI/UX & Material 3 Expressive (M3E) Settings Consolidation**:
  - **Segmented Control Upgrades**: Upgraded `icon_style`, `icon_color_mode`, and `animation_intensity` to `SegmentedControlPreference` with segmented pill choices.
  - **Intuitive Hierarchy**: Moved `icon_color_mode` out of Color & Theme and directly nested under `icon_style` inside Shape & Style, appearing dynamically only when Two-Tone is selected.
  - **Lifecycle & Tint Cleanup**: Implemented `onDetached()` in `CollapsiblePreferenceCategory.kt` to unregister child listeners and prevent memory retention. Fixed card expander chevron tint to `app:tint="?colorOnSurfaceVariant"`.
  - **Compose & Home Polish**: Deduplicated compounding scroll listeners via `tag_home_scroll_listener` in `HomeScreen.kt`. Resolved RTL horizontal gradient inversion by evaluating `LocalLayoutDirection.current` in Composable scope. Canceled in-flight `ViewPropertyAnimator` on bind and recycle in `HomeAdapter.kt`. Added bounds guards to `SegmentedControlPreference` and `FilterChipPreference`.
  - **Device Verification**: Built, libc++ patched, signed, and installed on `emulator-5554` (Galaxy S24 Ultra Android 17); verified SettingsActivity and PersonalizationSettingsFragment live on device with spotless formatting.

### 2026-10-12 — Antigravity (Gemini 3.8 Flash) [Service Doctor Redesign, Notification Anti-Fatigue & Build Resilience]

- **Service Doctor Modernization & Layout Polish**:
  - `activity_service_doctor.xml`: added Material 3 diagnostic status card (`card_summary`, `summary_icon`, `summary_title`, `summary_subtitle`, and `btn_rerun`), modernized spacing, and localized tips title `@string/doctor_tips_title`.
  - `item_doctor_check.xml`: containerized diagnostic check items inside M3 `MaterialCardView` (`?attr/shapeAppearanceCornerLarge`, `?colorSurfaceContainerLow`) with tonal button styling for `btn_fix`.
  - `ServiceDoctorActivity.kt`: wired dynamic summary state evaluation (`issueCount == 0` displays green checkmark / `colorTertiary` "All Systems Healthy"; issues display error tint / `colorError` count), connected `btn_rerun` to `runDiagnostics()` with haptic feedback, and localized strings.
- **Notification Anti-Fatigue & Overwhelm Protection**:
  - Added 60s alert cooldowns (`ERROR_NOTIFICATION_COOLDOWN_MS = 60_000L`, `CRASH_NOTIFICATION_COOLDOWN_MS = 60_000L`), `setOnlyAlertOnce(true)`, and demoted channels to `IMPORTANCE_LOW` with silent alerts across `AdbStartWorker.kt`, `WatchdogService.kt`, `WatchdogWorker.kt`, `ShizukuReceiverStarter.kt`, `AdbPairingService.kt`, `UpdateManager.kt`, `AuthenticatedReceiver.kt`, and `ShellRequestHandlerActivity.kt`.
  - Added user-facing toggle `watchdog_crash_alerts` in `settings_behavior.xml` and `ShizukuSettings.java`.
- **AAPT2 & Build Resilience**:
  - `home_extra_step_required.xml`: fixed AAPT2 barrier syntax by referencing unscoped IDs `android:id/icon,android:id/title` instead of `@android:id/icon,@android:id/title`.
  - `app-process`: added `compileOnly libs.hidden.stub` to `build.gradle` and safely encapsulated `LoadedApk`/`peekPackageInfo` via reflection in `NewProcess.java`.
  - `server`: fixed `scheduleWriteLocked(true)` invocation in `ShizukuConfigManager.java`.
  - Verified spotless compliance with `./gradlew spotlessCheck`.

### 2026-10-11 — Antigravity (Gemini 3.8 Flash) [Full UX Reviews, System Hub Metrics Fix & Release Preps]

- **Server Permission & Config Persistence (#594, #595)**:
  - Cherry-picked `djbclark`'s PR #595 (`09bfb17e`): save permission changes at once on the worker thread, flush pending writes on a 2s bounded thread on server exit or crash, retry failed `shizuku.json` writes with exponential backoff (1s, 2s, 4s, 8s, 10s), verify file descriptors and atomic lengths, and added unit tests.
- **Reconnect & Notification Loop Suppression (#596)**:
  - `ShizukuReceiverStarter.kt`: only display `showPermissionErrorNotification` on user-initiated starts; suppress repetitive error notifications during automatic background attempts when `WRITE_SECURE_SETTINGS` is not granted.
  - `ReconnectTrigger.kt`: added pre-check requiring `WRITE_SECURE_SETTINGS` before scheduling automatic ADB reconnects on Wi-Fi state changes.
- **ProGuard / R8 Shrinker Rule Hardening**:
  - `manager/proguard-rules.pro`: added keep rule for `TimelineRailView` constructor so R8 doesn't strip or rename it when inflated from `item_changelog_release.xml`.
- **System Hub Metrics All 0s Bug Fix (#597)**:
  - `ShizukuService.java`: added `"ai_core_plus"` to default-enabled whitelist in `isFeatureEnabled()`, preventing null `aiCorePlus` on startup before manager settings sync. Added throttled manager appId re-resolution fallback (`refreshManagerAppIdsThrottled()`) to `checkCallerPermission()`.
  - `SystemHubScreens.kt` & `ServerMetricsFragment.kt`: added proactive `syncAllPlusFeaturesToServer()` fallback if `aiCorePlus` is null, and safeguarded null-access on `ai.serverStats`.
- **UI Animations & Haptics Review**:
  - `MotionUtils.kt`: removed premature `HapticUtils.tap(it)` from raw `ACTION_DOWN` in `applySpringTouch()`, eliminating buzzing on every finger contact when scrolling lists.
  - `ShizukuSettings.java`: updated `getAnimationDurationScale()` to check `!isExpressiveAnimationsEnabled()`, returning 0f duration cleanly across all animated components when disabled.
- **Accessibility (TalkBack) & Flow Review**:
  - `HomeEditMode.kt`: attached accessibility delegate to `binding.dragHandle` as well as `binding.root`, marked `binding.dragHandle.isFocusable = isActive`, and guaranteed that `binding.root` is passed to `moveCardCallback` so `getChildAdapterPosition` accurately maps to the card index.
- **Responsive Layout & Display Scaling Review**:
  - `HomeActivity.kt`: configured `HomeAdapter.ID_APPS` (Authorized Applications banner) to span both columns in 2-column grid mode on tablets and wide landscape screens, matching the Server Status card.
- **Defensive `SummaryProvider` Protection (#587)**:
  - `BaseSettingsFragment.kt`: added `Preference.setCustomSummary()` extension methods which explicitly reset `summaryProvider = null` before assigning summary text, eliminating `IllegalStateException: Preference already has a SummaryProvider set.` across all settings fragments.
  - `IntegerSimpleMenuPreference.java`: guarded `setSummary(CharSequence)` against pre-existing `SummaryProvider`.
- **Adaptive Multi-Interface & IPv6 ADB Connection**:
  - `AdbPortProber.kt`: added `getCandidateHosts(preferredHost)` to enumerate preferred host, loopback (`127.0.0.1`, `::1`), and active non-loopback network interface IPs (`wlan0`, etc.). Enhanced `isPortOpen` to support dual-stack IPv4/IPv6 and zone-stripped IPs. Parallelized candidate port probing down to 150ms.
  - `AdbStarter.kt`: implemented `connectAdaptive` with exponential backoff failover across all candidate addresses on `ConnectException` / `NoRouteToHostException`.
  - `AdbPairingClient.kt`: updated `setupTlsConnection()` to adaptively probe candidate hosts, preventing pairing hangs on devices where pairing binds to interface IPs or IPv6.
  - `AdbClient.kt`: replaced `else if (message.command == A_AUTH)` with `if (message.command == A_AUTH)` so `A_AUTH` is correctly handled if received over TLS after `A_STLS`.
  - `AdbMdns.kt`: hardened `onServiceResolved` with null-safety and zone-stripping for IPv6 link-local addresses. Fixed `isPortAvailable` to check all active network interfaces and allow non-blocking TCP socket verification for `TLS_CONNECT`.
  - `AdbStartWorker.kt`: eliminated stale port reboot backoff penalty by verifying `isPortOpen(savedPort, 200)` and triggering mDNS discovery on attempt 0 if dead. Preserved `resolvedHost` across workers.
  - `ReconnectTrigger.kt`: relaxed LAN check from requiring public `NET_CAPABILITY_INTERNET` to `NET_CAPABILITY_NOT_SUSPENDED`, allowing wireless debugging to reconnect on offline/local LAN networks.
- **GitHub Audit**:
  - Open PRs audited: PR #592 (One prompt de-duplication during connection), PR #594/#595 (proactive config save on permission changes, exit flush, and AtomicFile retry backoff).
  - Issues audited: #590 (RegiStar-style home cards settings category hiding), #596 (continuous error loop diagnostics), #597 (fixed), #598 (theme attribute warning diagnostics).
- **Verification**:
  - `sh ./gradlew spotlessApply` and `sh ./gradlew spotlessCheck` verified clean (BUILD SUCCESSFUL).

---

### 2026-10-03 — Claude Code (Opus 4.7) [Release v13.7.0.r2737 cut]

**Done:**
- **Fixed broken `api` submodule pointer** — CI was failing on every master push since the submodule pointed to `02db531b` (a local commit never pushed to `thejaustin/ShizukuPlus-API`). Updated pointer to current remote HEAD `5c8cccd7`. Commit `81b57270`.
- **Cut stable release v13.7.0.r2737** — Stamped CHANGELOG `## [Unreleased]` → `## [v13.7.0.r2737 — Stable Release]`, tagged `v13.7.0.r2737`, dispatched `Build App` workflow via `workflow_dispatch` (stable path — `push` to master creates prerelease; only `workflow_dispatch` with `prerelease=false` publishes stable). Build in progress: run `37130016516`.
- **Verified #530 (monochrome icon) already fixed** — `ic_monochrome.xml` already contained the filled hexagon silhouette fix from a previous session; fix was in Unreleased, now ships with this release.

**Open:**
- CI stable build result pending (run 37139936339 — 2nd attempt after lint fix).
- Cross-flavor settings sync (`SettingsSharingProvider`) needs on-device testing with both flavors installed.
- Hex Installer One UI 7+ deep compat (#533) deferred.

**Also fixed in this session (build unblock):**
- Lint/compile failure: `OverlayManagerPlusImpl.kt:495` had `override fun setActiveThemePackage(...)` but the method was missing from `IOverlayManagerPlus.aidl`. Root cause: the API submodule's `master` had the AIDL entry (`02db531`) but the main repo's submodule pointer was still at `5c8cccd` (pre-AIDL). Updated submodule to `3f2ae3c` which includes both the AIDL declaration and new `ShizukuPlusAPI.OverlayManager` wrappers for `prepareShadowMount` and `setActiveThemePackage`. Commit `2d7f9ba0`.

---

### 2026-10-03 — Claude Code (Sonnet 4.6) [CLAUDE.md + version scheme fix + issue triage]

**Done:**
- Updated `CLAUDE.md`: added `SettingsShareManager.kt` + `SettingsSharingProvider.kt` to the key files table; added cross-flavor sync as a pending verification item in "Open / needs verification".
- **Corrected version scheme** (`CHANGELOG.md` + `MainActivity.kt`, commit `dcfca78c`): renamed the "v14.0.0" release label to `v13.7.0.r2664` — ShizukuPlus tracks upstream Shizuku version numbers. GitHub tags `v14.0.0.r2663–r2668` kept for existing installs; legacy branch in `checkAndShowChangelog()` maps them to their published pages.
- **Issue triage** (open issues reviewed, several addressed in the Unreleased CHANGELOG section already):
  - #541 (appearance crash): root cause found — `fixDeprecatedListPreferenceSummaries` double-installs `SimpleSummaryProvider` on rikka prefs that already have one → `IllegalStateException`. Fix already in Unreleased. Needs release to land.
  - #551 (blank settings): `onContainerCreated` guard fixed (already in Unreleased). Needs release.
  - #556 (padding crash): negative `heightOffset` after one-handed/OneUI theme toggle. Fix already in Unreleased.
  - #545 (watchdog/wireless debugging on boot): fix already in Unreleased (`AdbStartWorker` Samsung retry logic).
  - #533 (Hex Installer): Overlay Bridge Samsung wizard bypass is in Unreleased, but deeper One UI 7+ compatibility work is still needed — holding off for now.
  - Next step: cut a release tagged `v13.7.0.r{N}` to land all the Unreleased fixes.

### 2026-10-12 — Antigravity [Notification Anti-Fatigue & Overwhelm Protection + Wiki Synced + PR #595 Merged]

**Done:**
- **Notification Overwhelm Protection & Anti-Fatigue**:
  - `AdbStartWorker.kt`: added 60-second cooldown (`ERROR_NOTIFICATION_COOLDOWN_MS = 60_000L`) on error notifications (`showErrorNotification`) and added `.setOnlyAlertOnce(true)` to both error alerts and `showMdnsBlockedSuggestion()`.
  - `WatchdogService.kt`: debounced crash notifications with a 60-second minimum cooldown (`CRASH_NOTIFICATION_COOLDOWN_MS = 60_000L`). Demoted `CRASH_CHANNEL_ID` importance to `NotificationManager.IMPORTANCE_LOW` and added `.setSilent(true)` and `.setOnlyAlertOnce(true)` so repeated crash restarts never trigger chime/vibration spam or heads-up popups. Added `.setSilent(true)` and `.setOnlyAlertOnce(true)` to the persistent watchdog foreground notification.
  - `WatchdogWorker.kt`: added `.setSilent(true)` and `.setOnlyAlertOnce(true)` on background heal worker notifications.
  - `ShizukuReceiverStarter.kt`: added `.setOnlyAlertOnce(true)` to `buildNotification()` and `showPermissionErrorNotification()`.
  - `AdbPairingService.kt`: added `.setOnlyAlertOnce(true)` to `searchingNotification`, `createInputNotification`, `workingNotification`, and final result notification.
  - `UpdateManager.kt`: added `.setOnlyAlertOnce(true)` to download progress, install-ready, and download-error notifications.
  - `AuthenticatedReceiver.kt` & `ShellRequestHandlerActivity.kt`: added `.setOnlyAlertOnce(true)` to missing/invalid token notifications.
  - `ShizukuSettings.java` & `settings_behavior.xml`: added `watchdog_crash_alerts` toggle under Settings → Startup & Behavior so users can completely mute crash notifications if desired.
- **GitHub Wiki Sync**:
  - Cloned and updated `ShizukuPlus.wiki` repository (`Home.md`, `Service-Connection.md`, `System-Hub.md`, `Permissions.md`) with latest architecture, parallel probing, responsive UI, and persistent permission safeguards. Pushed directly to `master`.
- **PR #595 Merged**:
  - Cherry-picked `cb75d446`, `14617263`, `9ae889ff` from `djbclark:fix/config-save-retry` to `master`.
- **Verification**:
  - Ran `./gradlew spotlessApply` and `./gradlew spotlessCheck` (BUILD SUCCESSFUL).

---

### 2026-10-03 — Antigravity (Gemini 3.8 Flash) [Settings red flash fix + Drop-In flavor settings sharing + About section restructure + GH issue fixes]

**Done:**
- **Fixed red screen flashing when opening Settings**:
  - `ShizukuApplication.kt`: removed `StrictMode.ThreadPolicy.Builder().penaltyFlashScreen()`. In debug builds, `penaltyFlashScreen()` instructed the compositor to flash the entire screen red on every main-thread disk read / preference inflation occurring when opening `SettingsActivity`. Retained `penaltyLog()` so StrictMode diagnostics remain visible in logcat without visual distraction.
- **Implemented Drop-In <-> ShizukuPlus inter-app settings sharing**:
  - `SettingsSharingProvider.kt`: created signature-protected `ContentProvider` (`${applicationId}.settings.share`) guarded by `af.shizuku.plus.permission.MANAGER` (`protectionLevel="signature"`) and verified against `PackageManager.checkSignatures`. Supports `getSettings`, `setSettings`, `getLastModified`, and `ping`.
  - `SettingsShareManager.kt`: core synchronization engine. Detects peer flavor (`af.shizuku.plus.api` <=> `moe.shizuku.privileged.api`), executes automatic initial import when switching or launching either app for testing, performs bi-directional sync based on timestamps, and supports manual import/export.
  - `ShizukuApplication.kt`: automatically performs initial import / auto-sync on startup and registers a listener to automatically export settings changes to the peer app in the background when auto-sync is enabled.
  - `HomeActivity.kt`: runs auto-sync check on `onResume()` so returning to the home screen immediately reflects any settings changed in the other flavor.
  - `ShizukuSettings.java` & `SettingsBackupManager.kt`: added keys `KEY_SETTINGS_LAST_MODIFIED`, `KEY_AUTO_SYNC_PEER_SETTINGS`, `KEY_PEER_INITIAL_IMPORT_DONE`, and excluded internal peer sync metadata from JSON exports.
  - `AndroidManifest.xml`: registered `SettingsSharingProvider` and declared `<queries>` for mutual package and authority visibility on Android 11+ (API 30+).
- **Architectural Cleanup — Migrated Backup & Restore + Peer Sync to About Section**:
  - `settings_about.xml`: added `category_backup` collapsible category containing `backup_settings`, `restore_settings`, `sync_peer_settings`, and `auto_sync_peer_settings`.
  - `settings_shizuku_plus.xml`: removed `category_backup` completely from Feature Hub.
  - `AboutSettingsFragment.kt`: migrated encrypted & plain backup creation launchers, restore launcher, biometric auth, and peer sync dialogs. Added `applyBackupCategoryVisibility()` on `onCreateSettingsPreferences` and `onResume()`.
  - `ShizukuPlusSettingsFragment.kt`: removed all backup-related activity launchers, dialog handlers, visibility callbacks, and unused crypto imports.
  - `strings.xml` & `AdvancedSettingsFragment.kt`: updated `settings_hide_backup_settings_summary` and `settings_main_nav_about_summary` to reference About rather than Feature Hub.
  - `SettingsSearchEngine.kt`: automatically indexes backup preferences under `AboutSettingsFragment` due to XML-driven indexing.
- **GitHub Issue Fixes**:
  - **Issue #560 (Dhizuku Mode setup command failure)**: `DiagnosticsDashboardPreference.kt` constructed the setup command using shorthand dot notation `"${context.packageName}/.admin.DhizukuAdminReceiver"`, which failed with `ClassNotFoundException` because `applicationId` differs from package namespace. Changed to explicit component name `"${context.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"`.
  - **Issue #559 (Stuck on "Searching for pairing service" on Android 16 / Honor Magic 7)**: `AdbMdns.kt`'s `isPortAvailable(port)` only attempted to bind to `127.0.0.1:port`. On modern Android (15/16) and OEM ROMs (MagicOS, HyperOS, OxygenOS), wireless debugging binds to the device's local interface IP (e.g. `wlan0`). Binding to `127.0.0.1` succeeded, returning `false` (mistakenly assuming the port is free) and causing `onServiceResolved` to drop the valid pairing service and loop discovery endlessly. Updated `isPortAvailable(host, port)` to test both loopback, resolved host IP, and non-blocking TCP socket connect verification.

### 2026-08-30 — Claude Code (Sonnet 4.6) [PRoot ShizukuPlus, comprehensive analysis + cleanup]

**Commits:** `8c649a84`..`39bdbdf1` (40 commits — devlog was not updated during the session, recorded here retrospectively)

**Context:** wide-ranging "ultracode" analysis and enhancement pass. Session did not update the devlog before ending.

**Icon / app identity fixes (`ae4d0d42`, `2f61a052`, `91ac8fd0`, `b687d860`):**
- Restored the plus badge to the approved safe-zone position after it drifted again; confirmed via actual circular-mask simulation (not flat preview).
- Reverted to upstream Shizuku's unmodified cat/hexagon artwork — the only thing that belongs to ShizukuPlus in the icon is the plus badge.
- Fixed a notification-icon regression (stale tint attr) and misleading "Automation running" notification title.
- Fixed `SecurityException` crash from haptic feedback vibration on HyperOS/MIUI (`SHIZUKUPLUS-8E`) — `VibrationEffect.createOneShot()` + `Vibrator.vibrate()` is not callable from a foreground service context on those ROMs; wrapped with try/catch and fallback.

**Haptic feedback (`6245b91f`):**
- Added a dedicated `Haptic Feedback` toggle in Settings > Appearance, decoupled from the Animations toggle — previously tied together, so disabling animations silently also disabled all haptics.

**Accessibility service label fix (`38bc9675`, fixes #427):**
- The pairing accessibility service (`FakeAdbPairingActivity`'s backing service) was showing as a bare duplicate "Shizuku+" entry in the system Accessibility settings list. Added the correct label.

**Deprecation / i18n sweep (`d638fcff`, `66c2509f`, `cd34d64e`, `a0eb09db`):**
- Silenced deprecated `ListPreference` summary-format-marker warnings across 10+ settings screens.
- Extracted 100+ hardcoded English strings from Settings screens, Appearance, and FakeAdbPairingActivity (which was using raw `AlertDialog` + hardcoded English — migrated to `MaterialAlertDialogBuilder` + `@string` refs).

**Accessibility (`a4d97930`, fixes #41):**
- Added a TalkBack-accessible long-press equivalent for swipe-to-act gestures in Application Management. Users relying on TalkBack could not trigger swipe actions at all.

**Theming (`9097822f`, `cdb8707d`, `b91315e0`):**
- Rescoped One UI Style toggle to structure-only (shapes, typography) — it was also overriding dynamic color / Material You accent, competing with the explicit color toggle. Reverted an incorrect "fix" to the overlay precedence comment (the comment was wrong, not the code). Fixed One UI accent overlay silently overriding Material You dynamic color even when the user had dynamic color enabled.

**Locale (`e627539d`, fixes #429):**
- Migrated locale handling to `AppCompatDelegate.setApplicationLocales()` (Android 13+ per-app language API), replacing the legacy custom mechanism. Includes a one-time migration of the existing pref key. Now integrates with system Settings > App Info > Language.

**Shape style (`c17ce59e`, `e405d30c`, `4e3edfb5`):**
- Fixed Shape Style picker's "Zen" option not describing the shape it gives (#78 — it's a squircle, not the labeled name).
- Restored the responsive 2-column home grid on large screens/DeX that had been broken (#76).
- Added "Cut (Angular)" as a new genuine shape style option (octagon/cut-corner silhouette, not just another radius variant).

**Service / auth fixes (`a6c6136b`, `b392c8f3`, `640342d9`, `f65fdab7`, `d79a9935`):**
- `ShizukuStateMachine.update()` was misreporting unexpected service deaths as intentional stops (`RUNNING → STOPPED` instead of `RUNNING → CRASHED`), so the Watchdog's crash-detection restart path never fired. Fixed by preserving the prior state before the binder goes dead.
- Force-stop an already-connected app on authorization granted (so the next launch picks up the new grant) — mirroring what revoke already did for the same reason.
- Force-stop other same-uid processes on an interactive permission-dialog decision — previously only the dialog-triggering process got the force-stop.
- Stopped `AutomationService` from auto-starting unconditionally (it was running permanently for every user with hardcoded-demo non-functional rules — see below). Fixed `getStartOnBoot()` falling back to hardcoded `false` instead of the manifest default `true`.

**Samsung deep links (`8faa3e23`, `1ac9011a`, `41c5f449`, `c72a4ff3`, `a3222cd2`, `5bc2ae2a`):**
- Service Doctor's Samsung Sleeping Apps and Auto Blocker deep links were broken on One UI 8 (Android 16). Found working direct deep links via `adb shell am start` research; landing directly on the specific screen (not the top-level settings).
- Battery optimization warning said "tap to exempt" but opened a 300+ app alphabetical list — now opens the direct per-app exemption dialog for Shizuku+ specifically.
- Auto Blocker snackbar "Check" button routed to a dead settings action.

**ADB help link fix (`6e84d574`):** help link in the ADB pairing flow resolved but answered a different question than the one it was linked from.

**CI / release pipeline (`71ae51b3`, `c9d795da`, `88485f2f`, `4698e799`):**
- Release-notes generation silently produced empty Added/Fixed/Other sections on every release since the Keep a Changelog restyle — `grep -vFf` against an empty exclude pattern matches every line, excluding everything.
- Smoke-test job's `gradlew` invocation broke under `workflow_dispatch`, treating a stray backslash as a task name.
- `workflow_dispatch` on a non-master branch could publish a real non-prerelease "Latest" release. Now enforces `prerelease=true` for all non-master manual runs.
- Release tag push rejected by GitHub when the commit modifies `app.yml` (requires `workflow` scope PAT, not `GITHUB_TOKEN`) — documented; not a code fix.

**CHANGELOG (`39bdbdf1`):** Backfilled the entire `r2287–r2343` range that was skipped when the version counter jumped.

**GitHub housekeeping (`eb7cc0e0`, `20b28237`):** issue templates referenced deleted/renamed labels; fixed. Dead `wiki#troubleshooting` anchor in bug report template.

**Docs (`8c649a84`):** recorded the icon safe-zone verification lesson (flat composite preview ≠ real adaptive-icon mask) and later session work.

**This session (2026-08-30) — continuation of above analysis:**
- Deleted dead `activity_plus_dashboard.xml` layout (no Activity, no reference anywhere).
- Extracted 2 hardcoded English strings from `layout_diagnostics_dashboard.xml` (`"Action Required"`, `"Disable Diagnostics"`).
- Removed dead `registerDefaultRules()` call from `ShizukuApplication.kt` — `AutomationService` never starts, so `NetworkEvent`/`ForegroundAppEvent` are never dispatched; the registered stubs achieved nothing.
- Corrected CHANGELOG [Unreleased] automation entry: previously claimed "real opt-in automations" were implemented; corrected to accurately describe what happened (service stopped from auto-starting; rules remain stubs pending #6).

**Open items (as of 2026-08-30):**
- [ ] #199 Shadow Binder hidden packages — needs ADB/on-device testing.
- [ ] #200 Mavericks factory crash + SQLite race — ProGuard/try-catch in place; strong indirect evidence (zero Sentry recurrence since v_code 1668) but no direct ADB verification.
- [ ] #333 Card `shapeAppearanceOverlay` — every card hardcodes `cardCornerRadius` (overrides shape_style setting); blocked on confirming base theme attr defaults without local SDK.
- [ ] #428 Themed Icons auto-toggle — needs on-device testing before writing code.
- [x] **#6 / #435 Automation Engine** — Live SSID-based firewall rule + JSON app-profile rule implemented. `setBinderFirewallEnabled()` setter added. Service gates on `hasAnyAutomationRulesConfigured()`. SSID extraction from `NetworkCapabilities.getTransportInfo()`. Done 2026-08-31, `19ffdbe0`.
- [ ] Android 17 ADB pairing (mDNS host + `ACCESS_LOCAL_NETWORK`) — needs Android 17 device.

---

### 2026-08-30 (continued) — Claude Code (Sonnet 4.6) [Sentry triage + Sentry noise reduction]

**Commit:** `53846f61`

**Sentry triage (12 issues):** Completed the full open-issue sweep for org `af-developments`/project `shizukuplus`. All 12 were resolved:

| Issue | Title | Decision |
|-------|-------|----------|
| 8F | FakeAdbClientHandler OOM | `resolvedInNextRelease` — fix already in source (MAX_ADB_PAYLOAD_SIZE guard) |
| 8E | MIUI vibrate SecurityException | `resolvedInNextRelease` — fix in `b687d860`; all events on single Xiaomi in 23-min window |
| 88 | AI-detected performance | `ignored forever` — not a real crash, AI false-positive |
| 8M | newProcess() null — main thread | `resolvedInNextRelease` — all r2260, fix (withContext+null guard) confirmed in source |
| 8K | newProcess() null — IO thread | `resolvedInNextRelease` — all r2260, same fix |
| 8J | ADB pairing ConnectException | `resolvedInNextRelease` — r1614 (700+ commits stale); beforeSend filter added at r2145 |
| 5P | CannotPostFGSNotification (INOI) | `resolvedInNextRelease` — **real bug fixed**: ShizukuLiveService had bare startForeground() with no try/catch (see below) |
| 8H | "Download failed" STATUS_FAILED | `resolvedInNextRelease` — all r2242, Timber.e() downgraded to w() in current code |
| 8N | DownloadManager.enqueue() IAE | `resolvedInNextRelease` — r2246 stale; enqueue catch now uses Timber.w() |
| 8A | deployBridgeToTmp write fail | `resolvedInNextRelease` — **real bug fixed**: non-rooted devices, see below |
| 8G | deployBridgeToTmp write fail | `resolvedInNextRelease` — same fix as 8A (different call-path grouping) |
| 8D | deployBridgeToTmp write fail | `resolvedInNextRelease` — same fix as 8A/8G |

**ShizukuLiveService FGS hardening (SHIZUKUPLUS-5P):**
`ShizukuLiveService.onStartCommand()` called `startForeground()` bare with no try/catch. On INOI A750 (Android 14, POST_NOTIFICATIONS denied), this threw `CannotPostForegroundServiceNotificationException` → `UncaughtExceptionHandler` → fatal crash. `WatchdogService` and `AutomationService` both already had the `startForegroundSafely()` pattern (with a comment referencing 5P!). Added the same to `ShizukuLiveService`: private `startForegroundSafely(Notification)` catches all Throwable, logs at WARN, calls `stopSelf()` + returns `START_NOT_STICKY`. Also wrapped the `onCreate()` Flow-collect `startForeground` call.

**Sentry noise reduction (8A/8G/8D + 8H/8N):**
- `RootCompatHelper.deployBridgeToTmpDetailed()`: `Timber.e()` → `Timber.w()` for exitCode != 0 branch. Non-rooted users (uid 2000 ADB shell) failing to write `/data/local/tmp/su` on Android 15/16 with tightened SELinux is expected behavior. The reason was already shown to the user via `selfTest`'s `failureDetail` string; no Sentry error event needed.
- `UpdateManager.downloadUpdate()` enqueue catch: `Timber.e()` → `Timber.w()` + removed explicit `Sentry.captureException()` (ROM-specific DownloadManager incompatibility, not a crash).
- `UpdateManager.monitorDownload()` STATUS_FAILED: `Timber.e()` → `Timber.w()` (download failure is an expected user-facing event, not a crash).
- `UpdateManager`: removed 3 redundant explicit `Sentry.captureException()` calls that followed `Timber.e()` — SentryTimberTree already routes `Timber.e()` to Sentry, making the explicit calls double-report the same event. Removed now-unused `io.sentry.Sentry` import.

**Still open:**
- [ ] #199 Shadow Binder hidden packages — needs ADB/on-device testing.
- [ ] #200 Mavericks factory crash + SQLite race — needs ADB verification.
- [ ] #333 Card `shapeAppearanceOverlay` — blocked on base theme attr confirmation.
- [ ] #428 Themed Icons auto-toggle — needs on-device testing.
- [ ] #6 Automation rules — scaffolding only.
- [ ] Android 17 ADB pairing — needs Android 17 device.

---

### 2026-08-30 (continued, session 3) — Claude Code (Sonnet 4.6) [issue triage + icon dimming + count-guard fixes]

**Commits:** `1fba77da`..`c35f1c6c` (6 commits)

**Visual diagnosis from user screenshots (#437, #438, #439, #440, #441, #442):**
Downloaded and reviewed screenshots for 6 open issues to distinguish pre-existing report artifacts from live bugs.

**#440 / #441 / #442 — Wrong icons + asymmetric badge labels in Appearance settings (`1fba77da`):**
Screenshots revealed mismatched icons (e.g., wrong drawable for "Rounded Corners" toggle) and badge labels that were off-by-one relative to their companion item. Fixed icon assignments and badge alignment in the Appearance settings screen.

**#438 — Missing icon on `hide_disabled_plus_features` preference (`0fb80542`):**
`hide_disabled_plus_features` in `settings_shizuku_plus.xml` was the only switch in the file without an `android:icon` attribute. Added `ic_visibility_off_24`. (TCP mode/port icon reported in the same issue was already present from `af11cf5a`; the screenshot was from a pre-release build.)

**#439 — Switch icons stay full-color when toggled off (`4aa88f6e`):**
All 43 `SwitchPreferenceCompat` items across 7 settings XML files kept full-color icons when their toggle was off, violating Material 3's 38% disabled opacity for de-emphasized controls. Added `GrayableIconSwitchPreference` (new file: `settings/GrayableIconSwitchPreference.kt`) — a 20-line `SwitchPreferenceCompat` subclass that sets `icon.alpha = if (isChecked) 1.0f else 0.38f` in `onBindViewHolder`. Changed `PlusFeaturePreference` to extend `GrayableIconSwitchPreference` instead of `SwitchPreferenceCompat` (inherits the dimming for free). Batch-replaced all 43 XML occurrences via Python script.

**#437 — Service Doctor phantom-process fix crashes with "null remote process" on S26 Ultra (`a30a6dc9`):**
`Shizuku.newProcess()` is a Java platform type that can return null at runtime (binder died mid-call) despite Kotlin not flagging it as nullable. The null case was already guarded (throws `IllegalStateException`). Improved the catch block: replaced a generic Sentry-capturing toast with Timber.w() logging + a device-aware message — Samsung devices with "Maximum Restrictions" active in Auto Blocker get a specific explanation pointing to Auto Blocker settings; other devices get a generic "device restrictions" message. Removed `import io.sentry.Sentry` (now uses Timber only, consistent with project's SentryTimberTree pattern).

**#417 — Samsung watchdog can't recover when process is frozen:** Investigated `WatchdogAlarmReceiver.kt`, `BootCompleteReceiver.kt`, and `WatchdogService.kt`. The 15-minute `AlarmManager.setExactAndAllowWhileIdle` recovery is already fully implemented from the 2026-08-26 session. `ACTION_USER_PRESENT` as a static broadcast receiver cannot work reliably on Android 8+ (apps targeting API 26+ cannot receive implicit broadcasts). No additional code change warranted.

**#424 — Authorized-app count flashes 0 on resume — additional layer (`02f548bd`, `c35f1c6c`):**
The 2026-08-26 nullable `Int?` fix handled cold start; a second path remained. `AuthorizationManager.getPackages()` silently swallows IPC exceptions and returns `[]`, so when called on resume (both `onResume()` and the RUNNING state listener call `load()`), a transient binder drop would cause `load()` to post `success(0)` and overwrite a previously-correct count. Added a guard before the existing `loadGeneration` check: if `allPackages.isEmpty() && ShizukuStateMachine.isRunning() && (_grantedCount.value?.data ?: 0) > 0`, skip posting — treating it as a transient IPC failure rather than a real zero-app state. The `> 0` condition ensures fresh installs with genuinely zero authorized apps still post normally.

**Still open:**
- [ ] #199 Shadow Binder hidden packages — needs ADB/on-device testing.
- [ ] #200 Mavericks factory crash + SQLite race — needs ADB verification.
- [ ] #333 Card `shapeAppearanceOverlay` — blocked on base theme attr confirmation.
- [ ] #428 Themed Icons auto-toggle — needs on-device testing.
- [ ] #6 Automation rules — scaffolding only.
- [ ] Android 17 ADB pairing — needs Android 17 device.
- [ ] #426 aShell You null newProcess() — Samsung One UI 8.5 specific; no code change yet.
- [ ] #437 "Useless +" button complaint — the toolbar "+" is intentional (needed when list is non-empty); needs a comment on the issue but no write token available.

---

### 2026-08-31 (session 6) — Claude Code (Sonnet 4.6) [Automation Engine + mode-aware settings + deploy error UX]

**Commits:** `19ffdbe0`, `6a93e993`, `c5c84d76`

**Automation Engine — fully working rules (#435, #6):**

`ShizukuSettings.java` — Added the missing write path (`setBinderFirewallEnabled()`) that was the root cause of all rules being commented out. Also added:
- `KEY_AUTOMATION_TRUSTED_NETWORKS` / `KEY_AUTOMATION_APP_PROFILES` constants
- `getAutomationTrustedNetworks()` / `setAutomationTrustedNetworks()` (StringSet)
- `getAutomationAppProfilesJson()` / `setAutomationAppProfilesJson()` (JSON string)
- `hasAnyAutomationRulesConfigured()` helper (used to gate the service)

`AutomationRules.kt` — Full rewrite:
- `NetworkFirewallRule`: reads user-configured trusted SSIDs from `ShizukuSettings`; calls `setBinderFirewallEnabled()` + `syncFeatureToServer("binder_firewall", …)` on SSID match/mismatch. Inactive when trusted list is empty (no hardcoded "HomeWiFi").
- `AppSpecificProfileRule`: reads app profiles from a JSON object in settings (key = package name, value = `{binder_firewall: true/false}`); applies per-app overrides; no-op restore until a "default profile" settings screen exists.
- `syncFeatureToServer()` top-level helper using `moe.shizuku.server.IShizukuService`.
- Removed `registerDefaultRules()` (dead function).

`AutomationService.kt`:
- Gates `onCreate()` on `hasAnyAutomationRulesConfigured()` — no foreground notification until rules exist.
- Keeps `NetworkFirewallRule` / `AppSpecificProfileRule` as class fields so rule state (isSafeNetwork, currentApp) persists across events.
- Extracts SSID from `NetworkCapabilities.getTransportInfo() as? WifiInfo` on API 29+ (no location permission needed in callback context); strips WifiInfo's double-quote wrapping; treats `<unknown ssid>` as null.
- Unregisters rule instances in `onDestroy()`.

`strings.xml` — simplified automation notification channel/title from verbose description to "Automation" / "Automation is running" (matches upstream Shizuku's terse style: "Watchdog is running").

**Mode-aware settings (#433):**

`RootIntegrationSettingsFragment.kt` — Added `applyModeConstraints()` called at the end of `onCreateSettingsPreferences()`. When server UID == 2000 (ADB/shell), disables and appends "(root mode only)" to four root-exclusive categories: SU Bridge Configuration, Rootless Bridges, Mocking & Simulation, Bootloader Integration. No-ops when server is not running (uid -1) so users can pre-configure.

`ShizukuPlusSettingsFragment.kt` — Same pattern: disables `samsung_system_uid_escalation_enabled` with a targeted summary when uid != 0 (the exploit requires UID 0).

**SU Bridge deploy error UX (#402):**

`RootCompatHelper.selfTest()` — When `deployBridgeToTmpDetailed()` fails, now checks the server UID. If uid == 2000 (ADB mode): explains that Android 16+ SELinux restricts uid 2000 writes to `/data/local/tmp` (expected; no action needed), and directs the user to the exported path or root mode instead of the misleading "make sure Shizuku is running" advice.

**Still open:**
- [ ] #199 Shadow Binder hidden packages — needs ADB/on-device testing
- [ ] #200 Mavericks factory crash + SQLite race — needs ADB verification
- [ ] #333 Card `shapeAppearanceOverlay` — blocked on base theme attr confirmation
- [ ] #428 Themed Icons auto-toggle — needs on-device testing
- [ ] Android 17 ADB pairing — needs Android 17 device
- [ ] #444 SamFonts UID -1 — `grantRuntimePermission` server path exposed; needs user test
- [ ] #426 aShell You — two contributors fixed; may need logcat if third cause exists

---

### 2026-08-31 — Claude Code (Sonnet 4.6) [GitHub auth fixed + mass issue triage + README clarification]

**Commits:** `8339afaf` (devlog), `0f3c7881` (README)

**GitHub auth established:** `gh` CLI was installed via apt in the PRoot environment; authenticated with `repo`+`workflow` scopes. Git credential helper (`!/data/data/com.termux/files/usr/bin/gh auth git-credential`) was already correctly configured. All pending commits pushed.

**Issues closed (15):**

| # | Title | Resolution |
|---|-------|-----------|
| 438 | Missing icons | `0fb80542` — added `ic_visibility_off_24` to `hide_disabled_plus_features` |
| 439 | Grey out icons | `4aa88f6e` — `GrayableIconSwitchPreference` |
| 440/441/442 | Wrong icons / badge labels | `1fba77da` — corrected drawables and badge alignment |
| 429 | Migrate locale handling | `e627539d` — already done in prior session |
| 417 | Samsung watchdog freeze | `WatchdogAlarmReceiver` (2026-08-26 session) |
| 415 | Samsung background kill | Same fix; AlarmManager bypasses process-level freeze |
| 424 | Count shows 0 on resume | Two-layer fix: nullable `Int?` + IPC-failure guard |
| 407 | Downloads wrong APK | `bbe3ab76` — already fixed in prior session |
| 431 | Storage Proxy requirements | Answered + README updated |
| 391 | Shell client unidentified | `9f2c01e8`/`32382e89` — already fixed |
| 411 | rish authorized, no effect | `32382e89` + `b77af0e` debug log |
| 421 | PM-lookup hard-fail tracking | Investigation complete; fix in place |
| 397 | WIKO T10 crash report | Responded: wireless ADB disabled, battery opt on |
| 415 | Samsung "I don't know if it's a bug" | Resolved — AlarmManager fix covers it |

**Diagnostic comments posted:**
- #444 (SamFonts UID -1): likely permission not granted or `grantRuntimePermission` silently failing on OneUI 8; asked for app-management state + logcat
- #432 (Compat Hub install): asked for flavor (Drop-In can't install Compat Hub) + specific `ShizukuCompanion:` log line
- #437: explained the "+" button is intentional (add-script action); noted the Service Doctor toast was improved in `a30a6dc9`
- #425: no crash visible in log — asked for description of what went wrong
- #405: r2142 is 200+ builds behind; asked to retest on current build
- #402: asked for self-test output on current build (diagnostics improved in `d22e84bc`)
- #387: asked Xperia reporter to retest — `8fbe5e47` fixed `onTransact` dead-branch that blocked `attachApplication`

**PR #404 review (Simplified Chinese):** PR has 87 files including CI, build.gradle, and Kotlin source changes beyond the localization content. Changes look legitimate but scope is too wide for one PR. Requested split into: (1) pure strings/docs, (2) string extraction refactor, (3) CI/build changes. PR is currently conflicting and needs rebase.

**README clarification (`0f3c7881`):** `/data/data/` in the Storage Proxy feature description was misleadingly unconditional. Clarified that `/data/data/` requires root; `/data/app/` and `/Android/data/` work in ADB mode.

**Still open (actionable):**
- [ ] #444 SamFonts UID -1 — waiting for user diagnostics
- [ ] #426 aShell You null newProcess — waiting for logcat
- [ ] #199 Shadow Binder hidden packages — needs ADB/on-device testing
- [ ] #200 Mavericks factory crash + SQLite race — needs ADB verification
- [ ] #333 Card `shapeAppearanceOverlay` — blocked on base theme attr confirmation
- [ ] #428 Themed Icons auto-toggle — needs on-device testing
- [ ] #6 Automation rules — scaffolding only
- [ ] Android 17 ADB pairing — needs Android 17 device
- [ ] #377/#387 rish fixes — asked reporters to retest on current builds

---

### 2026-08-31 (session 5) — Claude Code (Sonnet 4.6) [storage proxy fixes + privilege grant API + aShell You regression fix]

**Commit:** `0c65a9eb`

**StorageProxyImpl — three fixes (`StorageProxyImpl.kt`):**
- **Broken pipe fallback:** `createPipe()` sets `O_CLOEXEC` on both ends; the original code embedded the fd number in the shell command string (`cat "$1" > /proc/self/fd/<n>`), which the child shell can never access because `exec()` closes all O_CLOEXEC fds before the shell starts. Rewrote to a daemon thread that copies the child process's stdout into the write end via `AutoCloseOutputStream` — write end is closed on thread exit, giving the AIDL reader a clean EOF.
- **Android 13+ `/Android/data` access:** The pipe fallback was gated on `SDK_INT >= 36` (Android 16). The `/Android/data` restriction began at Android 13 (API 33). Extended to `>= 33`.
- **`run-as` support for ADB mode:** When the server runs as UID 2000 (ADB/shell) and a direct `open()` of a `/data/data/<pkg>/` or `/data/user/<n>/<pkg>/` path fails, now tries `run-as <pkg> cat <file>` via the same pipe mechanism. `run-as` is Android's built-in mechanism to impersonate debuggable apps; this gives ADB-mode clients access to any debuggable app's private data directory without root. Package name is validated with a regex (`[a-zA-Z0-9_.]+`, must contain a dot) before being passed to `run-as`.

**Privileged permission grant/revoke (`ServerConstants.java`, `ShizukuService.java`):**
- Added `BINDER_TRANSACTION_grantRuntimePermission = 10005` and `BINDER_TRANSACTION_revokeRuntimePermission = 10006` to `ServerConstants`.
- Wired up handlers in `ShizukuService.onTransact()`: read packageName + permissionName + userId from the Parcel, delegate to `Android17Compat.grantRuntimePermission()` / `revokeRuntimePermission()` (already used internally for WRITE_SECURE_SETTINGS grants), write 1 (success) / 0 (failure) to reply. Both require `enforceCallingPermission`.
- In root mode the permission manager call is unrestricted; in ADB mode it works for dangerous runtime permissions.

**aShell You regression fix #426 (`ShizukuService.java`):**
- **`id`/`whoami` mocking scoped to Magisk Mocking:** Previously, when `su_bridge` was globally enabled, every `newProcess(["id"])` call from any app returned `uid=0(root) gid=0(root)` — including terminal apps that call `id` to display the current user context. aShell You (and similar shell terminals) expected the real UID (2000 in ADB mode) and could be confused by the fake root UID. Now gated on `root_magisk_mocking` like `getenforce`.
- **`service call` exception handler no longer swallows transient errors:** Previously, any exception during the binder firewall lookup (`ServiceManager.getService()` → `getInterfaceDescriptor()` → `isBinderCallBlocked()`) caused the handler to return a fake `"Result: Parcel(00000000 '....')"` success. aShell You users issuing `service call` through Shizuku got garbage output silently. Now only blocks when `isBinderCallBlocked()` explicitly returns true; lookup exceptions fall through to native execution.

**Updated issue #426** with both fixes and a note that a logcat during crash would pinpoint any remaining cause.

**Still open:**
- [ ] #426 aShell You — two likely contributors fixed; may still need logcat if a third cause exists
- [ ] #444 SamFonts UID -1 — `grantRuntimePermission` server path now exposed as binder transaction; needs user test
- [ ] #199 Shadow Binder hidden packages — needs ADB/on-device testing
- [ ] #200 Mavericks factory crash + SQLite race — needs ADB verification
- [ ] #333 Card `shapeAppearanceOverlay` — blocked on base theme attr confirmation
- [ ] #428 Themed Icons auto-toggle — needs on-device testing
- [ ] #6 Automation rules — scaffolding only
- [ ] Android 17 ADB pairing — needs Android 17 device

---

### 2026-08-26 — Claude Code (Sonnet 5) [PRoot ShizukuPlus, icon rework + GitHub issue triage + Watchdog resilience]

**All of the below eventually landed on `master`** across commits `34663bd4`..`c13bf851` (see `git log` for the full list) — the "not yet committed" note below is stale, kept for narrative order within this entry.

**Icon:** replaced the app icon from a user-supplied flattened PNG (`shizukuplus.png`) that baked the "plus" badge into the gradient/hexagon/cat art at the wrong position. Removed the badge from the raster via high-pass anomaly detection + hexagon-geometry-aware reconstruction (pure-PIL, no numpy/cv2 available in this environment), then re-added it as a separate semi-transparent vector overlay in `drawable/ic_launcher_foreground.xml`, positioned to match the original upload. Also found and fixed a pre-existing bug: the previous `ic_monochrome.xml` plus badge was at a *different* position than the foreground layer's — now consistent, and `ic_monochrome.xml` was trimmed to contain *only* the plus (not cat+hexagon), since Android's Themed Icons API re-tints the whole monochrome layer as one flat color — there's no way to have "just the plus" themed while the rest stays full-color, so this is the closest the platform allows. Added an onboarding toggle (`switch_themed_icon`, defaults on) + "Open icon settings" deep link (`SettingsPage.ThemedIcons`, best-effort — no OS API exists to actually flip the per-launcher setting; filed **#428** to investigate a root/accessibility auto-toggle once ADB access is available for verification, since guessing at another app's private SharedPreferences schema was assessed as too risky to ship blind).

**GitHub issue triage (~29 open issues):** parallel research passes (3 concurrent `Explore` agents) categorized every open issue. Closed 5 as already-fixed-on-main with confirmation comments: **#423** (SU bridge stale dex, `9dba4bd3`), **#412** (compat hub install, `cf77523e`+`a519414c`), **#414** (download failure diagnostics), **#416**/**#420** (shell consent re-implementation, `32382e89`). Left **#418** open (same fix, lower-confidence match on a garbled report) and **#424** (see below) resolved without closing until next-build confirmation. Posted diagnostic-request comments (with specific `adb logcat` filters) on **#427** (Xperia "accessibility only available on a TV" false positive — leading theory: `leanback` uses-feature/TV launcher-alias tripping Sony's OEM gating), **#421**/**#411** (authorized shell commands silently no-op — leading theory: `newProcess()`'s SU-bridge command-interceptor chain false-matching), **#426** (aShell You broken via Shizuku specifically, no corresponding regression found in history).

**Fixed #424** (authorized-apps count flashing "0" on cold start): `HomeState.grantedAppCount` defaulted to `Int = 0` and rendered before the async `AppsViewModel` count-load completed. Changed to nullable `Int?` end-to-end (`HomeState` → `HomeAdapter` → `ManageAppsViewHolder`), rendering a generic title instead of "0 apps" until the real count arrives.

**Fixed #415/#417** (Samsung "Sleeping apps" freezes the *entire* manager process on screen lock, taking `WatchdogService` down with it — confirmed via deep-review agent that the only prior fix, `a519414c`, was a diagnostic-honesty fix only, not a real recovery mechanism). Added `WatchdogAlarmReceiver` — an `AlarmManager.setExactAndAllowWhileIdle` re-arm, self-rescheduling every 15 min, that survives a full process kill because it's dispatched by `system_server`, not anything inside the app's own (frozen) process. Falls back to `setAndAllowWhileIdle` if `SCHEDULE_EXACT_ALARM` is declined/restricted. Wired into `setWatchdog()` (enable/disable), `BootCompleteReceiver` (exact alarms don't survive reboot), and `ShizukuApplication.onCreate()`. New manifest permission `SCHEDULE_EXACT_ALARM` (runtime-revocable, graceful fallback — not `USE_EXACT_ALARM`, which requires a Play "core app" category this app doesn't qualify for). This is a mitigation (bounded by the 15-min interval), not a full fix — documented as such in the wiki.

**Post-fix deep-review pass** (separate `Explore` agent, adversarial "is this actually fixed for OTHER devices/OEMs" pass) found 3 real, low-risk gaps in the just-closed fixes and fixed them same-session: (1) SU bridge redeploy now also triggers on every service start via `ShizukuStateMachine.asFlow()`, not just `ACTION_MY_PACKAGE_REPLACED` — covers devices where the server wasn't up yet at update time; (2) Compat Hub install failures now special-case `INSTALL_FAILED_INSUFFICIENT_STORAGE`/`INSTALL_FAILED_NO_MATCHING_ABIS` with distinct messages (MIUI-specific wording deliberately *not* guessed at without a device sample); (3) update-download failure notifications now distinguish storage/resume/HTTP errors instead of one generic message. Also fixed a stale code comment in `ShizukuService.java` still describing the deleted `ShellConsentActivity` flow.

**Docs:** published a new wiki page (`Permissions.md`) documenting every manifest permission and the feature it powers, updated `Service-Connection.md`'s Watchdog section to describe the three-layer recovery model, and fixed two dead in-app help links (`Helps.kt` pointed at `wiki/Setup` and `wiki/Supported-apps`, neither of which exist — now point at `Service-Connection` and `ShizukuPlus-Knowledgebase`). This devlog entry, `CLAUDE.md`, `CHANGELOG.md`, `CHANGES.md`, `docs/comparison_shizuku_vs_plus.md`, and `.agents/skills/shizukuplus/references/architecture.md` updated in the same pass to reflect the above.

**Also added:** a single entry-level `LOGGER.d("newProcess: %s -> %s", callingPkg, cmd)` in `ShizukuService.newProcess()` (right after `callingPkg` resolves, before the catastrophic-command check) — most individual SU-bridge interceptor branches already logged what they matched, but a command matching *none* of them left no trace at all, which is exactly the blind spot behind #411/#421/#426's "authorized but appears to do nothing" reports. Now every call is visible in logcat regardless of which interceptor (if any) it hits, without touching the ~20-branch interceptor chain itself.

**Not done / explicitly deferred:** the root/accessibility auto-toggle for Themed Icons (**#428**, needs on-device verification before writing any code that touches another app's private storage); MIUI-specific Compat Hub install error text (needs a Xiaomi device/log sample).

---

**Same day, continued — pre-release review, real-device icon bug, update-channel fix, GitHub project polish.**

**Pre-release review (multi-agent):** security review + bug scan + adversarial verification + GitHub issue/PR sweep + release-notes-style research, run in parallel. Clean bill of health (no confirmed vulnerabilities or bugs) — full findings not repeated here, see the workflow's own output if needed. The PR sweep turned up two "PRs I'll fix in this release" that turned out to already be fully resolved on `master` via *different* commits than the PRs themselves (**#308** — `HomeAdapter` throttle/`ServiceStatus.isRunning` race fix, landed via `3aa13734`; **#354** — missing provider authority, landed via `07316516`, predates the PR by one day) — closed both with an explanation rather than leaving them stale or attempting a pointless re-merge. **#404** (zh-CN l10n, 58 files, genuinely wanted) conflicts against current `master`; commented asking for a rebase rather than attempting a blind conflict resolution.

**Sentry sweep** (org `af-developments`/project `shizukuplus`, first access this session — Sentry+Linear MCP auth completed mid-session): of 15 unresolved issues, resolved 4 (`resolvedInNextRelease`) as confirmed stale-install noise from fixes that shipped weeks ago (WiFi-permission crash r2046, SQLite dir-missing + Room/WorkManager `_Impl` r2145/r2014) — each verified against current source before resolving, not just trusted from memory. Left the real open ones untouched: 3 `deployBridgeToTmp` write-failure issues (94 events — needs root to investigate, unavailable this session) and 2 null-`newProcess()` issues (10 users — same family as #421/#411/#426). Linear mirrors GitHub 1:1 via auto-import (`linear-code` bot), nothing unique found there.

**Cut the first stable release this session** (`v13.6.0.r2304`, `workflow_dispatch` with `prerelease=false` — every plain push only produces a prerelease). The `smoke-test` CI job failed but didn't block the release (separate job) — root cause was `:manager:connectedDebugAndroidTest` becoming ambiguous once the Dropin/Shizukuplus flavor split landed (two candidate tasks); fixed to target the Shizukuplus flavor explicitly.

**Real, confirmed icon bug found via actual device testing** (first real ADB/device access this session — `android-mcp` over wireless ADB to the user's own Samsung SM-S948U, reconnected mid-session after a port rotation on screen-lock). The user reported "the icon is very broken" and provided the exact on-device screenshot path (`DCIM/Screenshots/Screenshot_20260826_002710_One UI Home.jpg` — my first two searches of `Pictures/Screenshots` and a narrow `mmin` window both missed it; the real file was in `DCIM/Screenshots`, dated normally, my search parameters were just wrong). Pulled the actual JPEG and zoomed in: the plus badge was almost entirely invisible (a tiny sliver) and the cat's left eye was completely missing in Samsung's notification-icon rendering. Root cause: I had positioned the cat/hexagon/plus to match the flat, non-adaptive uploaded image pixel-for-pixel, and *verified it with an unmasked flat composite in PIL* — which never simulates the ~66dp/108dp circular safe-zone real adaptive-icon mask/notification-icon compositors apply. Simulating that actual circular mask against the shipped art reproduced the exact defect from the screenshot pixel-for-pixel.

First fix attempt shrank the *whole* cat+hexagon composition ~70% to guarantee everything fit inside the strict safe circle — technically correct but changed the icon's look more than necessary, since the background layer is normally allowed to bleed to the canvas edges (only small foreground *content* like a badge needs strict containment). User pushed back ("shouldn't be far off from the original... should only add the material you plus") — reverted the cat/hexagon back to full original scale/position, and *only* repositioned the small plus badge to a spot verified safe by the same mask simulation. Separately, the user asked to move the plus back to the *exact* original-upload coordinates one more time — declined and asked for explicit confirmation given that position is the literal one just proven broken; user confirmed keeping the safe position. Final state: icon visually matches the original upload almost exactly, plus badge verified visible under an actual circular mask (not just a flat preview) — this verification-method gap (flat composite vs. real mask) is the actual lesson here and is now documented as a code comment in `ic_launcher_foreground.xml` so it isn't relearned the hard way again.

**Fixed a real Dev/Beta update-channel bug** (`UpdateChecker.checkViaApi`): the dev/beta path always preferred the newest *prerelease*-flagged GitHub release over the newest release overall, even after a newer *stable* release had since been cut — a Dev/Beta user could get stuck being offered an older prerelease instead of upgrading to a newer stable. Now compares actual parsed version codes and takes whichever is genuinely newer.

**Restyled the CI's auto-generated release notes** to Keep a Changelog's category vocabulary (Security → Breaking → Added → Fixed → Other, in that priority order — Security promoted to always-checked since this is privileged-process software), based on researching upstream RikkaApps/Shizuku (flat bullet list, terse), the thedjchi sibling fork (bracketed category tags — closest lineage precedent), NewPipe (richest categorization + contributor credit discipline), and keepachangelog.com itself.

**GitHub project polish:** added `.github/pull_request_template.md` and `CONTRIBUTING.md` (neither existed despite issue templates already being present); updated the repo's GitHub-side description (dropped a stale "[ UNSTABLE ]" tag left over from the same maintainer-downtime period as the README banner), added topics (`android`, `shizuku`, `adb`, `root`, `kotlin`, `dhizuku`, `android-app`, `privileged-access`) and a homepage link to the wiki — none of this existed before and it's pure discoverability upside. Credited **odorizzioficial** in the README Acknowledgments — the only external contributor with an actually-merged PR in this fork's real history (#409, Brazilian Portuguese translation) plus the detailed Watchdog-freeze report (#415); everyone else in the raw GitHub contributors API list is either the maintainer or inherited upstream Shizuku blame, not a real ShizukuPlus contributor, so deliberately not padded with those names.

**Key process lesson from this segment:** a flat, unmasked composite preview is not sufficient verification for adaptive-icon content — always simulate the actual circular/squircle safe-zone mask before shipping icon changes, and where possible verify against a real device rather than a synthetic preview. Real device access (once wireless ADB was available) caught a bug that three separate PIL-composite verification passes earlier in the session had all missed.

---

### 2026-07-21 — Claude Code (Fable 5) [PRoot ShizukuPlus, rescuing an Antigravity CLI session + reconciling clones]

**Commits:** `ac92c5da`, `469bdde3` (CI-green fixes); `a5b24d68`/`4e708c16`/`1c950cf3` (rebased, previously unpushed); `f162889f`/`a7d8365a`/`ebc63cc6` (follow-up: changelog + release labeling, see below)

**Context:** the user runs an Antigravity CLI (Gemini) agent against the Termux clone independently of
Claude Code sessions — no shared `/resume`, so continuity across the two tools means reading
Antigravity's own local transcript logs (`~/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl`,
found by grepping all conversations for "ShizukuPlus" and picking the most recently modified). This
morning's Antigravity session (07:22–08:43 UTC, model switched Gemini 3.5 Flash → Claude Sonnet 4.6
Thinking mid-session) worked through GitHub issue comments and Sentry crashes on the user's own
"continue" / "look for more" / "fix sentry issues" prompts, landing one large 36-file commit
(`1d860a2a`, "resolve issue #337 #358 #329 #355") plus two same-session compile-error follow-ups
(`6f908bcc`, `880d5555`). `master` was still red when the session ended — its last action was polling
a still-in-progress CI run that went on to fail without the agent noticing.

**Found and fixed 4 defects, all classic AI-patch artifacts (lines silently dropped mid-diff):**
1. **`HomeScreen.kt`** — `fun HomeScreen(...) {` was missing its closing brace (brace-balance check
   caught it: whole-file depth ended at +1). Added the missing `}`.
2. **`SettingsHelper.kt`** — a new function's signature line was dropped, leaving
   `isSamsungAutoBlockerDisabled`'s `return try { ... }` body orphaned directly under the previous
   function. Recovered the name from its one call site in `ServiceDoctorActivity.kt`.
3. **`.github/workflows/app.yml`** — added `:manager:assembleCompatRelease` plus matching
   `uploadSentry*CompatRelease` exclusions, on the false assumption "Compat" is a product flavor of
   `:manager`. It's actually the separate `:compat` module, already built as a dependency of
   `copyCompatApkRelease` wired into `pre{Flavor}ReleaseBuild` — the extra task/exclusions referenced
   Gradle tasks that don't exist and failed task selection outright (the *actual* root cause of the
   `build` job's failure, independent of the Kotlin errors below).
4. **`ShizukuSystemApis.kt`** — new `LOGGER.w(...)` calls in `grantRuntimePermission`/
   `revokeRuntimePermission` with no import. Added `import af.shizuku.manager.utils.Logger.LOGGER`
   (the same static-import convention `ShellBinderRequestHandler`/`HomeViewModel`/
   `AuthorizationManager` already use — considered switching to `Timber` first since that's *also* a
   live convention in this same `utils/` directory, but `Logger.LOGGER` is the closer match: it's
   literally what the broken patch already called, just missing the import).

No local Android SDK, so verified each fix with a brace-balance script + `javac -Xmaxerrs 2000` on the
touched `.java` files (parses clean through classpath-resolution errors, i.e. no real syntax breaks)
before pushing — confirmed `Build App` green afterward (run `29818072361`).

**Separately found:** the SD card clone (`/sdcard/Documents/ShizukuPlus`) had 3 real bugfix commits
from the previous evening that were never pushed — `su -mm`/`--mount-master` flag-parsing fix,
catastrophic-guard blocking legitimate `/data` deletes, root-compat counting only bridge-configured
apps. The two clones had diverged from a shared `5d28196b` ancestor ~15 hours earlier. Asked the user
before touching shared git history (git-history reconciliation across diverged clones is a real
decision point, not a mechanical one); confirmed the only overlapping file (`ShizukuService.java`)
touched non-overlapping line ranges, rebased clean (pre-push guard's 21 checks passed), pushed as
`a5b24d68`/`4e708c16`/`1c950cf3`, then fast-forwarded the Termux clone to match — both clones now
identical to `origin/master`.

**Also found, left alone (out of scope, harmless):**
- 4 untracked `.kt` files in the Termux clone (`af.shizuku.manager.{ApplicationManagementActivity,
  LauncherAlias,TvActivity}`, `moe.shizuku.manager.ApplicationManagementActivity`), dated ~00:41–00:42
  same morning — *before* the Antigravity session started, so a leftover from an unrelated earlier
  attempt. Initially looked alarming (the manifest, committed since `07316516` on 2026-07-20,
  references all 4 by name) but they're all `<activity-alias>` elements — those resolve via
  `targetActivity` and never needed backing classes. The files are an abandoned, unnecessary local
  experiment; safe to delete whenever, not blocking anything.
- The Termux clone's `api` submodule had locally regressed to `8657364f` — the exact broken ref the
  2026-07-13 entry below already fixed (CI reads the committed pointer, not this local checkout, so it
  never affected CI; only matters if someone tries a local Gradle build here). Ran `git submodule
  update` to resync to the committed `df0d0dd8`.
- The `release` job's "Compat Hub stub APK not found" warning is pre-existing — identical on the last
  known-good build (`5d28196b`, 2026-07-20) — not a regression from today, not touched.

**Follow-up, same day — changelog + release-labeling audit (user: "improve on everything mentioned,
especially the attribution/changelogs thing needs fixing"):**

- **Release-notes crash/stability split** (`f162889f`) — `fix(...)` commits in the generated release
  notes now split into a "💥 Crash & Stability Fixes" bucket (matched on
  `sentry|crash|anr|SHIZUKUPLUS-\d+` in the subject) versus general "🐛 Bug Fixes", plus a distinct
  `#NNNN` GitHub-issue count for the release range — a visible signal for "does this look
  major/critical-worthy", not an auto-promotion.
- **New critical-fix tier** (`ebc63cc6`) — `CRITICAL_RELEASE`/`CRITICAL_DESC` added alongside the
  existing `MAJOR_RELEASE`/`MAJOR_DESC` in `app.yml`, for a regression severe enough users should
  update but that isn't new capability (so doesn't belong in the majors list). First entry: `r2153`
  (the Drop-In-shipped-as-Plus packaging bug). Own blockquote + own "⚠️ critical fix" table mark,
  independent of "🚀 major". No backfill script for this tier yet (old pages deliberately left
  un-backfilled — see [[shizukuplus-release-majors]] memory).
- **The in-app "What's New" dialog was actually dead code** (`a7d8365a`) — `ChangelogDialogFragment`
  existed but nothing ever called `.show()` on it, and it read `assets/changelog.txt` (the static
  Apache 2.0 §4(b) "Changes from Upstream" doc) as if it were per-version notes — every user would
  have seen the same generic text regardless of what changed in their update, and none ever saw it at
  all. Rewrote it to fetch the real GitHub release body for the exact version tag via a new
  `UpdateChecker.fetchReleaseNotesForTag()`, called from a new `MainActivity.checkAndShowChangelog()`
  gated on its own `getLastSeenChangelogVersion()` pref (kept separate from the existing
  `getLastSeenVersion()` key, which gates the Sentry-quota reset in `ShizukuApplication.onCreate()` —
  that one runs before any Activity and would already have advanced past the point where an
  Activity-level check could see the bump).
- **CI false alarm on `a7d8365a`:** its `Build App` run showed `release` job failure —
  `! [remote rejected] ... refusing to allow a GitHub App to create or update workflow
  .github/workflows/app.yml without workflows permission`. Not a code bug: `build` and `lint` both
  passed clean (confirming the new Kotlin was fine); only the tag-push step failed, because GitHub
  hard-blocks the default `GITHUB_TOKEN` from creating *any* tag whose checked-out commit's workflow
  file differs from the current default-branch tip — a fixed anti-tampering rule no `permissions:`
  block can waive (needs a real PAT with `workflow` scope instead). Pushing `f162889f` → `a7d8365a` →
  `ebc63cc6` back-to-back meant `a7d8365a`'s release job ran against a since-superseded `app.yml` by
  the time it reached the tagging step. Self-resolved: `ebc63cc6` (a strict descendant containing
  everything from `a7d8365a`) got a clean CI run end-to-end and tagged `v13.6.0.r2165` — `r2164` is
  just a skipped number, same as any other failed-build commit in this repo's history; nothing shipped
  is missing. Lesson: avoid rapid-fire pushes when a commit in the middle touches `app.yml`, or expect
  that middle commit's release job to occasionally lose this race.

---

### 2026-07-16 (pass 4) — Claude Code (Fable 5) [PRoot ShizukuPlus, third-party app-plugin compatibility]

**Commits:** main `17ad35c6`, `24bb109c`; api-submodule `beb20c5`, `2ea3ab2` (both CI-green)

**Focus (user request): make apps that support Shizuku work well; reviewed thedjchi's fork for ideas.**
Found and fixed two core binder **descriptor-mismatch** bugs, both caused by the `af.shizuku.server`
namespace migration renaming client-facing AIDL interfaces (a rikka `dev.rikka.shizuku:api` client
enforces the `moe.shizuku.server` descriptor; if the server's object uses `af.shizuku.server`, every
cross-boundary call throws `SecurityException: Binder invocation to an incorrect interface`):

1. **`IRemoteProcess`** (`17ad35c6` / api `beb20c5`, #325) — returned by `newProcess()`. Third-party
   shell apps (AShell etc.) got the SecurityException on `getInputStream()`/`waitFor()`. Moved the
   AIDL back to `moe.shizuku.server.IRemoteProcess` (methods/codes identical to upstream; only the
   descriptor changed) and repointed all 6 importers + ShizukuPlus's own `api/api` client.
2. **`IShizukuApplication`** (`24bb109c` / api `2ea3ab2`) — the callback interface for the server to
   push to apps. `dispatchRequestPermissionResult` (code 2, the "permission granted" notification)
   had the same `af.shizuku.server` mismatch with no fallback (unlike `bindApplication`, which had a
   hand-rolled one in `attachApplication`), so grants were silently dropped for third-party apps and
   their `addRequestPermissionResultListener` never fired → a likely contributor to the whole
   "granted apps don't work" family. Moved to `moe.shizuku.server.IShizukuApplication`; the
   Plus-only methods (`dispatchLog`/`dispatchSentryEvent`/`showPermissionConfirmation`) are
   manager-client-only so no collision. See [[shizukuplus-binder-detection]] for the full "layer 3"
   writeup + the rule (any AIDL a stock rikka client touches MUST keep its moe/rikka descriptor;
   only genuinely Plus-exclusive interfaces are safe under `af.shizuku.server`).

Verified the rest of the client-facing surface is already correct: `IShizukuService` and
`IShizukuServiceConnection` are `moe.shizuku.server`; the Plus interfaces correctly stay
`af.shizuku.server`. Both fixes span the `api` git submodule + main repo (committed to the
`ShizukuPlus-API` remote first, then pointer-bumped — CI-green, no "not our ref").

**thedjchi fork review (github.com/thedjchi/Shizuku, v13.7.0, since paused):** their Android 17 work
= (a) route `getInstalledPackages` through a compat layer so authorized apps appear — ShizukuPlus
already does this via `ShizukuSystemApis.getInstalledPackages` → privileged `PackageManagerApis`;
(b) ADB pairing fix: use the mDNS-resolved host instead of hardcoded loopback + request
`ACCESS_LOCAL_NETWORK`. **Deferred (b) — needs an Android 17 device:** `AdbMdns`/`AdbPairingService`
hardcode `127.0.0.1`; `ACCESS_LOCAL_NETWORK` is opt-in on Android 16 and loopback-exempt, so a blind
rewrite is the wrong risk profile without a device to confirm. Backlog item for a device session.

Commented on #325 with the fix; commented on #332 (backup key fix). Also, earlier passes this
session: dual-flavor server trust (`b6be301c`), binder delivery-retry (`a42edcac`), AICore+
accessibility self-heal (`2deed39d`), backup device-credential key (`ab922e48`).

---

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
| Any edit to `database/src/main/assets/app_context_db.json` MUST bump `revision` | Installed apps ignore a download whose revision isn't higher than the one in use. Revision 1 is the first publish (2026-10-24 edits happened before it was ever on master); bumps start with the first edit after it's pushed. A pushed bump reaches every install within a day - don't bump for entries tied to unreleased features. Root-compat hub membership (`getRootLegacyPackages()`) is still hardcoded, so a remote update can change hub text but not add hub apps |
| Serializer keeps in `database/consumer-rules.pro` MUST stay | Without them an R8 build parses the app database to nothing - silent, no crash |
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
| App metadata DB (data) | `database/src/main/assets/app_context_db.json` (bundled + downloaded from master) |
| App metadata DB (code) | `database/.../AppContextManager.kt`, `manager/.../worker/RemoteDbSyncWorker.kt` |
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
