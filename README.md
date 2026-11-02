<div align="center">
[[[ UPDATE ]]]

Stable Release is heading your way... soon... have had some more issues creep up on me that I want to fix firsthand.
Pre-releases are back in the meantime.

I've been in the process of simplifying, restructuring, and improving the code quality, and visual quality of the app locally. 

Releases have been, and will be less frequent as I'm doing more local testing before pushing releases.
Pre-releases will return for simple things like bugs, crashes, issues and such. 

I'm creating more checkpoints for myself and this project as I shift my focus to stability and improvement in visual quality & user experience ahead of new feature implementation.

It's time to truly stabilize things instead of simply moving forward with development at lightspeed
Thank you for your patience, and much love to you all!


# Shizuku+

The advanced privileged-process manager for Android.

Shizuku+ is an enhanced version of [Shizuku](https://github.com/RikkaApps/Shizuku) built on top of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), with quality-of-life improvements, backported optimizations, exclusive Plus APIs, M3E theming & TCP connectivity improvements, and more. 

Shizuku lets normal apps use system-level APIs directly via a privileged process started with adb or root. Shizuku+ keeps full compatibility while adding features for power users and developers through the API and in-app with advanced settings and experimental developments.

✨ Check out everything else this fork offers in the feature section further below!

[![Stars](https://img.shields.io/github/stars/thejaustin/ShizukuPlus?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/thejaustin/ShizukuPlus/stargazers)
[![Downloads](https://img.shields.io/github/downloads/thejaustin/ShizukuPlus/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/thejaustin/ShizukuPlus/releases)
[![Latest Release](https://img.shields.io/github/v/release/thejaustin/ShizukuPlus?include_prereleases&sort=date&style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/thejaustin/ShizukuPlus/releases)

**🌐 Languages:** **English** · [Simplified Chinese](README.zh-CN.md)

</div>

> **Contributors welcome!** If you've found a bug or want to improve the codebase, please open an issue or pull request — the project is actively looking for contributors and collaborators.

## ⬇️ Download

Get the latest release from [GitHub Releases](https://github.com/thejaustin/ShizukuPlus/releases) — see the release notes there for what's changed recently.

## ✨ Shizuku+ Core Features

*   **Universal Privilege Provider**: One interface for **Root**, **ADB Shell**, and **Dhizuku (Device Owner)**.
*   **OneUI 8+ Theming Fix**: Lets theming engines like Hex Installer or Substratum keep working on Android 16/17 and OneUI 8+.
*   **Dhizuku Mode**: Share the system Device Owner binder with any app that has Shizuku permissions — set up via ADB, no root needed.
*   **Customizable Gestures**: Swipe left, swipe right, and long-press actions, configurable per app.
*   **In-App Changelogs**: See what's new after an update without leaving the app.
*   **Bulk Management**: Multi-select apps to grant/revoke permissions or hide them in one tap.
*   **Per-app API scopes**: Limit what each authorized app can do (shell, package manager, activity manager, app ops, system settings). An app with a scope off can't get around it with an interactive shell or its own background services.
*   **Quick safety check** (optional, off by default): When an app asks for access, see where it was installed from, when, and its signing certificate. Add your own VirusTotal or Pithus key to also see whether scanners flag that exact APK; only its hash is looked up.
*   **Safe by default for other apps**: Shizuku+'s riskier APIs for other apps (other apps' private data, SMS, contacts, location, screenshots, input, reboot) stay off until you turn them on in **Developer options → Unrestricted access**.
*   **App list sorting up front**: A sort chip leads the filter row: name, last installed, last updated or granted first, and tap again to reverse.
*   **In-App Knowledgebase**: Clean, offline-first Material 3 peek dialogs with rich guides, copyable command snippets, and direct action buttons without navigating away.
*   **Activity Log with Morphe Timeline**: Visual node rails with dynamic pulsing status halos, repetitive activity occurrence grouping, and multi-criteria sorting.
*   **Root Compatibility Hub & Enhanced SU Bridge**: Seamless translation of legacy `su -c` commands into Shizuku IPC with POSIX argument parsing and Android 14–17 support.
*   **Universal SU Automation**: One-tap 'Magic Setup' to configure installed root apps to use the Shizuku+ SU Bridge.
*   **Adaptive Service Doctor**: Diagnoses and fixes service startup, Samsung Auto Blocker (read from its real settings now) and OEM battery restrictions. Its background self-healing has its own switches, including whether it may turn the Pairing Helper back on and whether to tell you about fixes that failed.
*   **Helper notifications**: Problems show up in one place. With the Watchdog on, its notification says whether Shizuku+ is running and offers a fix button when something's wrong; without it, one quiet notification updates in place. You pick how much you hear (off, only when it stops or needs you, or every problem), and nothing pops up over the app you're using.
*   **Expressive morphing icons**: Setting icons morph from an "off" shape into your chosen shape when you switch them on, with Spin, Morph, Pop, Sweep or Liquid motion. There are Material's shapes plus the original hand-built "classic" ones, and experimental switch designs where the icon itself is the switch.
*   **Integrated Feature Guides & Smart Tooltips**: Long-press any setting for contextual explanations and direct links to the relevant knowledgebase guide.
*   **Quick Settings Tile**: Check and toggle the service status from your notification panel.

<details>
<summary>🚀 Plus API Features</summary>

Shizuku+ provides exclusive system interfaces for advanced automation and tools — none of these exist in stock Shizuku:

*   **AICore+ Automation Bridge**: Privileged UI automation (hierarchy dumps, tap/swipe) for AI-driven tools — no root needed. ([added](https://github.com/thejaustin/ShizukuPlus/commit/e9bd1187))
*   **AVF (Virtual Machine) Manager**: Create, start and stop AVF virtual machines, on devices whose firmware supports AVF. ([added](https://github.com/thejaustin/ShizukuPlus/commit/c8e962f6))
*   **Privileged Storage Proxy**: Fast access to shared storage and read-only access to installed APKs for backup and file tools. Other apps' private data (`/data/data/`, `/Android/data/`, `/Android/obb/`) is off by default; turn on **Developer options → Unrestricted app data access** for tools like Neo Backup. `/data/data/` also needs root. ([added](https://github.com/thejaustin/ShizukuPlus/commit/c8e962f6))
*   **Device Spoofing** (*Spoof Device Identity* in Settings): Present a different device identity to bypass device-specific restrictions. ([added](https://github.com/thejaustin/ShizukuPlus/commit/11867f44))
*   **Intelligence Bridge** (*AI Core Plus*): Screen context for AI tools (pixel colors, layer captures, system context). NPU scheduling isn't offered: there's no public API for it, so the call says "not supported" instead of pretending ([#633](https://github.com/thejaustin/ShizukuPlus/issues/633)). ([added](https://github.com/thejaustin/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Plus**: Force free-form resizing, manage the Bubble Bar, and resilient overlays. ([added](https://github.com/thejaustin/ShizukuPlus/commit/e9bd1187))
*   **System Theming Bridge** (*Overlay Manager Plus*): Privileged overlay management for rootless theming (e.g. Hex Installer). ([added](https://github.com/thejaustin/ShizukuPlus/commit/55f6b7c7))
*   **Network & DNS Governor**: Manage Private DNS and firewall routing for rootless ad-blockers. ([added](https://github.com/thejaustin/ShizukuPlus/commit/55f6b7c7))
*   **Deep Process Control** (*Activity Manager Plus*): Lets process managers kill apps and set standby buckets more aggressively. ([added](https://github.com/thejaustin/ShizukuPlus/commit/55f6b7c7))
*   **Continuity Bridge**: Secure state and task handoff between Shizuku+ devices. ([added](https://github.com/thejaustin/ShizukuPlus/commit/20cf14f7))

See the [Shizuku+-API](https://github.com/thejaustin/ShizukuPlus-API) repository for full documentation and client bindings.

</details>

<details>
<summary>🛠️ Backporting & Optimizations</summary>

Shizuku+ makes regular Shizuku apps faster and more compatible without any code changes:

*   **Transparent Shell Interceptor**: Routes common `pm`, `am`, and `settings` commands through faster native APIs.
*   **Local ADB Proxy**: Emulates an ADB server on port 15555, so legacy apps can use Shizuku without Wireless ADB staying on.
*   **SU Bridge**: A Shizuku-backed `su` drop-in for non-rooted apps that support a custom root path. Common root commands are translated into real framework operations where shell UID allows it — `iptables --uid-owner` per-app blocks become live `NetworkPolicy` restrictions, `resetprop` reads/writes real system properties, and `chmod`/`chown` apply for real — falling back to mocked success only when an operation genuinely requires root (e.g. flashing partitions or loading kernel modules).
*   **`plus` CLI Helper**: A privileged command-line utility, available inside `rish`.
*   **Dynamic App Database**: Keeps app descriptions and suggestions in the UI up-to-date from GitHub.

</details>

## ⚙️ Modular Control

Everything in Shizuku+ is optional. Use the **Plus Features** category in Settings to toggle:
*   Transparent Shell Interception
*   Individual Plus APIs (AVF, Storage, Intelligence, etc.)
*   Home screen card visibility
*   Activity Logging

## 🔌 Third-Party App Compatibility

Shizuku+ installs under its own package (`af.shizuku.plus.api`) so it can coexist with stock Shizuku. Because most Shizuku-aware apps look specifically for the `moe.shizuku.privileged.api` package, Shizuku+ ships a lightweight **Compat Hub** — a tiny companion app that registers that package name and forwards binder/permission requests to Shizuku+.

**If third-party apps don't detect Shizuku+:**
1. Start the Shizuku+ service (ADB or root).
2. On the home screen, use the **Compat Hub** card to install the companion (it's bundled in the app; installation goes through the running service, so start the service first).
3. Re-open the third-party app — it should now detect Shizuku and receive the service binder.

Alternatively, install the **drop-in** build, which registers as `moe.shizuku.privileged.api` directly (do not install it alongside stock Shizuku).

## 🔒 Privacy & Backups

Nothing leaves your device except update checks, a public app-compatibility list from this repo and, only if you opt in, crash reports (with addresses and other apps' commands stripped) and the safety check's VirusTotal or Pithus hash lookups. Backups never carry your ADB key or the list of apps you allowed, and the risky APIs for other apps are off until you turn them on. The details, with links to the code, are in [docs/PRIVACY.md](docs/PRIVACY.md).

## ☑️ Requirements

**Minimum: Android 7+ · Fully supported through Android 17 (SDK 37)**
- **Root mode:** Requires a rooted device
- **Wireless Debugging mode:** Android 11+ and all Android TVs
- **PC mode:** All devices
- **Start on boot:** Available only with Wireless Debugging or Root mode

On **Android 16+**, Shizuku+ requests the new Local Network Protection permissions so wireless-debugging discovery and pairing keep working; on **Android 17**, it transparently handles the hidden-API `deviceId` change so authorized apps still appear and permission grants still apply.

## 📃 License

[Apache 2.0](LICENSE)

---

<details>
<summary>🙏 Acknowledgements & Upstream Projects</summary>

Shizuku+ is a community-driven enhancement and fork of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), which is itself a fork of the original [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku). This project is not affiliated with the original RikkaApps team.

Thanks to the following upstream contributors and projects whose work makes Shizuku+ possible:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — For the foundational Shizuku project and its elegant API design.
- **[thedjchi](https://github.com/thedjchi)** — For the intermediate fork and quality-of-life improvements, and for carrying the **Android 17 (SDK 37) compatibility** work that Shizuku+'s A17 support is adapted from.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — Origin of the Android 17 hidden-API `deviceId` compatibility approach (the `Android17Compat` / `InstalledPackagesCompat` reflection layer) and Local Network Protection handling that this fork's A17 support descends from.
- **[LandonMoran](https://github.com/LandonMoran)** — For porting Nightzuku's Android 17 support into the thedjchi fork and **verifying it end-to-end on a physical Android 17 device** (pairing, service start, and the authorized-apps list), which is the field validation Shizuku+'s port builds on.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — For the aShell You codebase, which inspired the terminal and shell automation features.
- **[iamr0s](https://github.com/iamr0s)** — For Dhizuku, enabling the unified Device Owner privilege mode, and AndroidAppProcess for standalone Java process execution.
- **[pascua28](https://github.com/pascua28)** — For native Samsung System UID 1000 escalation integration.
- **[kerneldroid](https://github.com/kerneldroid)** — For the Nightzuku fork, which inspired our Android 16/17 (SDK 37) hidden API resilience (handling `deviceId`) and UI modernizations.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — For conceptualizing systemic UID 1000 privilege escalation beyond standard limits.

### Upstream Projects

| Project | Author | License | Role |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Foundational privileged-process architecture |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Intermediate fork with QoL improvements; carried the Android 17 compat work Shizuku+ adapted |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Origin of the Android 17 hidden-API `deviceId` + Local Network Protection compatibility approach |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Samsung UID 1000 system execution exploit |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API resilience & UI modernizations |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt Team | Apache 2.0 | System UID privilege escalation concepts |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder sharing (Dhizuku Mode) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Standalone high-privileged Java process wrapper |

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
| [SQLite (C Recovery API / CLI)](https://sqlite.org/) | D. Richard Hipp / SQLite Consortium | Public Domain |

Full license texts and per-library details: [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) | [NOTICE](NOTICE)

</details>

<details>
<summary>👥 Contributors</summary>

**Code Contributors**

| Contributor | Contribution |
|-------------|--------------|
| [thejaustin](https://github.com/thejaustin) | Project founder & primary maintainer — all core Plus features, UI/UX, and infrastructure |
| [thedjchi](https://github.com/thedjchi) | Intermediate fork base; Android 17 (SDK 37) compatibility groundwork |
| [Kevin Doremy](https://github.com/doremylover) | Dead code removal, unused import cleanup, layout & class refactoring |
| [Ryfter](https://github.com/Ryfter) | mDNS timeout improvements, FGS subtype refactoring, notification UX |
| [vvb2060](https://github.com/vvb2060) | AGP build system updates, LTO optimization, license clarifications |
| [Haruue Icymoon](https://github.com/haruue) | Documentation & README improvements |
| [djbclark](https://github.com/djbclark) | Fork-migration guide (`FORK_MIGRATION.md`); stock-API transaction-code, `onTransact` logging, locked-device ADB foreground-service, CI release-notes and bug-report link fixes (#572–#582) |
| [Enursha](https://github.com/Enursha) | BinderContainer keep-rules and binder delivery to stock clients; manager "not running" status fix (#307, #308) |

**Translation Contributors**

| Language | Contributor |
|----------|-------------|
| Azerbaijani | [Figim](https://github.com/Figim) |
| Brazilian Portuguese | [odorizzioficial](https://github.com/odorizzioficial), [ViictorNunes](https://github.com/ViictorNunes) |
| Simplified Chinese | [Yizutt](https://github.com/Yizutt), [JeffioZ](https://github.com/JeffioZ), [qianyumeng0228](https://github.com/qianyumeng0228) |
| Ukrainian | [insabon](https://github.com/insabon), [AmakerGame](https://github.com/AmakerGame) |
| Urdu | [hamzaharoon1314](https://github.com/hamzaharoon1314) |
| French | [Ryfter](https://github.com/Ryfter), [T. Clement](https://github.com/thibaultclement) |
| Vietnamese | [ThePrimalPea](https://github.com/ThePrimalPea) |
| Filipino | [IverCoder](https://github.com/IverCoder) |
| Italian | [Dany-coder778](https://github.com/Dany-coder778) |
| Japanese | [MES-mitutti](https://github.com/MES-mitutti) |

*Plus many more via community translation through Weblate — thank you to all translators!*

> **Machine-assisted drafts:** where a language was still incomplete, missing strings were filled with AI-assisted drafts in `strings_machine_translated.xml` and are **flagged for native review**. If you speak the language, corrections are very welcome — see [TRANSLATING.md](TRANSLATING.md).

### Acknowledgments
- Special thanks to **AkayamiShurui42** for the proactive security research and stability patches (Reference: #239).
- Thank you to **AlexeiCrystal** for identifying MIUI crash bugs and suggesting the Compat Hub workaround for legacy apps (#241, #242).
- Thank you to **ddnexus** and **kai-bash** for highlighting the Device Owner factory reset trap and Google Backup conflicts (#237).
- Thank you to **Kevinco1** for feedback on root compat app detection issues (#243).
- Thank you to **aragortsantiago6-beep** and **Scoop2389** (Pixel 9a) and **ConversionRituals** (Xiaomi) for on-device Android 16/17 testing, crash reports, and logs that drove the SDK 37 hidden-API and Local Network Protection compatibility fixes (#317, #323).
- Thank you to **gmm96** for extensive multi-round logcat debugging across several builds that pinned down the Cached Apps Freezer binder-delivery bug (#371).
- Thank you to **bilemke** for isolating the stock-API regression that led to removing the legacy binder proxy (#584), and to everyone who filed crash reports and tested builds recently.
- Thank you to **[odorizzioficial](https://github.com/odorizzioficial)** for the complete Brazilian Portuguese translation (#409), and for the detailed report on the Samsung "Sleeping apps" Watchdog freeze (#415).

</details>
