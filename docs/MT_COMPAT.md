# MT Manager / legacy rish loader compatibility

## Summary

Third-party apps that bundle their own **prebuilt rish loader** (MT Manager 2.x is the
best-known example) hard-code the upstream Shizuku class name and upstream APK layout
assumptions. Shizuku+ keeps the manager implementation under `af.shizuku.manager`, so those
apps fail in two steps:

1. `java.lang.ClassNotFoundException: moe.shizuku.manager.shell.Shell`
   (reported by the app as *"Class not found. Make sure you have Shizuku v12.0.0 or above installed"*),
2. after adding a compatibility class, `java.lang.UnsatisfiedLinkError: couldn't find "librish.so"`.

This document explains both causes, the two-part fix shipped in this repository, and how to
produce and verify a compatible build.

## Background: how a legacy rish loader starts

A legacy loader (e.g. MT Manager's bundled `assets/rish_dex`, decrypted it contains a single
class `rikka.shizuku.shell.ShizukuShellLoader`) does the following:

1. Sends `rikka.shizuku.intent.action.REQUEST_BINDER` to the installed Shizuku package and waits
   for the server binder (this handshake still works with Shizuku+ - authorization and binder
   delivery are not the problem).
2. Builds a `DexClassLoader` over the installed Shizuku APK, with the native-library search path
   set to a path **inside the APK**: `'<shizuku.apk>!/lib/<abi>'`.
3. Loads the hard-coded class `moe.shizuku.manager.shell.Shell` and invokes
   `static main(String[] args, String packageName, IBinder binder, Handler handler)`
   reflectively.
4. The loaded `Shell` implementation then starts the app's `start` script through rish.

## Root cause 1 - hard-coded class name

`moe.shizuku.manager.shell.Shell` is the upstream Shizuku entry point. Shizuku+ moved the
manager namespace to `af.shizuku.manager` (`manager/build.gradle` ->
`namespace = "af.shizuku.manager"`), so the class is missing from the APK and step 3 throws
`ClassNotFoundException`. The loader's catch block prints the misleading "v12.0.0 or above"
message.

**Fix:** `manager/src/main/java/moe/shizuku/manager/shell/Shell.java` - a source-level shim in
the legacy package that forwards to `af.shizuku.manager.shell.Shell.main(...)` with the exact
signature the loader expects. It is protected from R8 shrinking/renaming by a dedicated rule in
`manager/proguard-rules.pro` (a reflection entry point is invisible to R8 otherwise).

## Root cause 2 - native libraries compressed inside the APK

With the class-name problem solved, the forwarded `Shell` reaches `rikka.rish.Rish`, which calls
`System.loadLibrary("rish")`. The loader created in step 2 resolves native libraries only in its
native-library search path: `'<shizuku.apk>!/lib/<abi>'` plus the system library paths. The
Android linker can `mmap` a shared object straight out of an APK **only when the ZIP entry is
STORED (uncompressed) and page-aligned**. Shizuku+ (like upstream Shizuku) packages jniLibs with
`useLegacyPackaging = true` (compressed, extracted at install), so the in-APK path cannot be
used and the loader fails with `UnsatisfiedLinkError: couldn't find "librish.so"`.

Note that changing the project globally to uncompressed native libraries
(`useLegacyPackaging = false`) is **not** a drop-in fix: it also flips
`android:extractNativeLibs` to `false`, and Shizuku+'s own rish loader resolves `librish.so`
from the extracted native-lib directory. Both layouts are needed at the same time.

**Fix:** keep the manifest and the project's packaging untouched, and post-process the built
APK with `scripts/mtcompat_repack.py`, which

* rewrites `lib/**/*.so` (and `resources.arsc`, which modern Android requires to be stored and
  at least 4-byte aligned) as STORED entries, keeping every other entry's compression as-is;
