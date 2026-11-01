#!/usr/bin/env python3
"""User-facing release notes from CHANGELOG.md.

The CHANGELOG entries a release added ("- **Title** — text") are written for users; commit
subjects aren't. This prints those entries, grouped under the CHANGELOG section they sit in.

    whats_new.py FROM TO            grouped entries added between two refs (markdown)
    whats_new.py FROM TO --title    one line for the release table: the first entry's title

FROM may be empty (first release): then every entry in TO's CHANGELOG counts as new.
Prints nothing when the release added no entries, so callers can fall back to commits.
"""
import os
import re
import subprocess
import sys

ENTRY = re.compile(r"^- \*\*(.+?)\*\*")


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout


def reworded_titles():
    """Titles in changelog_rewrites.txt were reworded, not added; never list them as new."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "changelog_rewrites.txt")
    try:
        with open(path, encoding="utf-8") as f:
            return {l.strip() for l in f if l.strip() and not l.startswith("#")}
    except OSError:
        return set()


def title_of(line):
    m = ENTRY.match(line)
    return m.group(1) if m else line


def added_entries(frm, to):
    if frm:
        diff = git("diff", frm, to, "--", "CHANGELOG.md")
        # A moved entry shows as removed + added; only titles FROM didn't have are new.
        before = {title_of(l) for l in git("show", f"{frm}:CHANGELOG.md").splitlines() if l.startswith("- **")}
        return [l[1:] for l in diff.splitlines() if l.startswith("+- **") and title_of(l[1:]) not in before]
    return [l for l in git("show", f"{to}:CHANGELOG.md").splitlines() if l.startswith("- **")]


def section_of(changelog_lines):
    """Map each entry line to the ### section it's under, first occurrence wins."""
    sections, current = {}, None
    for line in changelog_lines:
        if line.startswith("## "):
            current = None
        elif line.startswith("### "):
            current = line[4:].strip()
        elif line.startswith("- **") and line not in sections:
            sections[line] = current
    return sections


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    frm, to = sys.argv[1], sys.argv[2]
    skip = reworded_titles()
    entries = [e for e in added_entries(frm, to) if title_of(e) not in skip]
    if not entries:
        return
    sections = section_of(git("show", f"{to}:CHANGELOG.md").splitlines())
    grouped = {}
    for e in entries:
        grouped.setdefault(sections.get(e) or "Changes", []).append(e)
    # Features first, then the rest in CHANGELOG order.
    order = sorted(grouped, key=lambda s: 0 if "feature" in s.lower() or "added" in s.lower() else 1)

    if "--title" in sys.argv:
        m = ENTRY.match(grouped[order[0]][0])
        title = m.group(1) if m else grouped[order[0]][0]
        more = len(entries) - 1
        print(title + (f" (+{more} more)" if more > 0 else ""))
        return

    for s in order:
        print(f"### {s}")
        print("\n".join(grouped[s]))
        print()


if __name__ == "__main__":
    main()
