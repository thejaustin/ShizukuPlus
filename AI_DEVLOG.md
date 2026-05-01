# Shizuku+ — AI Session Devlog & Planning Space

Living document. Update at the start of every AI session: mark completed items, add new ones.
This is the single source of truth for cross-session continuity — reduces re-explanation,
prevents re-introducing fixed bugs, and keeps the roadmap visible without user steering.

---

## Open Backlog (unfinished / planned)

Items carried forward from previous sessions that have not yet been committed.

### Features

- [x] **Root Compat Hub "Shizuku-aware only" label** — Info banner added to
  `activity_root_compatibility.xml` with string `root_hub_shizuku_aware_note`. Done 2026-04-24.

### CI / Infrastructure

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

- **Context7 integration for dev sessions** — Use the `mcp__claude_ai_Context7__query-docs` tool
  at the start of sessions involving Android API questions (Lifecycle, WindowManager, Mavericks,
  Sentry SDK changes) rather than relying on training data. Particularly useful for Mavericks
  version differences and Sentry 8.x migration quirks.

- **Jetpack Compose re-migration** — Compose was fully migrated then reverted (`25d796d4` revert
  of `749d72a6`) due to instability. If Compose is reconsidered, start with a single isolated
  screen (e.g. Service Doctor) rather than a full home screen migration.

- **Dynamic remote DB versioning** — `AppContextManager` fetches `database/apps.json` but has no
  version/ETag caching. Could add `If-None-Match` header + local cache timestamp to reduce
  redundant fetches.

- **Onboarding step for Root Compat Hub** — Users don't know the Root Hub exists. An optional
  onboarding card after first Shizuku connection could walk through what toggles are available.

---

## Session History (newest first)

### 2026-05-01 — Claude (Sonnet 4.6)
**Commits:** `a1858c0a` (api submodule fix), `71adc664` (enhancements)

**Done:**
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

**Notes:**
- `AdbMdns` was already wired for pairing (TLS_PAIRING) in `AdbPairingService` — no change
  needed there.
- Mavericks ProGuard rules and SQLite try-catch from Gemini session confirmed in code.
  Issue #200 awaits on-device ADB logcat verification.

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
