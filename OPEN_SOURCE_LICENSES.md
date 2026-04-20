# Open Source Licenses

Shizuku+ is built on the shoulders of many excellent open-source projects. Full credit and gratitude to every author listed below.

---

## Upstream Projects

### Shizuku
- **Author**: RikkaApps (Rikka)
- **Repository**: https://github.com/RikkaApps/Shizuku
- **License**: Apache License 2.0
- **Usage**: Foundational privileged-process architecture, API design, server and client code

### Shizuku (thedjchi fork)
- **Author**: thedjchi
- **Repository**: https://github.com/thedjchi/Shizuku
- **License**: Apache License 2.0
- **Usage**: Intermediate fork with additional quality-of-life changes that Shizuku+ builds on top of

### Dhizuku
- **Author**: iamr0s
- **Repository**: https://github.com/iamr0s/Dhizuku
- **License**: Apache License 2.0
- **Usage**: Device Owner binder sharing protocol (Dhizuku Mode)

---

## Inspiration (No Code Taken)

### aShell You
- **Author**: Muntashir Akon / Shashank
- **Repository**: https://github.com/MuntashirAkon/aShell
- **License**: GPL-3.0 (no code incorporated — inspiration only)
- **Usage**: Terminal and shell automation UX patterns that influenced Shizuku+ design.
  No source code from aShell You is present in this project.

---

## Android Jetpack (AndroidX)

All AndroidX libraries are Copyright © The Android Open Source Project, licensed under the **Apache License 2.0**.
https://developer.android.com/jetpack

| Library | Version | Usage |
|---------|---------|-------|
| `androidx.core:core-ktx` | 1.15.0 | Kotlin extensions for Android core APIs |
| `androidx.appcompat:appcompat` | 1.7.0 | Backwards-compatible app components |
| `androidx.fragment:fragment-ktx` | 1.8.7 | Fragment Kotlin extensions |
| `androidx.recyclerview:recyclerview` | 1.4.0 | Efficient list/grid views |
| `androidx.preference:preference-ktx` | 1.2.1 | Settings UI via Preference library |
| `androidx.browser:browser` | 1.8.0 | Custom Tabs for in-app browsing |
| `androidx.work:work-runtime-ktx` | 2.10.4 | Background task scheduling |
| `androidx.activity:activity-ktx` | 1.10.0 | Activity Kotlin extensions, predictive back |
| `androidx.core:core-splashscreen` | 1.0.1 | Splash screen API |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.9.0 | ViewModel Kotlin extensions |
| `androidx.lifecycle:lifecycle-livedata-ktx` | 2.9.0 | LiveData Kotlin extensions |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.0 | Lifecycle-aware coroutine scopes |
| `androidx.room:room-runtime` | 2.7.0-alpha13 | SQLite ORM — persistent data |
| `androidx.room:room-ktx` | 2.7.0-alpha13 | Room Kotlin extensions |

---

## Material Design Components

### Material Components for Android
- **Author**: Google
- **Repository**: https://github.com/material-components/material-components-android
- **Version**: 1.14.0-alpha09
- **License**: Apache License 2.0
- **Usage**: M3 Expressive dialogs, chips, navigation, text fields, and all primary UI surfaces

### Google Material Symbols
- **Source**: https://fonts.google.com/icons
- **License**: Apache License 2.0
- **Usage**: Icons throughout the Shizuku+ Manager UI

---

## Kotlin & JetBrains

All JetBrains/Kotlin libraries are licensed under the **Apache License 2.0**.
https://github.com/JetBrains/kotlin

| Library | Version | Usage |
|---------|---------|-------|
| `kotlinx-coroutines-core` | 1.10.2 | Structured concurrency |
| `kotlinx-coroutines-android` | 1.10.2 | Main-thread dispatcher |
| `kotlinx-serialization-json` | 1.8.0 | JSON serialization for API responses |

---

## RikkaX Libraries

All RikkaX libraries are authored by **Rikka (RikkaApps)**, licensed under the **Apache License 2.0**.
https://github.com/RikkaApps

| Library | Version | Usage |
|---------|---------|-------|
| `rikkax-appcompat` | 1.6.1 | Enhanced AppCompat base |
| `rikkax-compatibility` | 2.0.0 | Android version compatibility helpers |
| `rikkax-core-ktx` | 1.4.1 | Core Kotlin extensions |
| `rikkax-material` | 2.7.2 | Material-styled UI components |
| `rikkax-material-preference` | 2.0.0 | Material-styled Preference widgets |
| `rikkax-html-ktx` | 1.1.2 | HTML-to-Spannable utilities |
| `rikkax-insets` | 1.3.0 | Window insets helpers |
| `rikkax-layoutinflater` | 1.3.0 | LayoutInflater factory extensions |
| `rikkax-simplemenu-preference` | 1.0.3 | Simple menu for Preference lists |
| `rikkax-recyclerview-adapter` | 1.3.0 | RecyclerView adapter utilities |
| `rikkax-recyclerview-ktx` | 1.3.2 | RecyclerView Kotlin extensions |
| `rikkax-lifecycle-resource` | 1.0.1 | Lifecycle-aware resource LiveData |
| `rikkax-parcelablelist` | 2.0.1 | Parcelable list utilities |

