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
    (2202, "v13.6.0.r2202", "Cached Apps Freezer fix — third-party apps reliably detect Shizuku+"),
    (2341, "v13.6.0.r2341", "Watchdog crash-recovery and permission grant-notification fixes"),
    (2393, "v13.6.0.r2393", "Binder IPC migration — ADB-mode for all features, Android 17, exec() security hardening"),
    (2535, "v13.6.0.r2535", "Third-party app detection fix + AppOps IPC Parcel alignment (#480, #488, #491)"),
    (2593, "v13.6.0.r2593", "Android system backup compatibility (Google Drive Auto Backup, ADB backup, Swift Backup, Neo Backup)"),
    (2603, "v13.6.0.r2603", "App Backup overhaul — search, batch backup, restore, system-app filter, allowBackup badge"),
    (2609, "v13.6.0.r2609", "Device Control — connectivity, display, audio, power from a new home card"),
]

HEADLINE_REV, HEADLINE_TAG, _ = MAJORS[-1]
HEADLINE_DESC = ("Device Control: a new home screen card gives privileged access to airplane mode, "
                 "Wi-Fi, Bluetooth, mobile data, NFC, screen brightness and timeout, auto-rotate, "
                 "media/ring/alarm volume, system animations, font scale, and reboot/shutdown — all "
                 "using shell uid without root. Initial state is read from Settings on open; writes "
                 "take effect immediately. The card is draggable and hideable like all other home "
                 "cards. Also includes the App Backup overhaul: search/filter, batch backup, restore "
                 "from external file, system-app filter, and allowBackup badge.")
MAJOR_REVS = {m[0] for m in MAJORS}

# Most recent CRITICAL FIX to spotlight - mirrors app.yml's CRITICAL_RELEASE/CRITICAL_DESC (keep
# both in sync by hand, same as MAJORS above). Unlike MAJORS, this has no per-era history: a
# critical-fix callout is meant to be occasional and doesn't track "the critical fix of this
# build's own era" - just the single most recent one, same on every page, like the headline major.
# Set CRITICAL_REV to None to omit this callout entirely once nothing recent qualifies.
CRITICAL_REV = 2590
CRITICAL_TAG = "v13.6.0.r2590"
CRITICAL_DESC = ("Fixed Settings import/export silently reading/writing the wrong storage file on "
                 "Android 7+ (exports were empty; imports had no effect). Fixed Feature Hub crash "
                 "(ClassCastException) caused by migrateAutomationTrustedNetworks reading the wrong "
                 "SharedPreferences file. Fixed AutomationService double-firing network firewall rule, "
                 "not starting the app monitor for auto-hide-only users, and not restarting correctly "
                 "at boot on API 24-25. Fixed WatchdogService clearing watchdog setting on transient "
                 "foreground-start rejections.")


def sh(args, retries=4):
    """Run a command, retrying on transient GitHub API failures (503/timeout/rate)."""
    last = None
    for attempt in range(retries):
        last = subprocess.run(args, capture_output=True, text=True, cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
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
    """The actual latest 5 releases (newest first), marking the latest, any major, and the
    critical fix (mirrors app.yml's per-release table - only visible here if CRITICAL_TAG happens
    to still be within the last 5 releases)."""
    global CUR5_ROWS
    if CUR5_ROWS is None:
        top = gh_tags()[:5]
        newest = top[0] if top else ""
        rows = []
        for t in top:
            mark = ""
            if rev_of(t) in MAJOR_REVS:
                mark += " 🚀 **major**"
            if CRITICAL_REV is not None and t == CRITICAL_TAG:
                mark += " ⚠️ **critical fix**"
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
    # Most recent critical fix — single callout, same on every page (no era-tracking, unlike
    # majors above - see CRITICAL_REV's own comment for why).
    if CRITICAL_REV is not None:
        this = " _(this release)_" if CRITICAL_TAG == tag else ""
        L += ["", f"> ⚠️ **Most recent critical fix — [{CRITICAL_TAG}]({BASE}/{CRITICAL_TAG})**{this}", f"> {CRITICAL_DESC}"]
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
