# Shizuku vs. Shizuku+ Architectural Comparison

Shizuku+ is a comprehensive enhancement of the foundational Shizuku project. While maintaining 100% compatibility with existing Shizuku-enabled apps, it introduces a modern, modular architecture designed for the next generation of Android power-user tools.

## 1. Core Privilege Architecture

| Feature | Original Shizuku | Shizuku+ |
|---------|------------------|----------|
| **Primary Backend** | Root / ADB Shell | Root / ADB Shell / **Dhizuku (Device Owner)** |
| **Service Process** | `shizuku_server` | `shizuku_plus_server` (Optimized native bridge) |
| **Binder Interface** | Standard IShizuku | Extended IShizukuPlus with granular capability flags |
| **Multi-User** | Basic support | Robust cross-user binder sharing (e.g. Secure Folder) |

### Unified Backend
Unlike the original which relies primarily on `shizuku_server` started via ADB, Shizuku+ integrates **Dhizuku Mode**. This allows the Shizuku+ manager itself to be set as a **Device Owner**, providing a permanent, rootless anchor for system privileges that survives reboots (on supported configurations).

### Compatibility Layer (Package Detection & Binder Delivery)
Shizuku+ installs under its own application ID (`af.shizuku.plus.api`) so it can coexist with stock Shizuku, but Shizuku-aware apps traditionally look for the fixed `moe.shizuku.privileged.api` package. Shizuku+ bridges this with two independent layers:

*   **Detection:** a bundled **Compat Hub** companion (the `compat` module, package `moe.shizuku.privileged.api`) satisfies third-party `isInstalled` checks and relays legacy `REQUEST_BINDER` broadcasts to the real manager. The alternative **drop-in** flavor installs directly as `moe.shizuku.privileged.api`.
*   **Delivery:** the service hands the privileged binder to each authorized app via a ContentProvider `sendBinder` call, carrying `BinderContainer` parcels for all three API namespaces (`af.shizuku`, `rikka.shizuku`, `moe.shizuku`). Both the compat hub and this handshake must succeed for an app to see Shizuku+ as "running."

## 2. The Plus API Ecosystem

Shizuku+ goes beyond simple shell execution by exposing stable, version-agnostic bridges to internal Android system services.

### Overlay Manager Plus (OMP)
*   **The Issue:** Android 14+ and OneUI 8+ introduced strict restrictions on the standard `OverlayManager` system service, breaking rootless theming.
*   **The Solution:** Shizuku+ uses a proprietary `OverlayManagerTransaction` bridge that allows themes (Substratum, Hex Installer) to apply overlays safely and persistently without full system root.

### AICore+ Automation Bridge
*   **Capability:** Provides a privileged `AccessibilityService` proxy.
*   **Functions:** 
    *   `dumpHierarchy()`: Exports the full UI tree as XML (faster than `uiautomator`).
    *   `performTap/Swipe()`: Simulates physical input at the kernel/hardware abstraction level.
*   **Use Case:** Enables advanced AI-driven automation (like Tasker + GPT) to interact with any app without root.

### Root Compatibility Hub (SU Bridge)
Shizuku+ acts as a translation layer for legacy apps.
*   **SU Wrapper:** Provides a `/system/bin/su` drop-in replacement that routes commands through the Shizuku binder.
*   **Module Mocking:** Can fool apps into thinking Magisk or BusyBox is installed to unlock advanced features in legacy tools.

## 3. Performance & Stability Improvements

### Transparent Shell Interceptor
Original Shizuku apps often run shell commands (e.g., `pm install`). These are slow because they fork a new process.
*   **Shizuku+ Optimization:** Intercepts these calls and routes them through direct Java/Native APIs (like `IPackageManager`) inside the existing privileged process.
*   **Result:** Up to 10x faster execution for shell-heavy apps like MacroDroid or terminal emulators.

### Service Doctor
A real-time diagnostic engine that monitors for vendor-specific "app killers" (like Samsung's Auto Blocker or Oppo's Battery Guard) and provides one-tap fixes to keep the Shizuku service alive.

### Watchdog Resilience
Three independent recovery layers, each covering a gap the others can't: an in-process crash listener for fast recovery, a periodic WorkManager backstop in case just the watchdog service dies, and an `AlarmManager`-based external re-arm that can restart everything even if an OEM freezer (e.g. Samsung One UI's "Sleeping apps") kills the *entire* app process on screen lock — the one failure mode nothing running inside that process can self-detect. See the [Service Connection page](https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/service-connection.md#watchdog).

## 4. UI/UX Refinement
*   **Material 3 Expressive:** Uses the latest M3 design language with spring animations and adaptive shapes.
*   **In-App Changelogs:** Direct access to release notes upon update.
*   **Bulk Management:** Industrial-grade permission management for power users with hundreds of apps.

---
*For documentation on integrating the Plus APIs into your own app, see the [bundled API module](https://github.com/qianyumeng0228/ShizukuPlus/tree/master/api). For a full breakdown of every permission Shizuku+ requests and why, see the [Permissions page](https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/permissions.md).*