### Rikka Hidden API
| Library | Version | Usage |
|---------|---------|-------|
| `dev.rikka.hidden:compat` | 4.4.0 | Hidden Android API compatibility |
| `dev.rikka.hidden:stub` | 4.4.0 | Compile-time stubs for hidden APIs |
| `dev.rikka.tools.refine:runtime` | 4.4.0 | Runtime type refinement |
| `dev.rikka.tools.refine:annotation` | 4.4.0 | Annotation processing for refine |

---

## Airbnb

### Mavericks (MvRx)
- **Repository**: https://github.com/airbnb/mavericks
- **Version**: 3.0.9
- **License**: Apache License 2.0
- **Usage**: MVI architecture — `MavericksViewModel` and `MavericksState` for home screen

### Lottie for Android
- **Repository**: https://github.com/airbnb/lottie-android
- **Version**: 6.6.2
- **License**: Apache License 2.0
- **Usage**: JSON animation playback for expressive UI transitions

---

## Square

### LeakCanary
- **Repository**: https://github.com/square/leakcanary
- **Version**: 2.15
- **License**: Apache License 2.0
- **Usage**: Memory leak detection (debug builds only)

---

## Jake Wharton

### Timber
- **Repository**: https://github.com/JakeWharton/timber
- **Version**: 5.0.1
- **License**: Apache License 2.0
- **Usage**: Structured logging throughout the application

---

## Coil

### Coil (Coroutine Image Loader)
- **Repository**: https://github.com/coil-kt/coil
- **Version**: 3.0.0-rc01
- **License**: Apache License 2.0
- **Usage**: Asynchronous image loading for app icons and remote images

---

## Koin

### Koin for Android
- **Repository**: https://github.com/InsertKoinIO/koin
- **Version**: 4.0.2
- **License**: Apache License 2.0
- **Usage**: Dependency injection

---

## Sentry

### Sentry Android SDK
- **Repository**: https://github.com/getsentry/sentry-java
- **Version**: 8.36.0
- **License**: MIT License
- **Usage**: Crash reporting and performance monitoring (`sentry-android-core`, `sentry-android-fragment`, `sentry-android-timber`, `sentry-okhttp`)

---

## mikepenz

### AboutLibraries
- **Repository**: https://github.com/mikepenz/AboutLibraries
- **Version**: 11.6.3
- **License**: Apache License 2.0
- **Usage**: Open source library metadata generation (Gradle plugin + core)

---

## TopJohnWu

### libsu
- **Repository**: https://github.com/topjohnwu/libsu
- **Version**: 6.0.0
- **License**: Apache License 2.0
- **Usage**: Root shell management and command execution

---

## LSPosed

### AndroidHiddenApiBypass
- **Repository**: https://github.com/LSPosed/AndroidHiddenApiBypass
- **Version**: 6.1
- **License**: Apache License 2.0
- **Usage**: Bypass Android hidden API restrictions on API 28+

### libcxx
- **Repository**: https://github.com/lsposed/libcxx
- **Version**: 27.0.12077973
- **License**: Apache License 2.0 with LLVM Exception
- **Usage**: C++ standard library for native code

---

## Bouncy Castle

### bcpkix-jdk18on
- **Repository**: https://www.bouncycastle.org/
- **Version**: 1.81
- **License**: MIT License (Bouncy Castle variant)
- **Usage**: Cryptographic operations (key generation, certificate handling for ADB pairing)

---

## vvb2060

### BoringSSL (Android NDK packaging)
- **Repository**: https://github.com/vvb2060/ndk-boringssl
- **Version**: 20250114
- **License**: Apache License 2.0 / ISC / OpenSSL License
- **Usage**: TLS/SSL implementation for ADB wireless debugging

---

## Zhanghai (Zhang Hai)

### AppIconLoader
- **Repository**: https://github.com/zhanghai/AppIconLoader
- **Version**: 1.5.0
- **License**: Apache License 2.0
- **Usage**: Loads and renders adaptive app icons with proper masking

---

## Google

### Gson
- **Repository**: https://github.com/google/gson
- **Version**: 2.13.1
- **License**: Apache License 2.0
- **Usage**: JSON serialization/deserialization in server-side modules

---

## License Texts

Full license texts are available at:

- **Apache License 2.0**: https://www.apache.org/licenses/LICENSE-2.0
- **MIT License**: https://opensource.org/licenses/MIT
- **LLVM Exception**: https://spdx.org/licenses/LLVM-exception.html

The Shizuku+ source code itself is licensed under the [Apache License 2.0](LICENSE).
