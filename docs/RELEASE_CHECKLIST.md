# Stable release checklist (next stable after v13.7.0)

Built 2026-10-24 from the device / screenshot checklists scattered through `AI_DEVLOG.md`
(2026-10-22 → 2026-10-24) plus the release mechanics. Tick items as they're verified; anything
that fails becomes an issue or a fix before the stable dispatch.

## 0. Blockers (all must be done)

- [ ] **Unit tests green in CI.** New `unit-tests` job in `app.yml`. First run includes the 4
      Robolectric suites that can't run on the phone (OnePromptScenarios, VendorBatteryRestriction,
      WatchdogBatteryExemption, ActivityLogDao).
- [ ] **Emulator smoke tests green.** `smoke-test` job (JniSmokeTest + ScreenSmokeTest), dispatch only.
- [ ] **Release smoke test green.** `release-smoke.yml` runs the R8 release build on an emulator
      (nightly when master changed, and on ProGuard/dependency changes). Dependabot PRs wait on it.
- [ ] **Regression watch read.** `scripts/dev/regression-watch.sh origin/master` (also in the CI
      step summary): re-check the earlier issue fixes it lists.
- [ ] **On-device smoke pass:** `bash scripts/dev/device-smoke.sh` (keeps your data).
- [ ] **Release (R8) build on the phone:** see §2. Needs an uninstall if the phone has a debug build,
      so **export a backup first**.
