<div align="center">

# Shizuku+

An enhanced version of [Shizuku](https://github.com/RikkaApps/Shizuku) with quality-of-life improvements, backported optimizations, and a strategic 2026-era API.

Shizuku lets normal apps use system-level APIs directly via a privileged process started with adb or root. Shizuku+ keeps full compatibility while adding features for power users and developers.

[![Stars](https://img.shields.io/github/stars/thejaustin/ShizukuPlus?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/thejaustin/ShizukuPlus/stargazers)
[![Downloads](https://img.shields.io/github/downloads/thejaustin/ShizukuPlus/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/thejaustin/ShizukuPlus/releases)
[![Latest Release](https://img.shields.io/github/v/release/thejaustin/ShizukuPlus?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/thejaustin/ShizukuPlus/releases/latest)

</div>

## ⬇️ Download

Get the latest release from [GitHub Releases](https://github.com/thejaustin/ShizukuPlus/releases).

## ✨ Shizuku+ Core Features

*   **Universal Privilege Provider**: Combines **Root**, **ADB Shell**, and **Dhizuku (Device Owner)** into a single unified interface.
*   **Dhizuku Mode**: Share the system `DevicePolicyManager` binder with any app that has Shizuku permissions.
*   **Customizable Gestures**: Configure swipe left, swipe right, and long-press actions for any app in the management list.
*   **Bulk Management**: Multi-select apps to grant/revoke permissions or hide them in one tap.
*   **Activity Log**: Audit trail of which apps are using Shizuku and what actions they are performing.
*   **Service Doctor**: In-app diagnostic tool to troubleshoot and fix service startup issues.
*   **Quick Settings Tile**: Conveniently view and toggle the service status from your notification panel.

## 🚀 2026 Strategic API Extensions

Shizuku+ provides a foundational bridge for the next generation of Android (Android 16/17+):

*   **AVF (Virtual Machine) Manager**: Manage isolated Linux/Microdroid VMs with VirtIO-GPU acceleration.
*   **Privileged Storage Proxy**: Authenticated access to restricted paths like `/data/data/` for verified tools.
*   **Intelligence Bridge (AI Core Plus)**: Privileged NPU scheduling and screen context intelligence.
*   **Window Manager Plus**: Force free-form resizing, manage the Android 17 "Bubble Bar," and resilient overlays.
*   **Continuity Bridge**: Secure state and task handoff between ShizukuPlus-enabled devices.

## 🛠️ Backporting & Optimizations

Shizuku+ makes regular Shizuku apps faster and more compatible without any code changes:

*   **Transparent Shell Interceptor**: Intercepts common `pm`, `am`, and `settings` commands and routes them through high-performance native APIs.
*   **`plus` CLI Helper**: Adds a privileged command-line utility to the `rish` environment for advanced terminal use.
*   **Dynamic App Database**: Fetches the latest app descriptions and enhancement suggestions from GitHub to keep the UI up-to-date.

## ⚙️ Modular Control

Everything in Shizuku+ is optional. Use the **Plus Features** category in Settings to toggle:
*   Transparent Shell Interception
*   Specific API Bridges (AVF, Storage, Intelligence, etc.)
*   Home screen card visibility
*   Activity Logging

## ☑️ Requirements

**Minimum: Android 7+**
- **Root mode:** Requires a rooted device
- **Wireless Debugging mode:** Android 11+ and all Android TVs
- **PC mode:** All devices
- **Start on boot:** Available only with Wireless Debugging or Root mode

## 📱 Developer Guide

See the [ShizukuPlus-API](https://github.com/thejaustin/ShizukuPlus-API) repository for documentation on the strategic API extensions.

## 📃 License

[Apache 2.0](LICENSE)
