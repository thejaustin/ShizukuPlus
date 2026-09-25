#!/usr/bin/env bash
# Fast debug build. No Sentry upload.
# NOTE: On ARM64 PRoot (Termux), native modules (rish, jni) require CI — use GitHub Actions
# to produce a testable APK. This script works on standard x86_64 Linux dev machines.
set -euo pipefail
cd "$(dirname "$0")/../.."
bash gradlew :manager:assembleShizukuplusDebug "$@"
echo "APK: $(find manager/build/outputs/apk -name '*.apk' | head -1)"