- [x] **Merge origin/master** (`212d3a2a` README, `5c0a0716` az #619). Resolve values-az with the
      LOCAL side, then confirm no `*_value`/`*_values` arrays came back in any locale and none of
      the strings removed in `ef5bfa82` returned.
      Done in `3a4a1e04`; 2026-10-24: `check_arrays.py` OK, only the 3 About strings restored on purpose (`f212640b`) came back.
- [x] `api` submodule `e7d1773` is on its remote (verified 2026-10-24 and re-verified 2026-10-25), so the parent push is safe.
- [x] Fold `## [Unreleased]` in `CHANGELOG.md` into the release section: `## [v13.7.0-p1 — Major Stable Release] - 2026-10-25`. Version follows the
      AGENTS.md fork-versioning rule (no upstream version bump without merging upstream).
- [x] **Code review** of everything since the part-2 ultrareview base: `/code-review medium`
      on `9f4287b4..HEAD` (~95 commits). Fix or file what it finds.
      2026-10-24: 10 findings. Fixed: `38c2738a` (auto-grant revoke user/leftovers/manager keys, manager id uid check), `c31a8e48` (privileged keys on trusted imports), `4d1262e4` (scrubber, AICore+ consent baseline, app DB warm-up), `a25529ff` (smoke gate). Not fixed, low impact: `RemoteDbSyncWorker` sends no conditional headers while the bundled revision is current (daily full download of a small file), and a pre-rework `app-context-db.json` blob stays in prefs.
- [x] **Hard-coded English → strings.xml** (promised to Figim on #619/#622, then ping them):
      SU Bridge self-test report (`database/.../RootCompatHelper.kt` ~:600-650); manager activity-log /
      watchdog lines (`AdbStarter.kt` ~:170, `ShizukuStateMachine.kt` ~:363, `ReconnectTrigger.kt` ~:57).
      Server-side log text ("Connected to Shizuku", `ShizukuService.java` ~:759) can't use manager
      resources: log a key and render it in the manager, or leave English and say so.
      Done in `fe82a9f9`: log stores English, `ActivityLogText` maps it when shown (server lines too); self-test report built in the manager. Ping Figim on #622 after the push.
- [x] Decide on #622 (Figim's open PR): Strings extracted in `fe82a9f9`, ready to ping Figim on push and merge/close with release.
- [ ] Push only on explicit OK. Push to master = **prerelease**; stable = `workflow_dispatch` on
      master with `prerelease=false`.
- [ ] After release: close #623 / #624 (fix `9a811eee` ships here), and reply on #626 (`ef092a45`).

## 1. Crash / data safety (P0, device)

- [ ] Cold start: "Authorized N applications" from the first frame (no 0 flash).
- [ ] Settings search: type several queries; every result shows its icon (#623/#624).
- [ ] Upgrade a build with **Rounded edges OFF** → Corner rounding shows Sharp and corners stay sharp.
- [ ] Restore an **old backup** with `rounded_edges_enabled=false` → Sharp. Backup → restore round trip
      shows a toast then the restart hint (not two snackbars).
- [ ] Theme changes: Material screen transitions **on and off** (watch for #483 on Android 16: black
      screen / crash after a theme change).
- [ ] Known open: `custom_accent` / `use_system_color` only re-theme XML screens on the next resume
      (`applyTheme(requiresRecreate = false)`). Decide: fix or ship as known.

## 2. Release build only (R8)

- [ ] Mavericks screens open (#200, factory keep rules).
- [ ] Fresh install: activity log works, no SQLite race (#200).
- [ ] Shadow Binder hidden packages hide from a client app (#199).
- [ ] Root compatibility shows descriptions, root level, Shizuku-native label and SU-path hints
      (new serializer keeps in `database/consumer-rules.pro`; an empty list means R8 stripped them).
- [ ] rish works (`rish -c id`): #626 VerifyError fix `ef092a45`.
- [ ] Crash-reporting consent prompt appears (debug builds have no DSN, so only testable here).

## 3. Features (P1, device)

- [ ] **Pairing flow** end to end (never tested since `07e930e3`): notification heads-up per new port,
      reverts to "searching" when the dialog closes, auto-start after pairing.
- [ ] Auto-grant revoke when turned off; Tasker/Locale consent gate.
- [ ] **App database:** App interactions > Data shows "Descriptions and setup tips for 72 apps…".
      Tap → "Checking GitHub…" → result. Before the push it says *failed*; after the push it says
      *already up to date*.
- [ ] Apps list: rows show the description only (no badge); freeze/unfreeze, copy package name and
      no-launcher show snackbars.
- [ ] Snackbar confirmations: Advanced (backup/restore, reset ADB keys), Root integration
      (device-owner toggles), Home restore cards, Device hardening.
- [ ] Floating nav (experimental): no back arrow on Apps / Hub / Settings root only while that tab is
      on the bar; hide a tab → that screen gets its back arrow and no bottom gap; docked default,
      reselect scrolls to top, no autohide under TalkBack.
- [ ] About: Dev channel then Cancel stays Stable; auto-check off disables + clears auto-install;
      missing install permission blocks auto-install with the dialog.
- [ ] Verified installation: VirusTotal key flow + Pithus. Package governor toggle reaches the server.
- [ ] Predictive back in Apps selection and Permission Manager detail (cancel mid-gesture resets).
- [ ] TalkBack: slider step names, badge readout, "Help" long-press action.
- [ ] App language set to e.g. Deutsch: Activity log rows, the live activity notification and the
      SU Bridge self-test dialog are in German (older entries too); Copy/Export stays English.
- [ ] Auto-grant off with a granted app: its grant is revoked; the manager keeps WRITE_SECURE_SETTINGS/DUMP.
- [ ] AICore+: on an update where the service was already on, the self-heal does not re-enable it
      after an OEM/user disable until it has been switched on again by hand.

## 4. Visual pass (P2, screenshots)

- [ ] One UI corners; Rounded edges off + One UI + a corner override; each corner step incl. System default.
- [ ] Cut style in Permission Manager (Compose) vs an XML screen; the 9 retokenized cards.
- [ ] Bold-only type (onboarding headlineLarge 32sp) and One UI + Bold.
- [ ] Every accent light + dark, Full colour schemes on and off; swatches match the applied accent.
- [ ] High contrast: with an accent, Default, system colours; Android 14 Contrast Medium/High; AMOLED + HC outlines.
- [ ] Switch thumb check/X everywhere (incl. onboarding + GrantPermissions); Expressive button press
      morph; connected groups; LoadingIndicator in app picker / profiles / backup; snackbar inverse
      colours on AMOLED; System Hub containers with an accent.
- [ ] Home card drag lift/drop (re-pick mid-settle), edit-mode in/out, section header double-tap,
      switch-row icon dim/scale; feature badges at a large system font.

## Tests to write next (highest regression risk this release)

- [ ] Corner-settings migration `migrateCornerSettingsIfNeeded()`: at start-up and after an import.
- [ ] One-time cleanups: `custom_app_name` seed, `clearLegacyEnhancementPrefs()`, haptics default.
- [ ] Backup round trip: export → import, plus an old backup with `rounded_edges_enabled=false`.
- [ ] Sync-key parity: every key `syncAllPlusFeaturesToServer()` sends is one the server reads.
- [ ] Cross-flavor settings sync (`SettingsSharingProvider`) with both flavors installed (device, open since 2026-10-03).

## Known, not blocking

- New strings are English-only until translations refresh (Data, Long-press actions, app-database status).
- ~50 toasts remain outside Settings and the Apps list (see `docs/M3E_DESIGN_PLAN.md` 3.4).
- #625 / #629: reports without a stack trace (connection drops; one client binding its user
  service several times a second). Worth a look, not a crash.
- Removed per-app enhancements are tracked in #631–#635.
