# Security, Privacy & Sandboxing Architecture

### Security Philosophy
Traditional rooting tools (Magisk, KernelSU, APatch) grant Ring-0 / `uid=0` privileges that bypass all Linux Discretionary Access Controls (DAC) and Android SELinux rules. This introduces critical security risks: rogue apps can silently read private credentials, compromise banking tokens, or cause permanent bootloops.

### The Shizuku Privilege Model (`uid 2000`)
ShizukuPlus strictly operates within Android's `android.uid.shell` (UID 2000) security context:
- **Enforced Sandbox**: Cannot tamper with the Linux kernel, boot partitions, or cryptographic hardware keystores.
- **Google Wallet & Banking Safety**: Because system integrity remains untouched, Play Integrity (DEVICE / BASIC integrity) and Samsung Knox remain un-tripped.
- **Binder IPC Security**: Client applications cannot execute unauthorized actions; every request is verified via Binder calling UID token checks.

### What an Allowed App Can Do
- An app you allow gets Shizuku's shell access (`uid 2000`, or root in root mode). Only allow apps you trust.
- **Riskier Plus APIs are off for other apps by default**: other apps' private data, and personal data and device control (SMS, contacts, call log, location, clipboard, notifications, screenshots, input injection, reboot). Turn them on in **Developer options → Unrestricted access**. They apply to every allowed app and stop the moment you turn them off.
- **Per-app API scopes** (in an app's details under Authorized apps) limit one app further. With any scope off, that app can't open an interactive shell, start its own background services, or run commands that can't be checked.
- **Quick safety check** (optional, Settings → App safety check): the access request shows where the app came from, when it was installed and its signer, plus VirusTotal or Pithus results if you add a key. Only the APK's hash is looked up.

### Backups & Crash Reports
- Backups never include your ADB key, which apps you allowed, or your crash-reporting choice. API keys, trusted Wi-Fi names and hidden apps are only in the encrypted export. After a restore, Shizuku+ tells you what didn't come back.
- Crash reporting is off until you say yes. Reports have addresses, network names, emails and other apps' commands stripped, and manual reports show other apps as "app 1", "app 2".
- The full list of what leaves your device is in [docs/PRIVACY.md](https://github.com/thejaustin/ShizukuPlus/blob/master/docs/PRIVACY.md).

### Full Auditing & Transparency
- **Live Activity Log**: ShizukuPlus maintains an audit log of every Binder request, transaction type, calling application, and timestamp.
- **One-Tap Kill Switch**: Tapping **Stop** immediately halts the Shizuku server and disconnects all authorized clients.
- **Granular Permissions**: You can revoke access from any authorized app at any time without restarting the phone.