* page-aligns the stored native libraries (`zipalign -P 16 4` by default: 16 KiB pages, which
  also satisfies 4 KiB-page devices and Android 15+'s 16 KiB requirement);
* can sign the result with `apksigner` (signing does not move entry data, so the alignment is
  preserved - never run `zipalign` after signing);
* verifies the result before returning.

The manifest is intentionally left untouched (`extractNativeLibs="true"`), so the app's own
rish path keeps working from the extracted directory while legacy loaders mmap the same APK.

## Building a compatible APK

```bash
# 1. Build as usual (any flavor; Dropin is what applications looking for the stock
#    moe.shizuku.privileged.api package will use).
./gradlew :manager:assembleDropinRelease

# 2. Post-process the output. Requires Python 3 and Android build-tools
#    (zipalign/apksigner) - the script locates them via --build-tools,
#    ANDROID_HOME or ANDROID_SDK_ROOT.
python scripts/mtcompat_repack.py \
    manager/build/outputs/apk/dropin/release/manager-dropin-release.apk \
    -o Shizuku+-Dropin-mtcompat.apk \
    --ks signing.jks --ks-pass <store-pass> --ks-key-alias <alias> --key-pass <key-pass>
```

Without `--sign`, the script only repacks/aligns; sign the result with the project's usual
signing flow afterwards (in that case do not re-run zipalign).

## Verification

Tested on Android 12 (API 32) x86_64 emulator with a repacked Dropin APK
(`moe.shizuku.privileged.api`, the stock package name) and **MT Manager 2.14.5** (the
long-lived 2.x line, including community-repacked builds):

* MT's own shell debug log (`/sdcard/MT2/shell_debug/*.log`, enabled by creating that directory)
  shows `Creating server socket with port: 9200` -> `Success` -> `Socket connected` ->
  `exec [sh, ...]` for the `start` script;
* `ps -A` shows the `start` script and `bin.mt.shell_server.Main` running as `uid=2000(shell)`;
* MT's file manager can browse `/`, `/data/local/tmp`, etc. (paths an ordinary app cannot read);
* before the fix, the two errors quoted above appear in the same log.

Commands used to inspect the state:

```bash
adb shell "ls -t /sdcard/MT2/shell_debug/*.log | head -1 | xargs cat | tail -40"
adb shell "ps -A -o PID,PPID,USER,NAME,ARGS | grep -E 'shell_server|shizuku_plus_server'"
```

## Limitations

* The shim covers the **class name**; the APK still has to be repacked with the script above.
  A stock (unrepacked) Shizuku+ build will hit root cause 2. Integrating the repack step into a
  release pipeline is left to the release process.
* The repack step increases APK size by roughly the uncompressed size of `lib/**/*.so`
  (compression ratio difference), and is only needed for builds intended for legacy rish
  clients.
* Legacy loaders locate the Shizuku APK by the package name they know
  (`moe.shizuku.privileged.api`), so the Dropin flavor is the one legacy apps will use; the
  Shizuku+ flavor coexists under its own package name.
* Re-verify with your own device/ROM: some vendor ROMs block or delay the
  `REQUEST_BINDER` broadcast for background apps; that failure mode is unrelated to this shim
  (the loader times out before it ever reaches the class lookup).

## Alternatives considered

* **Patch the client** (e.g. change the class name and native path in MT's bundled rish dex):
  not viable for distribution - it requires modifying a third-party app and re-signing it.
* **Load `librish.so` from the extracted native-lib directory at runtime**: the legacy loader's
  `DexClassLoader` native-library path is fixed to the in-APK path, and `System.loadLibrary`
  resolves through that class loader, so a pre-load does not help unless the class loader
  itself is replaced; also brittle against hidden-API restrictions.
* **Flip `useLegacyPackaging` globally**: breaks the app's own rish loader (see above).

## Keep-alive for legacy clients (why the shim alone is not enough)

MT Manager does not use the modern Shizuku API handshake for its bundled rish: the rish
process sends the public `rikka.shizuku.intent.action.REQUEST_BINDER` broadcast and only waits
a few hundred milliseconds before it starts the shell socket. The broadcast is answered by
`af.shizuku.manager.receiver.BinderRequestReceiver` in the manager process. On devices with
aggressive OEM process management (vivo's `pem_freezer`, MIUI's app freezer, Samsung's
"sleeping apps", ...) the inactive manager process is frozen or killed, so it cannot answer the
broadcast in time and the client never gets shell access - even when the class-name and native
library fixes above are in place.

Shizuku+ therefore ships an optional keep-alive foreground service (`LegacyRishKeepAliveService`)
that:

* runs while the privileged server is RUNNING and stops when it stops;
* keeps the manager process reachable so `BinderRequestReceiver` can always answer legacy
  clients quickly;
* shows a minimum-importance notification (silent, no badge) so it is transparent;
* is controlled by Settings → Behavior → *Legacy rish compatibility* (`legacy_rish_keepalive`),
  on by default for the Drop-In flavor (the package legacy clients look for) and off for the
  Shizuku+ flavor.

Nothing else changes for modern clients: the service only affects process lifetime, and every
privileged method is still gated by `enforceCallingPermission()` on the real caller UID.

## Files

| File | Purpose |
|---|---|
| `manager/src/main/java/moe/shizuku/manager/shell/Shell.java` | legacy class-name shim (forwards to `af.shizuku.manager.shell.Shell`) |
| `manager/proguard-rules.pro` | `-keep` rule for the shim's class name and exact `main(...)` signature |
| `scripts/mtcompat_repack.py` | post-build repacker: stored + page-aligned jniLibs/arsc, optional signing, verification |
| `docs/MT_COMPAT.md` | this document |