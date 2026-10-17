#!/usr/bin/env python3
"""MT Manager / legacy-rish compatibility post-processor for Shizuku+ APKs.

Why this exists
---------------
Legacy rish loaders bundled by third-party apps (MT Manager 2.14.5 and other
prebuilt rikka/rish clients) load the shell implementation class out of the
installed Shizuku APK and set the native library search path to a path *inside*
the APK itself (the APK path followed by "!/lib/<abi>"). dlopen() can only mmap
a shared object straight out of the APK when the entry is STORED (uncompressed)
and page aligned; otherwise loading fails with:

    java.lang.UnsatisfiedLinkError: couldn't find "librish.so"

Shizuku+ packages its jniLibs with useLegacyPackaging = true (compressed, legacy
packaging) and may also compress resources.arsc, which modern Android
additionally requires to be stored and 4-byte aligned.

What this script does
---------------------
1. Rewrites the APK so every lib/**/*.so and resources.arsc is STORED
   (uncompressed); every other entry keeps its original compression.
2. Runs zipalign -P <page_kb> 4 on the result, page-aligning the stored native
   libraries (default page size 16 KiB, which also satisfies 4 KiB pages) and
   4-byte-aligning the rest.
3. Optionally signs the aligned APK with apksigner (apksigner does not realign,
   so the alignment survives - never run zipalign after signing).
4. Verifies the result: stored entries, alignment, and (when signing) the
   signature.

The AndroidManifest is intentionally left untouched (extractNativeLibs stays as
generated), so Shizuku's own rish keeps loading from the extracted native-lib
directory while legacy loaders can mmap the same APK directly.

Only the Python standard library plus Android build-tools are required.

Examples
--------
    # just repack + align
    python scripts/mtcompat_repack.py manager/build/outputs/apk/dropin/release/manager-dropin-release.apk -o Shizuku+-Drop-In-mtcompat.apk

    # repack + align + sign with the fallback debug keystore
    python scripts/mtcompat_repack.py in.apk -o out.apk --sign --ks debug.keystore --ks-pass android --ks-key-alias androiddebugkey --key-pass android

    # explicit build-tools directory (otherwise ANDROID_HOME / ANDROID_SDK_ROOT
    # or a build-tools path in the same SDK tree is searched)
    python scripts/mtcompat_repack.py in.apk -o out.apk --build-tools <sdk>/build-tools/36.0.0
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

NL = chr(10)
STORED_NAMES = ("resources.arsc",)
ALIGN_DEFAULT = 4
PAGE_KB_DEFAULT = 16


def should_store(name):
    """Return True if the entry must be STORED for in-APK mmap/verification."""
    n = name.replace(chr(92), "/")
    if n == "resources.arsc" or n in STORED_NAMES:
        return True
    return n.startswith("lib/") and n.lower().endswith(".so")


def human(size):
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            if unit == "B":
                return "%d %s" % (int(value), unit)
            return "%.1f %s" % (value, unit)
        value /= 1024.0
    return str(size)


def find_tool(tool, build_tools, env_names=("ANDROID_HOME", "ANDROID_SDK_ROOT")):
    """Locate a build-tools executable (zipalign / apksigner)."""
    exe = tool + (".exe" if sys.platform.startswith("win") else "")
    bat = tool + ".bat"
    candidates = []
    if build_tools:
        candidates.append(os.path.join(build_tools, exe))
        candidates.append(os.path.join(build_tools, bat))
    for env_name in env_names:
        root = os.environ.get(env_name)
        if not root:
            continue
        bt = os.path.join(root, "build-tools")
        if not os.path.isdir(bt):
            continue
        versions = []
        for version in os.listdir(bt):
            full = os.path.join(bt, version)
            if os.path.isdir(full):
                key = tuple(int(p) for p in version.split(".") if p.isdigit())
                versions.append((key, full))
        for _, full in sorted(versions, reverse=True):
            candidates.append(os.path.join(full, exe))
            candidates.append(os.path.join(full, bat))
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return None


def repack_stored(src, dst):
    """Copy src to dst, storing lib/**/*.so and resources.arsc uncompressed."""
    stored = []
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", allowZip64=True) as zout:
        for info in zin.infolist():
            data = zin.read(info)
            store = should_store(info.filename)
            if store:
                stored.append((info.filename, len(data)))
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            if store:
                zi.compress_type = zipfile.ZIP_STORED
            elif info.compress_type == zipfile.ZIP_STORED:
                zi.compress_type = zipfile.ZIP_STORED
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zi.flag_bits = info.flag_bits & 0x800  # keep only the UTF-8 name bit
            zout.writestr(zi, data, compress_type=zi.compress_type)
    return stored


def run(cmd, what):
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            check=False,
        )
    except OSError as exc:
        raise SystemExit("error: cannot run " + what + " (" + cmd[0] + "): " + str(exc))
    if proc.returncode != 0:
        raise SystemExit("error: " + what + " failed (exit " + str(proc.returncode) + ")" + NL + proc.stdout.strip())
    return proc.stdout


def verify_apk(path, align, page_kb, zipalign):
    """Assert stored entries and alignment; return (stored entries, problems)."""
    problems = []
    stored = []
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            if should_store(info.filename):
                stored.append((info.filename, info.compress_type == zipfile.ZIP_STORED, info.file_size))
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append(info.filename + " is still compressed")
    if zipalign:
        # zipalign exits non-zero and prints the offending entry when the APK is
        # not aligned, so the exit code alone is authoritative.
        run([zipalign, "-c", "-P", str(page_kb), str(align), path], "zipalign -c")
    return stored, problems


def main(argv):
    parser = argparse.ArgumentParser(
        description="Make a Shizuku+ APK loadable by MT Manager / legacy rish loaders "
                    "(stored + page-aligned native libs, stored resources.arsc).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("apk", help="input APK")
    parser.add_argument("-o", "--output", help="output APK (default: <input>-mtcompat.apk)")
    parser.add_argument("--build-tools", help="Android build-tools directory (contains zipalign/apksigner)")
    parser.add_argument("--zipalign", help="explicit path to the zipalign executable")
    parser.add_argument("--apksigner", help="explicit path to the apksigner executable")
    parser.add_argument("--align", type=int, default=ALIGN_DEFAULT,
                        help="alignment in bytes for regular entries (default: %d)" % ALIGN_DEFAULT)
    parser.add_argument("--page-kb", type=int, default=PAGE_KB_DEFAULT, choices=(4, 16, 64),
                        help="page size in KiB used to align uncompressed .so files (default: %d)" % PAGE_KB_DEFAULT)
    parser.add_argument("--sign", action="store_true", help="sign the aligned APK with apksigner")
    parser.add_argument("--ks", help="keystore path (required with --sign)")
    parser.add_argument("--ks-pass", default="android", help="keystore password (default: android)")
    parser.add_argument("--ks-key-alias", default="androiddebugkey", help="key alias (default: androiddebugkey)")
    parser.add_argument("--key-pass", default=None, help="key password (defaults to --ks-pass)")
    parser.add_argument("--keep-temp", action="store_true", help="keep intermediate files")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.apk):
        raise SystemExit("error: input APK not found: " + args.apk)

    out_apk = os.path.abspath(args.output or (os.path.splitext(args.apk)[0] + "-mtcompat.apk"))
    if os.path.abspath(args.apk) == out_apk:
        raise SystemExit("error: output must differ from input")

    zipalign = args.zipalign or find_tool("zipalign", args.build_tools)
    if not zipalign:
        raise SystemExit("error: zipalign not found; pass --build-tools or --zipalign")
    apksigner = None
    if args.sign:
        if not args.ks:
            raise SystemExit("error: --sign requires --ks")
        apksigner = args.apksigner or find_tool("apksigner", args.build_tools)
        if not apksigner:
            raise SystemExit("error: apksigner not found; pass --build-tools or --apksigner")

    tmpdir = tempfile.mkdtemp(prefix="mtcompat-")
    try:
        recompressed = os.path.join(tmpdir, "recompressed.apk")
        aligned = os.path.join(tmpdir, "aligned.apk")

        print("[1/4] storing lib/**/*.so and resources.arsc uncompressed ...")
        stored = repack_stored(args.apk, recompressed)
        if not stored:
            print("      warning: no lib/**/*.so or resources.arsc entries found - is this a full manager APK?")
        for name, size in stored:
            print("      STORED " + name.ljust(55) + " " + human(size))
        print("      input " + human(os.path.getsize(args.apk)) + " -> recompressed " + human(os.path.getsize(recompressed)))

        print("[2/4] zipalign -f -P " + str(args.page_kb) + " " + str(args.align) + " ...")
        run([zipalign, "-f", "-P", str(args.page_kb), str(args.align), recompressed, aligned], "zipalign")

        print("[3/4] verifying stored entries + alignment ...")
        stored_check, problems = verify_apk(aligned, args.align, args.page_kb, zipalign)
        if problems:
            raise SystemExit("error: verification failed:" + NL + "  " + (NL + "  ").join(problems))
        print("      OK: " + str(len(stored_check)) + " entries stored, alignment verified")

        if args.sign:
            print("[4/4] signing with apksigner ...")
            if os.path.exists(out_apk):
                os.remove(out_apk)
            key_pass = args.key_pass if args.key_pass is not None else args.ks_pass
            cmd = [apksigner, "sign", "--ks", os.path.abspath(args.ks),
                   "--ks-pass", "pass:" + args.ks_pass,
                   "--ks-key-alias", args.ks_key_alias,
                   "--key-pass", "pass:" + key_pass,
                   "--out", out_apk, aligned]
            run(cmd, "apksigner sign")
            run([apksigner, "verify", "--verbose", out_apk], "apksigner verify")
            _, problems = verify_apk(out_apk, args.align, args.page_kb, zipalign)
            if problems:
                raise SystemExit("error: post-sign verification failed:" + NL + "  " + (NL + "  ").join(problems))
            print("      OK: signed and still aligned")
        else:
            print("[4/4] no --sign requested; copying aligned APK to output")
            if os.path.exists(out_apk):
                os.remove(out_apk)
            shutil.copyfile(aligned, out_apk)

        print("")
        print("done: " + out_apk + " (" + human(os.path.getsize(out_apk)) + ")")
        return 0
    finally:
        if args.keep_temp:
            print("intermediate files kept in: " + tmpdir)
        else:
            shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
