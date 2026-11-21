# Shizuku vs. Shizuku+

Shizuku+ is a fork of Shizuku. Apps written for Shizuku keep working with it, and it adds a few things on top. This page explains how the two differ under the hood; the [README](../README.md) has the user-facing feature list.

## 1. Core Privilege Architecture

| Feature | Original Shizuku | Shizuku+ |
|---------|------------------|----------|
| **Primary Backend** | Root / ADB Shell | Root / ADB Shell / **Dhizuku (Device Owner)** |
| **Service Process** | `shizuku_server` | `shizuku_plus_server` |
| **Binder Interface** | `IShizukuService` | The same `IShizukuService`, plus separate Plus API interfaces (`af.shizuku.server.I*Plus` and others) and per-app API scopes |

### Unified Backend
Besides root and ADB, Shizuku+ has **Dhizuku Mode**: the Shizuku+ app itself becomes the **Device Owner** and shares that with Dhizuku apps, without root, and it survives reboots. "Activate now" sets it up through the running service, so no computer is needed. The trade-off: while Shizuku+ is Device Owner, Android blocks new Secure Folder, work profiles, Private Space and Dual Messenger, and the setup refuses to run if one of them already exists. An experimental limited mode uses Profile Owner of the main profile instead, which Android allows next to other profiles, with fewer powers (no silent installs). Apps built with an older Dhizuku library need the optional Dhizuku Compat add-on.

### Compatibility Layer (Package Detection & Binder Delivery)
Shizuku+ installs under its own application ID (`af.shizuku.plus.api`) so it can coexist with stock Shizuku, but Shizuku-aware apps traditionally look for the fixed `moe.shizuku.privileged.api` package. Shizuku+ bridges this with two independent layers:

*   **Detection:** a bundled **Compat Hub** companion (the `compat` module, package `moe.shizuku.privileged.api`) satisfies third-party `isInstalled` checks and relays legacy `REQUEST_BINDER` broadcasts to the real manager. The alternative **drop-in** flavor installs directly as `moe.shizuku.privileged.api`.
*   **Delivery:** the service hands the privileged binder to each authorized app via a ContentProvider `sendBinder` call, carrying `BinderContainer` parcels for all three API namespaces (`af.shizuku`, `rikka.shizuku`, `moe.shizuku`). Both the compat hub and this handshake must succeed for an app to see Shizuku+ as "running."

## 2. The Plus API Ecosystem

Shizuku+ goes beyond simple shell execution by exposing stable, version-agnostic bridges to internal Android system services.

### Overlay Manager Plus (OMP)
*   **The issue:** Android 14+ and One UI 8+ restrict the standard `OverlayManager` system service, which broke rootless theming.
*   **What Shizuku+ does:** an `OverlayManagerTransaction` bridge that lets theming apps (Substratum, Hex Installer) apply overlays without root.
*   **Status:** experimental and off by default (Settings → Experimental features → System theming bridge).

### AICore+ Automation Bridge
*   **Status:** experimental and off by default.
*   **Capability:** Provides a privileged `AccessibilityService` proxy.
*   **Functions:** 
    *   `dumpHierarchy()`: Exports the UI tree as XML.
    *   `performTap/Swipe()`: Injects taps and swipes.
*   **Use case:** AI-driven automation (like Tasker with an LLM) that interacts with other apps without root.

### Root Compatibility Hub (SU Bridge)
Shizuku+ acts as a translation layer for legacy apps.
*   **SU Bridge:** an `su` wrapper that Shizuku+ exports to a path apps can be pointed at (apps that support a custom root path). It routes commands through the Shizuku binder and translates common ones into real framework calls. It doesn't replace `/system/bin/su`, and exporting rish to terminal apps leaves it out unless you tick it.
*   **Module mocking:** can make apps believe Magisk or BusyBox is installed, so legacy tools unlock their root features. Banking and Google Wallet apps can flag this, so it's off by default, and Service Doctor's Wallet & Messages repair can turn it off again.

## 3. Performance & Stability Improvements

### Transparent Shell Interceptor
Original Shizuku apps often run shell commands (e.g., `pm install`). These are slow because they fork a new process.
*   **What Shizuku+ does:** intercepts common ones and runs them through direct framework APIs (like `IPackageManager`) inside the existing privileged process.
*   **Result:** no new process per command, so shell-heavy apps get answers faster.

### Service Doctor
Checks for the things that stop the service from starting or keep killing it, from wireless debugging and port discovery to vendor features like Samsung Auto Blocker, Xiaomi/HyperOS autostart and ColorOS/OxygenOS auto-launch, and offers fixes. How much it does on its own is chosen at first-run setup: Off, Quiet, Balanced or Active.

### Watchdog Resilience
On for new installs (from the first time the service starts); an update keeps the setting you had. Three independent recovery layers, each covering a gap the others can't: an in-process crash listener for fast recovery, a periodic WorkManager backstop in case just the watchdog service dies, and an `AlarmManager`-based external re-arm that can restart everything even if an OEM freezer (e.g. Samsung One UI's "Sleeping apps") kills the *entire* app process on screen lock — the one failure mode nothing running inside that process can self-detect. See the [Service Connection wiki page](https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#watchdog).

## 4. UI/UX Refinement
*   **Material 3 Expressive:** spring animations and shape-morphing icons.
*   **What's New:** after an update, a timeline of the releases since the version you had, with a "You were here" marker.
*   **Bulk management:** grant, revoke or hide several apps at once.

---
*For documentation on integrating the Plus APIs into your own app, see the [Shizuku+-API](https://github.com/thejaustin/ShizukuPlus-API) repository. For a full breakdown of every permission Shizuku+ requests and why, see the [Permissions wiki page](https://github.com/thejaustin/ShizukuPlus/wiki/Permissions).*