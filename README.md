<div align="center">

# Shizuku+

An enhanced version of [Shizuku](https://github.com/RikkaApps/Shizuku) built on top of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), with quality-of-life improvements, backported optimizations, and exclusive Plus APIs.

Shizuku lets normal apps use system-level APIs directly via a privileged process started with adb or root. Shizuku+ keeps full compatibility while adding features for power users and developers.

[![Stars](https://img.shields.io/github/stars/thejaustin/ShizukuPlus?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/thejaustin/ShizukuPlus/stargazers)
[![Downloads](https://img.shields.io/github/downloads/thejaustin/ShizukuPlus/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/thejaustin/ShizukuPlus/releases)
[![Latest Release](https://img.shields.io/github/v/release/thejaustin/ShizukuPlus?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/thejaustin/ShizukuPlus/releases/latest)

</div>

> [!IMPORTANT]
> **Sentry Status Notice**: Crash reporting via Sentry is currently disabled due to monthly quota limits. Service will resume on May 1st, 2026. In the meantime, please report any bugs manually via [GitHub Issue #190](https://github.com/thejaustin/ShizukuPlus/issues/190).

## ⬇️ Download

Get the latest release from [GitHub Releases](https://github.com/thejaustin/ShizukuPlus/releases).

## ✨ Shizuku+ Core Features

*   **Universal Privilege Provider**: Combines **Root**, **ADB Shell**, and **Dhizuku (Device Owner)** into a single unified interface.
*   **OneUI 8+ Theming Fix**: Provides the necessary **Overlay Manager Plus** bridge (using stable **OverlayManagerTransaction** on Android 14+) to allow engines like Hex Installer or Substratum to function on Android 16/17 and OneUI 8+.
*   **Dhizuku Mode (Integrated Device Owner)**: Share the system `DevicePolicyManager` binder with any app that has Shizuku permissions. Shizuku+ can now be set as a **Device Owner** via ADB, providing a unified rootless management platform.
*   **Customizable Gestures**: Configure swipe left, swipe right, and long-press actions for any app in the management list.
*   **In-App Changelogs**: Instantly view what's new after an update without leaving the app.
*   **Bulk Management**: Multi-select apps to grant/revoke permissions or hide them in one tap.
*   **Activity Log**: Audit trail of API calls and `su` bridge commands, complete with app icons and real-time dispatch.
*   **Root Compatibility Hub**: Dedicated dashboard to configure and manage legacy root apps with **Granular Module Control** (AdAway, Magisk Mocking, Auto-Grant, etc.).
*   **Universal SU Automation**: One-tap 'Magic Setup' to configure all installed root apps to use the Shizuku+ SU Bridge.
*   **Service Doctor**: In-app diagnostic tool to troubleshoot and fix service startup issues (now optimized for Samsung Auto Blocker on S22 Ultra).
*   **Integrated Feature Guides**: Every "Plus" feature now includes a dedicated **Information Icon** and detailed technical "About" guide to help users master advanced integrations.
*   **Quick Settings Tile**: Conveniently view and toggle the service status from your notification panel.

## 🚀 Plus API Features

Shizuku+ provides exclusive system interfaces for advanced automation and tools:

*   **AICore+ Automation Bridge**: A privileged `AccessibilityService` proxy for AI-driven automation. Supports XML UI hierarchy dumping and physical input simulation (tap/swipe) without requiring root.
*   **AVF (Virtual Machine) Manager**: Manage isolated Linux/Microdroid VMs with VirtIO-GPU acceleration.
*   **Privileged Storage Proxy**: Authenticated access to restricted paths like `/data/data/` or `/data/app/` for backups and file management.
*   **Device Spoofing (Identity Bridge)**: Project hardware identities of modern flagships (Pixel 9 Pro XL, S24 Ultra, etc.) to bypass device-specific restrictions.
*   **Intelligence Bridge (AI Core Plus)**: Privileged NPU scheduling and screen context intelligence.
*   **Window Manager Plus**: Force free-form resizing, manage the system "Bubble Bar," and resilient overlays.
*   **System Theming Bridge (Overlay Manager Plus)**: Expose privileged overlay management for rootless theming (like Hex Installer).
*   **Network & DNS Governor**: Manage Private DNS and iptables routing for rootless ad-blockers and firewalls.
*   **Deep Process Control (Activity Manager Plus)**: Allow advanced process managers to deeply kill apps and set standby buckets.
*   **Continuity Bridge**: Secure state and task handoff between Shizuku+-enabled devices.

## 🛠️ Backporting & Optimizations

Shizuku+ makes regular Shizuku apps faster and more compatible without any code changes:

*   **Transparent Shell Interceptor**: Intercepts common `pm`, `am`, and `settings` commands and routes them through high-performance native APIs.
*   **Legacy Compatibility Bridges**:
    *   **Local ADB Proxy**: Emulates an ADB server on port 15555, allowing legacy apps to use Shizuku privileges without keeping the system Wireless ADB enabled.
    *   **SU Bridge (su wrapper)**: A Shizuku-backed `su` binary drop-in replacement for non-rooted apps that support custom root paths.
*   **`plus` CLI Helper**: Adds a privileged command-line utility to the `rish` environment for advanced terminal use.
*   **Dynamic App Database**: Fetches the latest app descriptions and enhancement suggestions from GitHub to keep the UI up-to-date.

## ⚙️ Modular Control

Everything in Shizuku+ is optional. Use the **Plus Features** category in Settings to toggle:
*   Transparent Shell Interception
*   Individual Plus APIs (AVF, Storage, Intelligence, etc.)
*   Home screen card visibility
*   Activity Logging

## ☑️ Requirements

**Minimum: Android 7+**
- **Root mode:** Requires a rooted device
- **Wireless Debugging mode:** Android 11+ and all Android TVs
- **PC mode:** All devices
- **Start on boot:** Available only with Wireless Debugging or Root mode

## 📱 Developer Guide

See the [Shizuku+-API](https://github.com/thejaustin/ShizukuPlus-API) repository for documentation on the exclusive Plus APIs.

## 🔨 Building from Source

To build Shizuku+ locally, ensure your environment meets the following requirements:
*   **JDK 21** (or compatible up to Java 24; Gradle 8.14 does not support Java 25 yet).
*   **Android SDK** with CMake `3.31.0` installed via SDK Manager.

Clone the repository and initialize the submodules before building:
```bash
git clone https://github.com/thejaustin/ShizukuPlus.git
cd ShizukuPlus
git submodule update --init --recursive
./gradlew :manager:assembleDebug
```

## 🙏 Acknowledgements & Licenses

Shizuku+ is a community-driven enhancement and fork of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), which is itself a fork of the original [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku). This project is not affiliated with the original RikkaApps team.

Thanks to the following upstream contributors and projects whose work makes Shizuku+ possible:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — For the foundational Shizuku project and its elegant API design.
- **[thedjchi](https://github.com/thedjchi)** — For the intermediate fork and initial quality-of-life improvements.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — For the aShell You codebase, which inspired the terminal and shell automation features.
- **[iamr0s](https://github.com/iamr0s)** — For Dhizuku, enabling the unified Device Owner privilege mode.

### Upstream Projects

| Project | Author | License | Role |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Foundational privileged-process architecture |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Intermediate fork with QoL improvements |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder sharing (Dhizuku Mode) |

### Open Source Libraries

| Library | Author | License |
|---------|--------|---------|
| [AndroidX Jetpack](https://developer.android.com/jetpack) | Google / AOSP | Apache 2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | Google | Apache 2.0 |
| [Material Symbols](https://fonts.google.com/icons) | Google | Apache 2.0 |
| [Kotlin / Coroutines / Serialization](https://github.com/JetBrains/kotlin) | JetBrains | Apache 2.0 |
| [RikkaX Libraries](https://github.com/RikkaApps) (appcompat, material, insets, html, recyclerview, preference, lifecycle, parcelablelist) | Rikka | Apache 2.0 |
| [Hidden API / Refine](https://github.com/RikkaApps/HiddenApiCompat) | Rikka | Apache 2.0 |
| [Mavericks (MvRx)](https://github.com/airbnb/mavericks) | Airbnb | Apache 2.0 |
| [Lottie](https://github.com/airbnb/lottie-android) | Airbnb | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Coil Contributors | Apache 2.0 |
| [Koin](https://github.com/InsertKoinIO/koin) | Koin Contributors | Apache 2.0 |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Apache 2.0 |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | LSPosed | Apache 2.0 |
| [libcxx](https://github.com/lsposed/libcxx) | LSPosed / LLVM | Apache 2.0 + LLVM Exception |
| [AppIconLoader](https://github.com/zhanghai/AppIconLoader) | Zhang Hai | Apache 2.0 |
| [BoringSSL (NDK)](https://github.com/vvb2060/ndk-boringssl) | vvb2060 / Google | Apache 2.0 / ISC |
| [Gson](https://github.com/google/gson) | Google | Apache 2.0 |
| [LeakCanary](https://github.com/square/leakcanary) | Square | Apache 2.0 |
| [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | Mike Penz | Apache 2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) | Legion of Bouncy Castle | MIT |
| [Sentry Android SDK](https://github.com/getsentry/sentry-java) | Sentry | MIT |

Full license texts and per-library details: [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) | [NOTICE](NOTICE)

## 📃 License

[Apache 2.0](LICENSE)
