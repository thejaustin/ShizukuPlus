#!/usr/bin/env python3
"""Catch translation mistakes that break the build or crash at runtime, before they ship.

1. Every values-*/*.xml file must parse as XML.
2. A key may live in only one file per language folder (strings.xml and
   strings_machine_translated.xml clashing is an aapt duplicate-resource error).
3. A translated string must use the same format arguments as English: same argument
   numbers and types (%s / %d / %1$s ...). A missing, extra or wrong-type argument throws
   at String.format() time. Reordering (%2$d ... %1$s) is fine.

Usage: python3 scripts/i18n/check_translations.py [res_dir ...]   (exit 1 on problems)
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

FMT = re.compile(r"%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
LOCALE_DIR = re.compile(r"values-[a-z]{2,3}(-r[A-Z]{2})?$")


def args(text):
    """Format arguments as a set of (position, type); unnumbered ones count in order."""
    out, n = set(), 0
    for pos, kind in FMT.findall(text):
        if kind in "%n":
            continue
        n += 1
        out.add((int(pos) if pos else n, kind.lower()))
    return out


def load(path):
    """Return {key: [texts]} for string and plurals entries (formatted="false" skipped)."""
    out = {}
    for el in ET.parse(path).getroot():
        name = el.get("name")
        if not name or el.get("translatable") == "false":
            continue
        if el.tag == "string":
            out[name] = None if el.get("formatted") == "false" else ["".join(el.itertext())]
        elif el.tag == "plurals":
            out[name] = ["".join(i.itertext()) for i in el.findall("item")]
    return out


def check(res):
    problems = []
    base = {}
    for f in glob.glob(os.path.join(res, "values", "*.xml")):
        try:
            base.update(load(f))
        except ET.ParseError as e:
            problems.append(f"{f}: not valid XML ({e})")
    for d in sorted(glob.glob(os.path.join(res, "values-*"))):
        if not LOCALE_DIR.match(os.path.basename(d)):
            continue
        seen = {}
        for f in sorted(glob.glob(os.path.join(d, "*.xml"))):
            try:
                entries = load(f)
            except ET.ParseError as e:
                problems.append(f"{f}: not valid XML ({e})")
                continue
            for key, texts in entries.items():
                if key in seen:
                    problems.append(f"{f}: '{key}' is also in {os.path.basename(seen[key])}")
                seen[key] = f
                ref = base.get(key)
                if not ref or not texts:
                    continue
                want = set().union(*(args(t) for t in ref))
                for t in texts:
                    # %s prints any argument, so a %s where English has %d can't crash.
                    types = dict(want)
                    got = {(p, types.get(p, k) if k == "s" else k) for p, k in args(t)}
                    # Plural items may drop the count ("one" -> "a file"), so only extras fail there.
                    bad = got - want if len(ref) > 1 or len(texts) > 1 else got ^ want
                    if bad:
                        fmt = lambda s: " ".join(f"%{p}${k}" for p, k in sorted(s)) or "none"
                        problems.append(f"{f}: '{key}' uses {fmt(got)}, English uses {fmt(want)}")
                        break
    return problems


if __name__ == "__main__":
    dirs = sys.argv[1:] or ["manager/src/main/res", "core/ui/src/main/res"]
    found = [p for d in dirs if os.path.isdir(d) for p in check(d)]
    print("\n".join(found) if found else "translations OK")
    sys.exit(1 if found else 0)
