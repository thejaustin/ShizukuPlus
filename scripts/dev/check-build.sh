#!/usr/bin/env bash
# Compile-only check (no APK). Faster than assembleRelease for verifying
# Kotlin/Java compile. Used by AI agents to confirm a refactor didn't
# break compilation without paying for the full APK pipeline.
set -euo pipefail
cd "$(dirname "$0")/../.."
bash gradlew :manager:compileShizukuplusReleaseKotlin :manager:compileShizukuplusReleaseJavaWithJavac "$@"
