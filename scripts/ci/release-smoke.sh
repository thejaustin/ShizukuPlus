#!/usr/bin/env bash
# Release (R8-minified) smoke test on an emulator: install the release APK, open the app, start
# the server the way `adb shell` users do, and fail on a crash or a missing process. Catches
# missing ProGuard keeps (manager/proguard-rules.pro replaces Android's defaults), which only
# crash minified builds. Run by .github/workflows/release-smoke.yml inside the emulator runner.
set -u
PKG=af.shizuku.plus.api
SERVER=shizuku_plus_server
OUT=smoke-logs
mkdir -p "$OUT"
fail=0

apk=$(find manager/build/outputs/apk/shizukuplus/release -name '*.apk' | head -1)
[ -n "$apk" ] || { echo "::error::No release APK found"; exit 1; }

adb logcat -c
adb install -r -g "$apk" || { echo "::error::install failed"; exit 1; }

echo "== Launch app"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 10

echo "== Start server (adb shell starter)"
libdir=$(adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^ *legacyNativeLibraryDir=//p' | head -1)
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
case "$abi" in x86_64) sub=x86_64 ;; x86) sub=x86 ;; arm64-v8a) sub=arm64 ;; *) sub=arm ;; esac
adb shell "LD_LIBRARY_PATH=$libdir/$sub $libdir/$sub/libshizuku.so" | tee "$OUT/starter.txt"
sleep 10

# Reopen the app so it binds to the running server (exercises the Binder path too).
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 10

adb shell pidof "$PKG" >/dev/null || { echo "::error::app process is not running"; fail=1; }
adb shell pidof "$SERVER" >/dev/null || { echo "::error::$SERVER is not running"; fail=1; }

adb logcat -d > "$OUT/logcat.txt"
adb logcat -b crash -d > "$OUT/crash.txt"
if command grep -qE "Process: ($PKG|$SERVER)|$SERVER" "$OUT/crash.txt"; then
  echo "::error::crash logged (see smoke-logs/crash.txt)"
  command grep -A25 -E "Process: ($PKG|$SERVER)" "$OUT/crash.txt" | head -60
  fail=1
fi
# R8 problems often surface as caught class-loading errors rather than crashes.
if command grep -E "ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError" "$OUT/logcat.txt" \
    | command grep -E "af\.shizuku|rikka\.shizuku|moe\.shizuku" | head -20 | command grep .; then
  echo "::error::class/method missing at runtime - likely a missing ProGuard keep"
  fail=1
fi

[ $fail -eq 0 ] && echo "release smoke test passed"
exit $fail
