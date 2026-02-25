<div align="center">

# Shizuku+

An enhanced fork of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), which is itself a fork of [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

Shizuku lets normal apps use system-level APIs directly via a privileged process started with adb or root. Shizuku+ keeps full compatibility while adding quality-of-life improvements to the app management screen.

[![Stars](https://img.shields.io/github/stars/thejaustin/ShizukuPlus?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/thejaustin/ShizukuPlus/stargazers)
[![Downloads](https://img.shields.io/github/downloads/thejaustin/ShizukuPlus/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/thejaustin/ShizukuPlus/releases)
[![Latest Release](https://img.shields.io/github/v/release/thejaustin/ShizukuPlus?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/thejaustin/ShizukuPlus/releases/latest)

</div>

## ⚠️ Disclaimer

This is a **fork of a fork**. For the original Shizuku visit [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku). For the intermediate fork this is based on visit [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku).

## ⬇️ Download

Get the latest release from [GitHub Releases](https://github.com/thejaustin/ShizukuPlus/releases).

## ✨ Shizuku+ additions

New in this fork on top of thedjchi's version:

*   **Universal Privilege Provider**: Combines **Root**, **ADB Shell**, and **Dhizuku (Device Owner)** into a single unified interface.
*   **Dhizuku Mode**: Share the system `DevicePolicyManager` binder with any app that has Shizuku permissions. No more account wipes or complex ADB setup for Dhizuku.
*   **Enhanced Shizuku API**: Provides synchronous wrappers for high-level operations like Package Management, Overlay (RRO) management, and System Settings.
*   **Dynamic App Management UI**: 
    *   **Requires Plus** notice: Automatically detects and flags apps that leverage the new ShizukuPlus features.
    *   **Safe Toggling**: Gray out and disable apps when the Enhanced API is required but turned off in settings, preventing crashes.
*   **Swipe right** on any app in the management list to launch it (green reveal with play icon)
*   **Swipe left** on any app to open its Android system settings page (blue reveal with info icon)
*   **Long-press** an app for a context menu — Open app · App info · Grant/Revoke permission · Hide from list
*   **Tap the package name** on any row to copy it to the clipboard
*   **Search bar** filters the list by app name or package name in real time
*   **Filter chips** — show All, Granted, or Denied apps
*   **Sort** — Name (A–Z), Last installed, or Last updated
*   **Hide from list** — hide apps you don't want to see; an Undo snackbar lets you reverse it
*   **Staggered slide-in animation** when the list first loads
*   **First-run swipe hint** — an animated card demonstrates swipe gestures on first launch
*   **Customisable long-press actions** in Settings — disable any action you don't need; if only one remains enabled it triggers directly with no menu shown
*   **Continuous Deployment**: Fully automated CI/CD pipeline that builds and releases signed APKs for every commit to the `master` branch.

## ✨ Features from thedjchi/Shizuku

* **More robust "start on boot":** waits for a Wi-Fi connection before starting the Shizuku service
* **TCP mode:** once Shizuku starts with Wi-Fi after a reboot, you can stop/restart it without Wi-Fi
* **Watchdog service:** automatically restarts Shizuku if it stops unexpectedly
* **Start/stop intents:** toggle Shizuku on-demand using automation apps (e.g. Tasker, MacroDroid)
* **[BETA] Stealth mode:** hide Shizuku from other apps that don't work when Shizuku is installed
* **[BETA] In-app updates:** automatically check for and install new versions from GitHub
* **Android/Google TV and VR headset support:** D-Pad compatible UI, Android 14+ TV pairing support
* **MediaTek support:** fixes a critical bug in v13.6.0 that prevented Shizuku from working on MediaTek devices

## ☑️ Requirements

**Minimum: Android 7+**
- **Root mode:** Requires a rooted device
- **Wireless Debugging mode:** Android 11+ and all Android TVs
- **PC mode:** All devices
- **Start on boot:** Available only with Wireless Debugging or Root mode

## 🔒 Privacy

* No tracking, analytics, or telemetry
* No proprietary libraries or Google Play Services
* Open-source — Apache 2.0
* Internet used only for wireless debugging connections and GitHub update checks

## 🌎 Translations

Contribute via the upstream [Crowdin project](https://crowdin.com/project/shizuku).

## 📱 Developer Guide

See the [ShizukuPlus-API](https://github.com/thejaustin/ShizukuPlus-API) repository for the Enhanced API and documentation.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b branch-name`)
3. Commit your changes and open a Pull Request

### Building

```
git clone --recurse-submodules https://github.com/thejaustin/ShizukuPlus
```

Run gradle task `:manager:assembleDebug` or `:manager:assembleRelease`.

## 📃 License

[Apache 2.0](LICENSE)
