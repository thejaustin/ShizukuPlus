#!/usr/bin/env bash
# Build, patch, sign, and install the ShizukuPlus debug APK.
#
# On Termux/ARM64: the Gradle output lands in ~/shizuku-build (not manager/build)
# because of the custom Termux cmake wrapper. We also detect whether the built
# native .so files dynamically depend on libc++_shared.so (which happens when
# Termux's clang links dynamically instead of using the lsposed static libcxx.a)
# and inject the library from Termux into the APK if so, before signing.
#
# Usage:  bash scripts/dev/build-install-debug.sh [gradlew args...]
set -euo pipefail
cd "$(dirname "$0")/../.."

ADB_SERIAL="${ADB_SERIAL:-127.0.0.1:5555}"
FLAVOR="shizukuplus"
DEBUG_KEYSTORE="${HOME}/.android/debug.keystore"

# ── 1. Gradle build ─────────────────────────────────────────────────────────
echo "▸ Building :manager:assembleShizukuplusDebug …"
bash gradlew :manager:assembleShizukuplusDebug "$@"

# ── 2. Locate the APK (Termux puts it under ~/shizuku-build) ────────────────
APK=""
SEARCH_ROOTS=(
    "${HOME}/shizuku-build/manager/outputs/apk/${FLAVOR}/debug"
    "manager/build/outputs/apk/${FLAVOR}/debug"
    "manager/build/outputs/apk/debug"
)
for dir in "${SEARCH_ROOTS[@]}"; do
    candidate="$(ls "${dir}"/*.apk 2>/dev/null | head -1 || true)"
    if [[ -n "$candidate" ]]; then
        APK="$candidate"
        break
    fi
done

if [[ -z "$APK" ]]; then
    echo "✗ Could not locate built APK. Tried:" >&2
    for dir in "${SEARCH_ROOTS[@]}"; do echo "    $dir" >&2; done
    exit 1
fi
echo "▸ APK: $APK"

# ── 3. Inject libc++_shared.so if the built .so files need it ───────────────
#    The Termux clang links dynamically against libc++_shared.so instead of
#    using the lsposed static libcxx.a. Detect this by checking NEEDED entries
#    in one of the extracted .so files via Python's zipfile.
LIBCPP_PATH="${DATA_PATH:-/data/data/com.termux/files/usr/lib}/libc++_shared.so"
if [[ ! -f "$LIBCPP_PATH" ]]; then
    LIBCPP_PATH="/data/data/com.termux/files/usr/lib/libc++_shared.so"
fi

TMPDIR_LOCAL="${TMPDIR:-${HOME}/tmp-apk}"
mkdir -p "$TMPDIR_LOCAL"
WORK_APK="${TMPDIR_LOCAL}/sp-work-$$.apk"
PATCHED_APK="${TMPDIR_LOCAL}/sp-patched-$$.apk"

cp "$APK" "$WORK_APK"

python3 - "$WORK_APK" "$PATCHED_APK" "$LIBCPP_PATH" <<'PY'
import sys, zipfile, struct

src, dst, libcpp = sys.argv[1], sys.argv[2], sys.argv[3]

def so_needs_libcxx(data: bytes) -> bool:
    """Return True if an ELF shared object has NEEDED=libc++_shared.so."""
    try:
        # Quick ELF header check
        if data[:4] != b'\x7fELF':
            return False
        bits = 64 if data[4] == 2 else 32
        if bits == 64:
            sh_off = struct.unpack_from('<Q', data, 40)[0]
            sh_ent = struct.unpack_from('<H', data, 58)[0]
            sh_num = struct.unpack_from('<H', data, 60)[0]
            dyn_hdr = struct.unpack_from('<H', data, 62)[0]
        else:
            sh_off = struct.unpack_from('<I', data, 32)[0]
            sh_ent = struct.unpack_from('<H', data, 46)[0]
            sh_num = struct.unpack_from('<H', data, 48)[0]
            dyn_hdr = struct.unpack_from('<H', data, 50)[0]
        # Search section headers for SHT_DYNAMIC (type=6) and SHT_STRTAB (type=3)
        sections = []
        for i in range(sh_num):
            off = sh_off + i * sh_ent
            if bits == 64:
                sh_type = struct.unpack_from('<I', data, off + 4)[0]
                sh_addr = struct.unpack_from('<Q', data, off + 24)[0]
                sh_foff = struct.unpack_from('<Q', data, off + 24 + 8)[0]  # sh_offset
                sh_size = struct.unpack_from('<Q', data, off + 24 + 16)[0]
                sh_link = struct.unpack_from('<I', data, off + 24 + 32)[0]
            else:
                sh_type = struct.unpack_from('<I', data, off + 4)[0]
                sh_addr = struct.unpack_from('<I', data, off + 12)[0]
                sh_foff = struct.unpack_from('<I', data, off + 16)[0]
                sh_size = struct.unpack_from('<I', data, off + 20)[0]
                sh_link = struct.unpack_from('<I', data, off + 24)[0]
            sections.append((sh_type, sh_foff, sh_size, sh_link))
        # Simpler: just scan for the string in the whole file
        return b'libc++_shared.so\x00' in data
    except Exception:
        return False

