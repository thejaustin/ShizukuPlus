#!/usr/bin/env python3
"""Backfill every release's rollup so each page shows:
  - the CURRENT most-recent major (headline, same on every page), and
  - the major release of *that build's own era* (latest major with rev <= this rev).
Run with no args to edit all releases; --dry to preview a few."""
import subprocess, re, sys, os, tempfile, time

REPO = "thejaustin/ShizukuPlus"
BASE = f"https://github.com/{REPO}/releases/tag"

# Feature-milestone "major" releases, oldest -> newest. Each release page spotlights the most
# recent one at or before its own rev, so it shows the big release of its own era.
MAJORS = [
    (1359, "v13.6.0.r1359-shizukuplus", "Root Compatibility Hub"),
    (1361, "v13.6.0.r1361-shizukuplus", "Dynamic remote app database"),
    (1387, "v13.6.0.r1387-shizukuplus", "Fake-su backend (rootless su)"),
    (1407, "v13.6.0.r1407-shizukuplus", "Material 3 Expressive UI"),
    (1415, "v13.6.0.r1415-shizukuplus", "Activity Log with app icons"),
    (1553, "v13.6.0.r1553-shizukuplus", "AI-agent input simulation & UI dump"),
    (1554, "v13.6.0.r1554-shizukuplus", "SU Bridge + advanced root mocking"),
    (1562, "v13.6.0.r1562-shizukuplus", "Stable/Dev update channels"),
    (1607, "v13.6.0.r1607-shizukuplus", "Resilient update checker"),
    (1649, "v13.6.0.r1649-shizukuplus", "Settings import/export"),
    (1669, "v13.6.0.r1669-shizukuplus", "Full Material 3 Expressive coverage"),
    (1931, "v13.6.0.r1931", "Spoof/ghost developer features"),
    (2025, "v13.6.0.r2025", "VirusTotal / Pithus APK verification"),
    (2032, "v13.6.0.r2032", "Stealth mode (hide launcher icon)"),
    (2129, "v13.6.0.r2129", "Android 17 (SDK 37) support"),
    (2139, "v13.6.0.r2139", "Stock-client compatibility (App-Ops / OptiDroid / Obtainium)"),
    (2149, "v13.6.0.r2149", "SU Bridge — root features for third-party apps on non-root"),
]

HEADLINE_REV, HEADLINE_TAG, _ = MAJORS[-1]
HEADLINE_DESC = ("SU Bridge now works with third-party root apps — apps like Swift Backup that gate "
                 "features behind root can use their root-only features on non-rooted devices by "
                 "pointing their custom-su path at Shizuku+. Fixes the long-standing caller-identity "
                 "bug so the bridge authenticates as the *calling* app, not Shizuku+ itself, and "
                 "deploys the wrapper to an exec-permitted location.")
MAJOR_REVS = {m[0] for m in MAJORS}


def sh(args, retries=4):
    """Run a command, retrying on transient GitHub API failures (503/timeout/rate)."""
    last = None
    for attempt in range(retries):
        last = subprocess.run(args, capture_output=True, text=True, cwd="/sdcard/Documents/ShizukuPlus")
        if last.returncode == 0:
            return last
        err = ((last.stderr or "") + (last.stdout or "")).lower()
        if any(s in err for s in ("503", "no server is currently available", "timeout",
                                  "bad gateway", "502", "500", "rate limit", "abuse",
                                  "unexpected eof", "read tcp", "malformed request",
                                  "i/o timeout", "connection reset", "eof", "graphql")):
            time.sleep(2 + attempt * 3)
            continue
        break
    return last


def gh_tags():
    """Fetch every release tag, newest-rev first. Hard-fails (raises) rather than returning
    an empty list, so a transient 503 can never make us write empty last-5 tables everywhere."""
    r = sh(["gh", "api", "--paginate", f"repos/{REPO}/releases", "--jq", ".[].tag_name"])
    tags = [t for t in r.stdout.split() if re.search(r"r\d+", t)]
    if r.returncode != 0 or len(tags) < 5:
        raise SystemExit(f"ABORT: could not fetch release list (rc={r.returncode}, "
                         f"got {len(tags)} tags). GitHub API may be down — retry later.\n"
                         f"{(r.stderr or '')[:200]}")
    tags.sort(key=rev_of, reverse=True)
    return tags


def subj(tag):
    # Local git first (fast); fall back to the API when the tag isn't resolvable locally
    # (this clone's history was rewritten, so newer tags don't map to a local commit).
    r = sh(["git", "log", "-1", "--pretty=%s", tag])
    s = r.stdout.strip() if r.returncode == 0 else ""
    if not s:
        r = sh(["gh", "api", f"repos/{REPO}/commits/{tag}", "--jq", ".commit.message"])
        s = (r.stdout.strip().splitlines() or [""])[0] if r.returncode == 0 else ""
    return s.replace("|", "\\|")


