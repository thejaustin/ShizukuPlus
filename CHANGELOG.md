# Changelog

All notable changes to ShizukuPlus are documented here. See [AI_ATTRIBUTIONS.md](AI_ATTRIBUTIONS.md) for full AI pair-programming provenance and commit mapping.

## [Unreleased]

*Co-developed with Antigravity & Claude Code*

### 🐛 Bug Fixes

#### Server / Service
- **Fixed Shadow Binder `getPackageUid()` returning 0 (root UID) for hidden packages** instead of -1 (Android's "package not found" sentinel) — apps checking whether a hidden package is installed received UID 0 (system/root) and treated it as installed, or had completely wrong assumptions about the owner. ([#444](https://github.com/thejaustin/ShizukuPlus/issues/444))
- **Fixed Shadow Binder IPackageManager intercepts matching unrelated Binder calls** when the `TRANSACTION_*` field initialization via reflection fails (rare on unusual vendor ROMs) — the -1 sentinel value used as "not initialized" could accidentally match valid transaction codes on those devices, intercepting calls from unrelated apps and returning spoofed data.
- **Re-verified the shell/rish consent re-implementation (`32382e89`) across every client type** — rish/shell, root/su, normal app clients, and Dhizuku clients each go through their own already-uid-verified path; no remaining spoofing or persistence gap found. Fixes "Allow always" not persisting ([#420](https://github.com/thejaustin/ShizukuPlus/issues/420)) and the underlying re-implementation request ([#416](https://github.com/thejaustin/ShizukuPlus/issues/416)).
- Fixed a stale code comment in `ShizukuService.checkCallerPermission()` still describing the deleted notification-based shell-consent flow.

#### Manager App (UI)
- **Fixed `Shizuku.newProcess()` returning null on certain chipsets (MediaTek MT6833 and others) causing NPE crashes** in the Fake ADB client handler, ADB proxy service, compat hub installer, and the Shizuku stock-server detection — all callers now handle null explicitly with clear error messages. ([#418](https://github.com/thejaustin/ShizukuPlus/issues/418))
- **Fixed the wireless ADB card missing its icon** — `ic_wadb_24` referenced in the layout but absent from the drawable directory; created the Material Symbols wifi-style vector in the correct 960×960 format. ([#447](https://github.com/thejaustin/ShizukuPlus/issues/447))
- **Fixed 4 home cards with no press animation** — `AutomationViewHolder`, `ShizukuCompanionViewHolder`, `StartStockShizukuViewHolder`, and `AdbPermissionLimitedViewHolder` were missing `applySpringTouch()`; tap feedback was absent for these cards. ([#450](https://github.com/thejaustin/ShizukuPlus/issues/450))
- **Fixed App Backup home card ignoring the icon shape style setting** — its icon always showed the default shape regardless of the user's zen/modern/classic/squircle/cut preference.
- **Fixed the Scripting screen "+" menu item always visible** even when the snippet list was empty — it's now hidden until the first snippet is saved, replaced by the empty state's own action button. ([#437](https://github.com/thejaustin/ShizukuPlus/issues/437))
- **Fixed authorized-apps count briefly showing "0" on cold start** — `HomeState.grantedAppCount` now starts as `null` (not loaded) instead of `0`, so the home screen shows a loading state instead of a wrong count before the real value arrives. ([#424](https://github.com/thejaustin/ShizukuPlus/issues/424))
- **Fixed appearance settings (icon style, shape style, expressive shapes) not visually applying until navigating away and back twice** — home screen cards now rebind synchronously on `onResume()` and also respond to a `SharedPreferences` listener while in the back stack, so changes are reflected immediately when returning from Personalization settings.
- **Fixed Terminal (rish) card hidden when service is not running** — the card was gated on `adbPermission` (service running + full ADB access), making it invisible to new users before they'd ever started the service. It now shows whenever the visibility toggle is on (default), showing a "service not running" disabled state, so users can discover the rish setup option at any time.
- **Fixed edit mode having no explicit exit control** — the home screen "Arrange Cards" mode previously required users to discover the back gesture to exit; a "Done" button now appears in the top bar action area when edit mode is active. Edit mode title updated to "Arrange Cards" with a shorter hint subtitle.
- **Fixed Wireless ADB discovery failing silently without Wi-Fi (5G/cellular data & offline)** —
  - Added `AdbPortProber` to probe local loopback (`127.0.0.1:5555`, cached port) via fast TCP socket connect, enabling instant 1-tap start without Wi-Fi when ADB TCP mode is active.
  - Fixed `EnvironmentUtils.isWifiRequired()` blocking cellular/5G background auto-reconnect (`AdbStartWorker`) even when port 5555 is already listening.
  - Added proactive network diagnostic banner and Mobile Hotspot launcher button to `AdbDialogFragment` and `AdbPairDialogFragment` explaining that Android disables mDNS on cellular and guiding users to enable Mobile Hotspot to use Wireless Debugging over 5G.
  - Added live loopback polling to `AdbDialogFragment` so it automatically connects as soon as port 5555 or hotspot becomes active.
- **Fixed Watchdog unable to recover from a full process freeze** (Samsung One UI "Sleeping apps" and similar OEM freezers kill the entire manager process on screen lock, taking Watchdog down with it) — added an external `AlarmManager`-based re-arm (`WatchdogAlarmReceiver`, every 15 min) that's dispatched by the system rather than anything inside the frozen process, so it can restart the service even after a full process kill. Mitigation, not a complete fix — see the [Watchdog wiki section](https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#watchdog). ([#415](https://github.com/thejaustin/ShizukuPlus/issues/415), [#417](https://github.com/thejaustin/ShizukuPlus/issues/417))
- **SU bridge redeploy now also triggers whenever the privileged service starts**, not only on app self-update — covers devices where the service wasn't running yet at update time (Xiaomi/HyperOS, Samsung One UI). Follow-up to [#423](https://github.com/thejaustin/ShizukuPlus/issues/423) (`9dba4bd3`).
- **Compat Hub install failures now show a specific reason** for insufficient storage, unsupported CPU architecture, and OEM install blocks (e.g. Samsung Auto Blocker) — unrecognized `pm install` errors now surface the actual failure token in the toast so users can self-diagnose without capturing logcat. Follow-up to [#412](https://github.com/thejaustin/ShizukuPlus/issues/412), [#446](https://github.com/thejaustin/ShizukuPlus/issues/446).
- **Update download failure notifications now distinguish cause** (insufficient storage, interrupted/unresumable transfer, network/HTTP error) instead of one generic "download failed" message. Follow-up to [#414](https://github.com/thejaustin/ShizukuPlus/issues/414).
- Fixed two dead in-app help links pointing at wiki pages that no longer exist (`wiki/Setup`, `wiki/Supported-apps`) — now point at real pages/sections.
- **Fixed the app icon's plus badge being almost entirely invisible on real devices** (confirmed via an on-device Samsung One UI notification-icon screenshot) — it was positioned to match the flat, non-adaptive uploaded image pixel-for-pixel, which sits outside the ~66dp/108dp circular safe zone real adaptive-icon mask/notification-icon compositors apply. A flat unmasked preview never caught this. Repositioned the plus to a spot verified against an actual circular-mask simulation; the cat/hexagon stay at their original scale and position.
- **Dev/Beta update channel could get stuck on a stale prerelease** — it always preferred the newest *prerelease*-flagged release over the newest release overall, even after a newer *stable* release had since been cut. Now compares actual version codes and takes whichever is genuinely newer. (Follow-up to the r2287-era Dev/Beta fix below — same function, a different edge case.)

### ✨ Enhancements

#### UI / UX
- **Material 3 Expressive (M3E) animation improvements** — home screen card entrances now use `m3_emphasized_decelerate` interpolator with a 0.92→1.0 scale-grow, matching the M3E motion spec. Spring press animations upgraded to `animateToFinalPosition()` with `STIFFNESS_MEDIUM + DAMPING_RATIO_NO_BOUNCY` for smooth mid-animation reversal. App Backup screen introduced as a new home card and detail screen.
- **Frosted glass AppBar** — when the Blur UI setting is enabled (Settings → Personalization → Frosted Glass Effect), the toolbar container's background is now semi-transparent on Android 12+, letting the window-level blur show through. Previously the opaque AppBar background blocked the blur entirely. ([#449](https://github.com/thejaustin/ShizukuPlus/issues/449))
- **M3E shape tokens updated** — `ShapeAppearance.Modern.Corner.ExtraLarge` cornerSize raised to 32dp (M3E ExtraLarge standard, up from 28dp).
- **Detail screens now use Z-axis (forward/back) transitions** instead of X-axis (lateral) — Z-axis (`MaterialSharedAxis.Z`) is the M3E standard for root→detail navigation. Subclasses can override `transitionAxis` to opt into X for truly lateral peer screens.
- **App icon plus badge repositioned** to match the intended design and separated back out into its own semi-transparent overlay layer (was previously baked into the flattened artwork at the wrong position).
- **Themed Icons (Material You) now scoped to just the plus badge** rather than the whole icon — the monochrome layer Android re-tints for Themed Icons no longer includes the cat/hexagon, since the platform re-tints the entire monochrome layer as one flat color and there's no way to theme only part of it.
- **New onboarding step**: "Themed app icon" toggle (defaults on) with a direct link to your launcher's icon-theming settings.
- **New wiki page: [Permissions](https://github.com/thejaustin/ShizukuPlus/wiki/Permissions)** — every permission Shizuku+ requests, mapped to the feature it powers and why.

#### Developer Experience
- **Release notes restyled** to Keep a Changelog's category vocabulary (Security → Breaking → Added → Fixed → Other), based on researching upstream Shizuku, the thedjchi sibling fork, and NewPipe's conventions.
- Added `.github/pull_request_template.md` and `CONTRIBUTING.md`.
- Repo description, topics, and homepage updated on GitHub for discoverability.

## [v13.6.0.r2287 → r2343]

*Co-developed with Claude Code*

### 🐛 Bug Fixes

#### Server / Service
- **Fixed `attachApplication` (binder code 17) falling into a dead branch of `Service.onTransact()`** — the call appeared to succeed to the connecting app, but the server never actually registered it as a client, so every API v13+ client's connection silently failed at the first step. The single largest root cause behind "Shizuku+ not detected" reports across this project's history. Affected Morphe, InstallerX Revived, Droid-ify, ObtainX, Obtainium, MT Manager, Termux `rish`, and others. (`8fbe5e47`, [#406](https://github.com/thejaustin/ShizukuPlus/issues/406))
- **Fixed the Watchdog's external crash-recovery mechanism (added for [#415](https://github.com/thejaustin/ShizukuPlus/issues/415)/[#417](https://github.com/thejaustin/ShizukuPlus/issues/417)) being silently inert** — `ShizukuStateMachine.update()` only preserved `STARTING`/`STOPPING`/`CRASHED` from the prior in-memory state when the binder was found dead; a prior `RUNNING` state fell through to `STOPPED` instead of `CRASHED`, so `WatchdogService`'s restart logic (which only reacts to `CRASHED`) never fired for an unexpected death — even with Watchdog enabled, even after the dedicated external `AlarmManager` re-arm added specifically to survive a full process freeze/kill. Also: a freshly cold-started process (exactly what that re-arm produces) had no in-memory record of the prior state at all, so even the "was it running" check was blind across a restart — fixed by persisting the last settled state. Found via live on-device verification, not a bug report.
- **Fixed apps authorized from Shizuku+'s own Application Management screen staying stuck showing "no access"** until manually force-stopped and relaunched — the server updated its own permission record and the OS-level runtime permission, but never told an already-connected client anything changed (there's no protocol-level way to push that correction to a live client). Fixed by force-stopping the app on a denied→granted transition, so its next launch gets a fresh handshake — mirroring the mechanism the revoke path already used for the identical reason. A second, related gap fixed the same way: only the specific process that triggered an interactive permission-request dialog got notified: any other already-connected process of the same app (e.g. a background `:service`) stayed stale. Found by directly comparing two screenshots from a reporter running an old vs. current build of the same third-party app ([#371](https://github.com/thejaustin/ShizukuPlus/issues/371)).
- Multiple rounds of Cached Apps Freezer (Android 12+) hardening: retry ladders extended to `{300, 1000, 3000, 9000}ms`, a post-startup catch-up pass for apps already running/frozen when the server (re)starts, and `IBinder.addFrozenStateChangeCallback` on API 36+ where available — see [#371](https://github.com/thejaustin/ShizukuPlus/issues/371) for the full investigation history.
- SU Bridge: fixed a redeploy leaving `rish_shizuku.dex` read-only from the previous run, breaking subsequent deploys with `EACCES`. ([#402](https://github.com/thejaustin/ShizukuPlus/issues/402))
- Shell (`rish`) consent: fixed the caller's UID not being threaded through when `callingPackage` is absent (classic `rish_shizuku.dex` doesn't include it), which made "Allow always" silently grant nothing. ([#391](https://github.com/thejaustin/ShizukuPlus/issues/391))

#### Manager App (UI)
- **Fixed the app icon diverging from upstream RikkaApps/Shizuku's cat/hexagon artwork** beyond the intentional plus badge (size, position, and background/foreground layer split had all drifted) — reverted to upstream's unmodified art with just the plus repositioned over it.
- **Fixed the "Network monitor" notification running permanently for every user** — `AutomationService` was started unconditionally on every app launch, producing a permanent foreground notification and a 2-second background polling loop for a feature that was never functional: its only two rules (`NetworkFirewallRule`, `AppSpecificProfileRule`) matched hardcoded demo values (`"HomeWiFi"`/`"WorkNetwork"` SSIDs, `"com.banking.app"`/`"com.games.app"` packages) that can never match a real device, with all real logic commented out. Stopped the service from auto-starting; the scaffolding remains in place pending real rule implementation (#6).
- **Fixed the "Start on boot" toggle silently reporting "off" while boot auto-start was actually enabled and running regardless** — it read `getComponentEnabledSetting()`, which returns "default" (not "enabled") for anyone who never explicitly touched the toggle, even though the receiver's manifest declares it enabled by default.
- Fixed the Auto Blocker "Check" action routing to a raw system intent instead of the correct Samsung settings deep link.
- Restored the responsive 2-column home grid on large screens/DeX. ([#76](https://github.com/thejaustin/ShizukuPlus/issues/76))
- Fixed deprecated `ListPreference` summary warnings across 10+ settings screens; extracted 100+ hardcoded English strings for i18n; added TalkBack support for swipe-to-act gestures in Application Management.

### ✨ Enhancements

#### Theming
- **New "Cut (Angular)" shape style** — a real octagon silhouette (cut corners), not just another rounded-corner radius variant.
- **One UI Style toggle rescoped to structure only** (shapes/typography) — color is the dynamic-color/custom-accent toggle's job; removed the color overrides that previously competed with it.

#### Locale
- **Migrated locale handling to `AppCompatDelegate.setApplicationLocales()`** (Android 13+ per-app language API), replacing the legacy custom mechanism — integrates with the system Settings → App Info → Language screen. ([#429](https://github.com/thejaustin/ShizukuPlus/issues/429))

#### Developer Experience
- **Fixed release notes silently generating empty Added/Fixed/Other sections on every release** since the Keep a Changelog restyle above — a `grep -vFf` against an empty exclude pattern matches every line, so the categorization step excluded everything whenever a release had no security/breaking commits (the common case). Verified against GitHub's actual published bodies for several recent releases before fixing; republished corrected notes for the affected releases and backfilled the historical rollup.
- **Fixed a manual `workflow_dispatch` run against a non-master branch being able to publish a real, non-prerelease "Latest" release** — the `prerelease` input defaults to `false`, which is the intentional stable-promotion mechanism for master, but nothing stopped that default from applying to any other branch too.
- Updated the release-notes "most recent major"/"most recent critical fix" spotlight pointers, stale since r2202/r2153.

## [v13.6.0.r2287]

### 🐛 Bug Fixes

#### Manager App (UI)
- **Fixed a launch crash on some OEM builds** (observed on Samsung OneUI 3.1/Android 11): notification icons that used a theme-attribute tint (`android:tint="?attr/..."`) could fail to render in the system's own theme context, throwing `RemoteServiceException: Couldn't create icon StatusBarIcon` and crashing the app outright. Found and fixed 4 affected icons (`AutomationService`'s foreground notification plus 3 others reused across update/wireless-ADB notifications). Added `scripts/dev/check-notification-icons.sh` (wired into `scripts/dev/lint.sh`) and a JUnit regression test so this class of bug can't silently reappear. ([#422](https://github.com/thejaustin/ShizukuPlus/issues/422))
- **Fixed Update channel: Dev/Beta channel silently tracking the same release as Stable.** This is a regression of the earlier r2248-era fix below — `checkViaApi()` picked the newest release *by creation date* instead of by its `prerelease` flag, so once a stable release was cut, the dev/beta channel converged on it too. Also fixed the underlying CI bug: `gh release create` computed `IS_PRERELEASE` for the build flavor but never passed `--prerelease` to the release itself, so every published release was `prerelease: false` regardless of channel. ([#407](https://github.com/thejaustin/ShizukuPlus/issues/407))
- **SU Bridge self-test now shows the actual deploy failure reason** (exit code/stderr) instead of a generic "could not deploy" message — the detail was already being captured to logcat but never reached the dialog users actually see/report. ([#402](https://github.com/thejaustin/ShizukuPlus/issues/402))

## [Unreleased / Build r2248+]

*Co-developed with Claude Code*

### 🐛 Bug Fixes

#### Server / Service
- Fixed `attachApplication` (binder code 17) dead-code regression that caused ALL API v13+ clients to fail. Affected: Morphe, InstallerX Revived, Droid-ify, ObtainX, Obtainium, MT Manager APK install, Termux `rish`. ([#406](https://github.com/thejaustin/ShizukuPlus/issues/406), [#394](https://github.com/thejaustin/ShizukuPlus/issues/394), [#392](https://github.com/thejaustin/ShizukuPlus/issues/392), [#391](https://github.com/thejaustin/ShizukuPlus/issues/391), [#387](https://github.com/thejaustin/ShizukuPlus/issues/387), [#386](https://github.com/thejaustin/ShizukuPlus/issues/386))
- Fixed shell consent (`rish`/`adb`) not persisting after `Allow always` tap. Callers are now identified by UID via `Os.getuid()` fallback even when PM lookup fails. ([#391](https://github.com/thejaustin/ShizukuPlus/issues/391), [#398](https://github.com/thejaustin/ShizukuPlus/issues/398))
- Fixed SU bridge self-test failing due to writing to an already-open file descriptor. ([#402](https://github.com/thejaustin/ShizukuPlus/issues/402))
- Fixed Live Activity notification reappearing after disabling the toggle. ([#400](https://github.com/thejaustin/ShizukuPlus/issues/400))
- SU bridge: removed the `id()`/`whoami()` shell function mocks from the dynamic Magisk-mode injection header — they were shadowing the real coreutils binaries for every `sh -c` call routed through the bridge, not just root-check probes.
- Fixed `newProcess()` dropping the entire boot environment (`BOOTCLASSPATH`, `ANDROID_DATA`, `ANDROID_ROOT`, etc.) when Magisk mocking is enabled and the caller passes a `null` env — it now seeds from `System.getenv()` before appending the Magisk vars, instead of replacing the env outright. Fixes spawned `app_process` children dying instantly with `ANDROID_DATA environment variable unset`. ([#410](https://github.com/thejaustin/ShizukuPlus/issues/410))

#### Manager App (UI)
- **Eliminated black screen flash** on every theme/accent/icon/blur change — settings screen now recomposes in-place without `Activity.recreate()`. ([#407](https://github.com/thejaustin/ShizukuPlus/issues/407) adjacent)
- Fixed Update channel: Dev/Beta channel now correctly fetches pre-releases and selects the right APK (Plus vs Drop-In). ([#407](https://github.com/thejaustin/ShizukuPlus/issues/407))
- Fixed bottom navigation bar overlapping: Feature Hub, Settings list, Activity Log, and App search bar. 
- Fixed Dhizuku Mode toggle not persisting state immediately on change.
- **Fixed "Shizuku is running" status icon** rendering as a garbled server-rack-and-checkmark mashup — reverted to the clean circle-checkmark glyph.
- **Fixed One-handed mode squeezing all content into the bottom-right corner** — the scale-down pivot was anchored at the corner instead of bottom-center, the actual Samsung OneUI behavior. Affected both Home and Settings.
- **Fixed update downloads appearing to hang instead of prompting to install** — the silent-install attempt (via root/Shizuku) had no timeout, so a stuck root prompt or dead Shizuku binder wedged the coroutine forever with no fallback to the system installer. Now falls back after 5s.
- Fixed update download progress notification vibrating/alerting on every 500ms progress tick instead of only once.
- **Manual crash reports were shipping with empty log sections** (`[#405](https://github.com/thejaustin/ShizukuPlus/issues/405)`, `[#397](https://github.com/thejaustin/ShizukuPlus/issues/397)`, `[#317](https://github.com/thejaustin/ShizukuPlus/issues/317)`) — the logcat tail filter (`*:S AndroidRuntime:E ...`) silenced everything by default and only allow-listed tags that didn't match real crash output. Now captures the last 300 unfiltered lines of the app's own logcat.
- Fixed `showStartAdbHome()` defaulting to `true` in Java while its XML preference (`show_start_adb_home`) defaults to `false` — first-run users saw the "Start ADB" home shortcut despite the setting screen showing it off.

### ✨ Enhancements

#### UI / UX  
- **Redesigned App Icon**: Added a "Plus" badge in the upper right of the app icon across the board. The Plus integrates directly into the original hexagon geometry.
- **Themed Icon Support**: Implemented a fully vector Material You monochrome icon, enabling the app icon (including the new Plus badge) to respond flawlessly to expressive theme color changes.
- **Authentic Samsung OneUI one-handed mode**: Content scales to 75% with bottom-center pivot anchor (matching Samsung's actual behavior) with smooth spring animation. Works on all devices.
- **Samsung OneUI Settings header**: LargeTopAppBar uses ExtraBold (W800) title at 28sp with −0.5sp letter-spacing and transparent container — matching OneUI 6/7 Settings exactly.
- **App search bar**: Modernized with Material 3 pill shape (28dp corner radius).
- Shell consent notification now shows the app package name instead of "cannot be identified" when PM lookup fails.

## [v13.6.0.r2239]

### Bug Fixes
- Power-save whitelist re-applied on each `bindApplication` retry.
- Watchdog scope clarified; RNDIS/Ethernet transport monitored.
- Binder delivery retried on frozen-app failure with actionable UI feedback.

## [v13.6.0.r2222]

### Bug Fixes  
- Shell caller now correctly identified; package name shown in consent notification.

## [v13.6.0 / r2215]

Initial public release of Shizuku+.
