# Privacy, backups and data

ShizukuPlus is a fork of Shizuku, and a lot of it was written with AI tools (see [AI_ATTRIBUTIONS.md](../AI_ATTRIBUTIONS.md)). That's a fair reason to be careful with it. This page says exactly what the app keeps, what leaves your device, and what a backup can and can't bring back, so you can check it instead of taking our word for it. Every claim links to the code that backs it.

## What leaves your device

| What | When | Where it goes |
|---|---|---|
| Crash reports | Only after you say yes to the first-run prompt or turn on **Settings → Advanced → Crash reporting**. Off until then. | Sentry |
| Update check | When the app checks for updates | GitHub (`api.github.com`, `github.com`) |
| App compatibility list | Periodic download of a public JSON file | GitHub (`raw.githubusercontent.com`) |
| App safety check | Only if you turn on **VirusTotal check** or **Pithus check** and add your own API key. When an app asks for Shizuku+ access, the SHA-256 hash of its APK is looked up. The APK itself is never uploaded. | VirusTotal (`virustotal.com`), Pithus (`beta.pithus.org`) |
| Settings backup | Only if Android backup is on for your device | Your Google account (Android Backup), or the new phone during device transfer |

Nothing else is sent anywhere. There are no analytics or ads, and no account.

### Crash reports
When crash reporting is on, Sentry also counts app sessions, for a crash-free rate. A report has the error, the app version, your Android version and device model, and recent app log lines. Before anything is sent:
- IP addresses, Wi-Fi network names, emails and similar are stripped from log lines ([`TelemetryScrubber`](../manager/src/main/java/af/shizuku/manager/utils/TelemetryScrubber.kt)).
- Warnings from the Shizuku+ service have other apps' commands, package names and file paths removed.
- No screenshots, no view hierarchy, and no default personal info (`isSendDefaultPii = false`). See [`ShizukuApplication.kt`](../manager/src/main/java/af/shizuku/manager/ShizukuApplication.kt).
- Pairing breadcrumbs don't include your device's address or port.
- **Manual crash reports** (Developer Options → Manual Crash Report) go wherever you send them, often a public GitHub issue. Other apps' package names are replaced with "app 1", "app 2", and addresses, network names and emails are stripped. Read the report before posting it.

Turning it off stops all sending right away. Your choice isn't backed up, so a new device asks again.

### App safety check
Off by default (Settings → App safety check). When turned on, a Shizuku+ access request shows:
- where the app was installed from (a store, an APK file, another app, or unknown);
- when it was installed;
- the start of its signing certificate.

All of that is read on the device. If you turn on VirusTotal or Pithus and add a key, it also shows what those services know about that exact APK. Only its hash is sent, and Pithus gets your key only for APKs it already has a report on. "Hasn't seen this app" means unknown, not safe. See [`AppSafetyCheck.kt`](../manager/src/main/java/af/shizuku/manager/authorization/AppSafetyCheck.kt).

### API keys
VirusTotal and Pithus API keys you enter are stored on the device only. They're never in cloud backups, plain exports or the reinstall copy (see below).

## What a settings backup contains

Shizuku+ has four ways to carry settings over:

| Backup | Encrypted | Includes API keys, trusted Wi-Fi names and hidden apps |
|---|---|---|
| **Encrypted export** (Settings → Advanced → Back up settings) | Yes, with a key tied to this install | Yes |
| **Plain export** | No | No |
| **Android Backup / device transfer** | By Android | No |
| **Reinstall copy** (used by in-app updates) | No | No |

The encrypted export can only be restored on the same install. After a reinstall or clearing app data, use a plain export.

### Never backed up, in any form
- **Which apps you allowed.** Each app asks again the first time it needs Shizuku+.
- **Shizuku+'s ADB key** (the one your device trusts for USB debugging). Holding it would give anyone an authorized ADB shell. After a restore you approve the "Allow USB debugging" prompt once.
- **Per-device consent:** crash reporting and the accessibility service. You're asked again on each device.
- **Runtime state:** caches, update-check times, one-time migration flags, the server's auth token.

The full list is `EXCLUDED_KEYS` and `SECRET_KEYS` in [`SettingsBackupManager.kt`](../manager/src/main/java/af/shizuku/manager/utils/SettingsBackupManager.kt).

### Restored, but held off until you confirm
Settings that widen what Shizuku+ or other apps can do aren't switched on by a restore on their own. After restoring, the app lists any the backup had on and asks before turning them on. These include:
- the SU Bridge and root emulation features, and root auto-grant;
- TCP mode, stealth mode, Shadow Binder and Dhizuku mode;
- the **Unrestricted access** developer options.

Turning one *off* is always applied. The list is `PRIVILEGED_KEYS` in the same file.

After every restore the app also shows what wasn't brought back, so nothing goes missing silently.

### What Android Backup does not include
Only the settings file goes to Android Backup ([`ShizukuBackupAgent.kt`](../manager/src/main/java/af/shizuku/manager/ShizukuBackupAgent.kt)). The activity log, app databases and any other files stay on the device.

## What other apps can do through Shizuku+

An app you allow gets Shizuku's usual access: it can run shell commands as the ADB shell user, or as root in root mode. Only allow apps you trust. On top of that, Shizuku+ adds its own APIs, and the risky ones are **off for other apps by default**. To bring them back, unlock Developer options by tapping the version 7 times in Settings → About:

- **Other apps' private data** (`/data/data`, Android/data, Android/obb, temp-debug backups, app data restore). Turn on **Developer options → Unrestricted app data access** for backup tools like Neo Backup or SD Maid.
- **Personal data and device control** (SMS, contacts, call log, location, clipboard, notifications, screenshots, input injection, logcat, heap dumps, reboot, reading other apps' content providers). Turn on **Developer options → Personal data & device control**.

Both ask you to confirm, apply to every allowed app, and stop working the moment you turn them off. Per-app API scopes (in the app's details under Authorized apps) can restrict a single app further.

## Reporting a problem

If you find something here that isn't true, or a way data leaves the device that isn't listed, please report it privately as described in [SECURITY.md](../SECURITY.md).
