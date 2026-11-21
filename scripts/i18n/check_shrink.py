#!/usr/bin/env python3
"""Catch translations that silently disappear, e.g. a Crowdin download that overwrites a
language file with fewer strings than the repo has. Fails when a key that is translated at
HEAD (and still exists in English) is missing from the working tree.

Usage: python3 scripts/i18n/check_shrink.py [--base REF] [res_dir ...]   (exit 1 on problems)
--base compares against REF instead of HEAD (use it after a step that commits).
"""
import glob
import os
import re
import subprocess
import sys

KEY = re.compile(r'<(?:string|plurals|string-array|integer-array|array)\s+([^>]*?)>')
LOCALE_DIR = re.compile(r"values-[a-z]{2,3}(-r[A-Z]{2})?$")


def keys(text, translatable_only=False):
    out = set()
    for attrs in KEY.findall(re.sub(r"<!--.*?-->", "", text, flags=re.S)):
        m = re.search(r'name="([^"]+)"', attrs)
        if m and not (translatable_only and 'translatable="false"' in attrs):
            out.add(m.group(1))
    return out


def check(res, base="HEAD"):
    english = set()
    for f in glob.glob(os.path.join(res, "values", "*.xml")):
        english |= keys(open(f, encoding="utf-8").read(), translatable_only=True)
    tracked = subprocess.run(["git", "ls-tree", "-r", "--name-only", base, "--", res],
                             capture_output=True, text=True, check=True).stdout.split()
    by_dir = {}
    for path in tracked:
        d = os.path.dirname(path)
        if path.endswith(".xml") and LOCALE_DIR.match(os.path.basename(d)):
            by_dir.setdefault(d, []).append(path)
    problems = []
    for d, paths in sorted(by_dir.items()):
        before = set()
        for p in paths:
            before |= keys(subprocess.run(["git", "show", f"{base}:{p}"], capture_output=True,
                                          text=True, check=True).stdout)
        now = set()
        for f in glob.glob(os.path.join(d, "*.xml")):
            now |= keys(open(f, encoding="utf-8").read())
        lost = sorted((before - now) & english)
        if lost:
            problems.append(f"{d}: {len(lost)} translated strings would be lost "
                            f"(e.g. {', '.join(lost[:5])})")
    return problems


if __name__ == "__main__":
    argv, base = sys.argv[1:], "HEAD"
    if argv[:1] == ["--base"]:
        base, argv = argv[1], argv[2:]
    dirs = argv or ["manager/src/main/res", "core/ui/src/main/res"]
    found = [p for d in dirs if os.path.isdir(d) for p in check(d, base)]
    print("\n".join(found) if found else "no translations lost")
    sys.exit(1 if found else 0)