def rev_of(tag):
    m = re.search(r"r(\d+)", tag)
    return int(m.group(1)) if m else 0


def contemporary_major(rev):
    best = None
    for mrev, mtag, mlabel in MAJORS:
        if mrev <= rev:
            best = (mrev, mtag, mlabel)
    return best


CUR5_ROWS = None
def cur5_rows():
    """The actual latest 5 releases (newest first), marking the latest and any major."""
    global CUR5_ROWS
    if CUR5_ROWS is None:
        top = gh_tags()[:5]
        newest = top[0] if top else ""
        rows = []
        for t in top:
            mark = ""
            if rev_of(t) in MAJOR_REVS:
                mark += " 🚀 **major**"
            if t == newest:
                mark += " _(latest)_"
            rows.append(f"| [{t}]({BASE}/{t}){mark} | {subj(t)} |")
        CUR5_ROWS = rows
    return CUR5_ROWS


def build_rollup(rev, tag):
    older = rev < HEADLINE_REV
    L = ["## 📦 Recent Releases", ""]
    if older:
        L += ["> 📣 You're viewing an older release. Here are the current builds — update for the latest fixes:", ""]
    # Current headline major (same on every page).
    if tag == HEADLINE_TAG:
        L += [f"> 🚀 **This is the most recent major release — {HEADLINE_TAG}**", f"> {HEADLINE_DESC}"]
    else:
        L += [f"> 🚀 **Most recent major release — [{HEADLINE_TAG}]({BASE}/{HEADLINE_TAG})**", f"> {HEADLINE_DESC}"]
    # Era major — only when it differs from the headline (else it'd be redundant).
    cm = contemporary_major(rev)
    if cm and cm[1] != HEADLINE_TAG:
        _, ctag, clabel = cm
        this = " _(this release)_" if ctag == tag else ""
        L += ["", f"> 🏛️ **Major release of this build's era — [{ctag}]({BASE}/{ctag})**{this} — {clabel}"]
    L += ["", "| Release | Highlight |", "|:--|:--|"] + cur5_rows()
    return "\n".join(L)


def transform(body, rev, tag):
    m = re.search(r"\[Full Changelog\]\([^)]*\)", body)
    footer = m.group(0) if m else ""
    cut = len(body)
    for marker in ["### 🏗️ Build History", "## 📦 Recent Releases", "\n---"]:
        i = body.find(marker)
        if i != -1:
            cut = min(cut, i)
    head = body[:cut].rstrip()
    new = head + "\n\n" + build_rollup(rev, tag)
    if footer:
        new += "\n\n---\n" + footer
    return new + "\n"


def targets():
    tags = [t for t in gh_tags() if re.match(r"^v13\.6\.0\.r\d+", t)]
    out = [(rev_of(t), t) for t in tags]
    out.sort(reverse=True)
    return out


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--dry":
        samples = ["v13.6.0.r2150", "v13.6.0.r2149", "v13.6.0.r2145",
                   "v13.6.0.r2135", "v13.6.0.r2092", "v13.6.0.r1600-shizukuplus"]
        for tag in samples:
            rev = rev_of(tag)
            body = sh(["gh", "release", "view", tag, "--repo", REPO, "--json", "body", "-q", ".body"]).stdout
            if not body:
                print(f"(no {tag})"); continue
            print(f"\n===== {tag} (rev {rev}) =====\n" + transform(body, rev, tag))
        sys.exit(0)
    if len(sys.argv) > 1 and sys.argv[1] == "--only":
        cur5_rows()  # prime the cache once up front
        tg = sorted(((rev_of(t), t) for t in sys.argv[2:]), reverse=True)
    else:
        tg = targets()
    print(f"{len(tg)} targets, r{tg[0][0]}..r{tg[-1][0]}")
    ok = fail = 0
    for num, tag in tg:
        r = sh(["gh", "release", "view", tag, "--repo", REPO, "--json", "body", "-q", ".body"])
        if r.returncode != 0:
            print("skip(view)", tag); fail += 1; continue
        new = transform(r.stdout, num, tag)
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
            f.write(new); path = f.name
        e = sh(["gh", "release", "edit", tag, "--repo", REPO, "--notes-file", path])
        os.unlink(path)
        if e.returncode == 0:
            ok += 1
            if ok % 20 == 0: print(f"  ...{ok} done")
        else:
            print("FAIL", tag, e.stderr[:100]); fail += 1
    print(f"DONE ok={ok} fail={fail}")
