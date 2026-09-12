# AI Pair-Programming & Development Attributions

This repository is co-developed by **Justin Austin ([@thejaustin](https://github.com/thejaustin))** in collaboration with advanced AI coding assistants. In accordance with open source provenance and transparency, this document details the specific contributions, commit ranges, and feature areas developed with each AI assistant.

---

## 🤖 AI Co-Developers Overview

| Assistant | Organization | Role / Focus Areas | Co-Author Trailer |
| :--- | :--- | :--- | :--- |
| **Claude Code** | Anthropic | Core architecture, Binder IPC refactor, Drop-In variant, Sentry stabilization, M3E Settings redesign | `Co-authored-by: Claude <81847+claude@users.noreply.github.com>`<br>`Co-authored-by: Claude <noreply@anthropic.com>` |
| **Antigravity** | Google DeepMind | Android 16/17 compatibility, SU Bridge & Magisk routing, ADB loopback/hotspot networking, Handover daemon | `Co-authored-by: Antigravity <antigravity@google.com>` |
| **Jules** | Google Labs | Security token authentication for Binder requests, DAO performance, logging refactor, unit testing | `Co-authored-by: google-labs-jules[bot] <161369871+google-labs-jules[bot]@users.noreply.github.com>` |

---

## 1. Claude Code (Anthropic)

[![Co-developed with Claude](https://img.shields.io/badge/Co--developed%20with-Claude-D97706?style=flat-square&logo=anthropic&logoColor=white)](https://claude.ai)

* **Primary Timeframe**: April 2026 – September 2026 (~500+ commits)
* **Releases Covered**: `v13.6.0.r1638` through `v13.6.0.r2343`+

### Key Contributions:
* **Binder IPC Migration**: Systematically replaced fragile `Runtime.getRuntime().exec()` calls with direct Android Binder IPC primaries across `AppInspector`, `BackupRestorePlus`, `StatusBarGovernorPlus`, `WindowManagerPlus`, and `ApkPatcher`.
* **Samsung OneUI 8 & Android 16 Compatibility**:
  - Prevented null `IRemoteProcess` crash in client applications on Samsung OneUI 8 ([#466](https://github.com/thejaustin/ShizukuPlus/issues/466)).
  - Preserved `BinderContainer` wire classes to ensure legacy Shizuku client apps connect seamlessly ([PR #307](https://github.com/thejaustin/ShizukuPlus/pull/307)).
  - Resolved manager race conditions where service appeared "not running" after successful start ([PR #308](https://github.com/thejaustin/ShizukuPlus/pull/308)).
* **Sentry Crash Monitoring & Bug Squashing**:
  - Integrated Sentry crash reporting (`sentry-android-core`).
  - Resolved over 30 distinct Sentry crash classes (fragment view lifecycles, R8 inflate errors, WorkManager initialization guards, and background service start exceptions).
* **UI/UX & Design System**:
  - Full redesign of Settings into hierarchical Google-style subscreen navigation with Material 3 Expressive (M3E) components.
  - Added drag-to-reorder cards on Home screen with spring touch animations and predictive-back gestures.
* **Root Compatibility Hub & Fake SU**:
  - Implemented the fake `su` backend with server-side interception for root apps without native Shizuku support.
  - Automated SU path configuration for 60+ apps.

---

## 2. Antigravity (Google DeepMind)

[![Co-developed with Antigravity](https://img.shields.io/badge/Co--developed%20with-Antigravity-4285F4?style=flat-square&logo=google&logoColor=white)](https://deepmind.google)

* **Primary Timeframe**: June 2026 & September 2026
* **Key Commits**: `09157e52`, `1b5dfbb3`, `002941ad`, `b664d50c`, `6b018da5`, `8718903d`, `96318a00`, `1ad69e81`, `c28fc60f`, `65a127a3`, `388457ba`, `eea407ff`, `bc50b6a3`

### Key Contributions:
* **Seamless Stock Shizuku Migration**:
  - Implemented detached root shell execution and automatic daemon shutdown in `StockShizukuCompat` to free up ServiceManager and port 5555 without user disruption (`002941ad`).
* **SU Bridge & Magisk Version Routing**:
  - Implemented robust `su` CLI parsing for `-v`, `-V`, and `--version` flags to return accurate MagiskSU versions (`6b018da5`).
  - Enhanced server-side routing for `su` command execution.
* **Swift Backup & Shifted AIDL Handling**:
  - Diagnosed and fixed shifted transaction codes in the legacy IPC bridge, eliminating `NullPointerException` crashes in Swift Backup (`8718903d`).
* **Networking & 5G Loopback Support**:
  - Added loopback socket probing (`127.0.0.1:5555`) to bypass Wi-Fi requirements for ADB on cellular networks.
  - Guided hotspot setup for Android mDNS limitations (`b664d50c`).
* **Android 16 / 17 Compilation & Build Fixes**:
  - Resolved hidden `IPackageDataObserver` and `IPackageDeleteObserver` API compilation for Android 16/17 (`388457ba`).
  - Resolved `DeviceConfig.SYNC_DISABLED_MODE_PERSISTENT` compatibility error (`eea407ff`).
  - Refactored `ShizukuSystemProperties.get` in `HomeViewModel` (`bc50b6a3`).

---

## 3. Jules (Google Labs)

[![Developed with Jules](https://img.shields.io/badge/Developed%20with-Jules-34A853?style=flat-square&logo=google&logoColor=white)](https://jules.google.com)

* **Primary Timeframe**: May 2026
* **Key Pull Requests**: [#213](https://github.com/thejaustin/ShizukuPlus/pull/213), [#214](https://github.com/thejaustin/ShizukuPlus/pull/214), [#215](https://github.com/thejaustin/ShizukuPlus/pull/215), [#216](https://github.com/thejaustin/ShizukuPlus/pull/216), [#217](https://github.com/thejaustin/ShizukuPlus/pull/217), [#218](https://github.com/thejaustin/ShizukuPlus/pull/218), [#219](https://github.com/thejaustin/ShizukuPlus/pull/219), [#232](https://github.com/thejaustin/ShizukuPlus/pull/232), [#244](https://github.com/thejaustin/ShizukuPlus/pull/244)

### Key Contributions:
* **Security Hardening**:
  - Implemented token-based authentication on `BinderRequestReceiver` and `ShellRequestHandlerActivity` to prevent unauthorized apps from stealing privileged binders via the `REQUEST_BINDER` intent ([#213](https://github.com/thejaustin/ShizukuPlus/pull/213)).
* **Code Health & Standardized Logging**:
  - Replaced unconfigurable raw `System.out`/`System.err`/`printStackTrace` calls with Timber and standard Java logging across `PlusShell`, `ShizukuShellLoader`, and `ParcelFileDescriptorUtil`.
* **Database & Memory Optimization**:
  - Refactored `ActivityLogDao` cleanup query from an $O(N)$ full in-memory scan into a direct database-level single `DELETE` query with `ORDER BY timestamp DESC LIMIT` ([#215](https://github.com/thejaustin/ShizukuPlus/pull/215)).
* **Automated Testing**:
  - Added Robolectric unit tests for `ActivityLogDao` and `AICorePlusImpl.simulateTouch`.

---

## 📋 Git Commit Co-Authorship Policy

To ensure automatic attribution for all future contributions, [`~/.git-hooks/prepare-commit-msg`](file:///data/data/com.termux/files/home/.git-hooks/prepare-commit-msg) automatically appends the corresponding Git trailer:

```text
# During Claude Code sessions:
Co-authored-by: Claude <81847+claude@users.noreply.github.com>
Co-authored-by: Claude <noreply@anthropic.com>

# During Antigravity sessions:
Co-authored-by: Antigravity <antigravity@google.com>
```