needs_inject = False
with zipfile.ZipFile(src) as z:
    for name in z.namelist():
        if name.startswith('lib/') and name.endswith('.so') and 'libc++_shared' not in name:
            data = z.read(name)
            if so_needs_libcxx(data):
                needs_inject = True
                print(f"  {name} → needs libc++_shared.so")
                break

if not needs_inject:
    print("  libc++_shared.so not needed — APK unchanged")
    import shutil; shutil.copy(src, dst)
    sys.exit(0)

import os
if not os.path.exists(libcpp):
    print(f"  WARNING: {libcpp} not found — skipping injection. Server may fail.", file=sys.stderr)
    import shutil; shutil.copy(src, dst)
    sys.exit(0)

print(f"  Injecting {libcpp} into lib/arm64-v8a/libc++_shared.so …")
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(dst, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == 'lib/arm64-v8a/libc++_shared.so':
            continue  # will be replaced below
        zout.writestr(item, zin.read(item.filename))
    with open(libcpp, 'rb') as f:
        zout.writestr('lib/arm64-v8a/libc++_shared.so', f.read())
print("  Done.")
PY

# ── 3b. Align ────────────────────────────────────────────────────────────────
# The Python repack above keeps each entry's compression but not its alignment. Uncompressed
# dex/.so must be aligned or ART/the linker falls back to extracting them into memory.
ALIGNED_APK="${TMPDIR_LOCAL}/sp-aligned-$$.apk"
zipalign -f -P 16 4 "$PATCHED_APK" "$ALIGNED_APK"
mv -f "$ALIGNED_APK" "$PATCHED_APK"

# ── 4. Sign ──────────────────────────────────────────────────────────────────
SIGNED_APK="${TMPDIR_LOCAL}/sp-signed-$$.apk"
# With the release key set up locally (signing.properties, gitignored; keep the keystore in
# private storage like ~/.keys, never /sdcard), sign with it: the build then installs over the
# published app, which a Device Owner app can't be uninstalled to make way for. Otherwise the
# debug key, which needs the installed app removed first.
if [[ -r signing.properties ]]; then
    KS_FILE="$(sed -n 's/^KEYSTORE_FILE=//p' signing.properties)"
    export SP_KS_PASS="$(sed -n 's/^KEYSTORE_PASSWORD=//p' signing.properties)"
    export SP_KEY_PASS="$(sed -n 's/^KEYSTORE_ALIAS_PASSWORD=//p' signing.properties)"
    echo "▸ Signing with the release key …"
    apksigner sign \
        --ks "$KS_FILE" \
        --ks-key-alias "$(sed -n 's/^KEYSTORE_ALIAS=//p' signing.properties)" \
        --ks-pass env:SP_KS_PASS \
        --key-pass env:SP_KEY_PASS \
        --out "$SIGNED_APK" \
        "$PATCHED_APK"
    unset SP_KS_PASS SP_KEY_PASS
else
    echo "▸ Signing with the debug key …"
    apksigner sign \
        --ks "$DEBUG_KEYSTORE" \
        --ks-pass pass:android \
        --key-pass pass:android \
        --out "$SIGNED_APK" \
        "$PATCHED_APK"
fi

# ── 5. Install via ADB ───────────────────────────────────────────────────────
echo "▸ Installing on device (serial: $ADB_SERIAL) …"
adb -s "$ADB_SERIAL" install -r "$SIGNED_APK"

# ── 6. Restart the Shizuku server with the new APK ──────────────────────────
echo "▸ Restarting Shizuku server …"
NEW_APK_DIR="$(adb -s "$ADB_SERIAL" shell pm path af.shizuku.plus.api 2>/dev/null \
    | sed 's|package:||;s|/base.apk||')"

if [[ -n "$NEW_APK_DIR" ]]; then
    adb -s "$ADB_SERIAL" shell "
        kill \$(pidof shizuku_plus_server) 2>/dev/null || true
        sleep 1
        LD_LIBRARY_PATH=${NEW_APK_DIR}/lib/arm64 \
            ${NEW_APK_DIR}/lib/arm64/libshizuku.so \
            --apk=${NEW_APK_DIR}/base.apk &
        sleep 3
        # The app's own package-replaced receiver may have started a server at the same time;
        # keep only the newest so two servers never fight over the binder.
        set -- \$(pidof shizuku_plus_server)
        if [ \$# -gt 1 ]; then
            newest=\$(for p in \"\$@\"; do echo \"\$(stat -c %Y /proc/\$p) \$p\"; done | sort -n | tail -1 | cut -d' ' -f2)
            for p in \"\$@\"; do [ \"\$p\" != \"\$newest\" ] && kill \$p; done
            sleep 1
        fi
        ps -A | grep shizuku_plus_server
    " 2>&1
else
    echo "  WARNING: Could not determine APK path; server not restarted."
fi

# ── Cleanup ──────────────────────────────────────────────────────────────────
rm -f "$WORK_APK" "$PATCHED_APK" "$SIGNED_APK"

echo ""
echo "✓ Build + install complete."
echo "  Open ShizukuPlus — it should show 'Running'."
echo "  Then launch Hex Bodhi to test the LegacyShizukuBinderProxy."
