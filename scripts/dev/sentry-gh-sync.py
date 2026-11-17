#!/usr/bin/env python3
"""Link GitHub issues and Sentry issues ourselves (Sentry's own GitHub issue linking is a paid
feature; notes and status changes through its API are not).

For every GitHub issue updated in the last --days days, finds the Sentry issues it names:
  - short ids such as SHIZUKUPLUS-BH (in the body or any comment)
  - "Sentry event: <32 hex>" lines, which the app adds to its crash logs and crash reports
  - sentry.io issue links
Then, for each pair:
  - adds a note on the Sentry issue linking the GitHub issue (once; skipped if already there)
  - if a maintainer closed the GitHub issue as completed and the Sentry issue is still
    unresolved, resolves it in the next release
  - if the Sentry issue is resolved but the GitHub issue is still open, only reports it: closing
    a public issue stays a person's call.

Usage: SENTRY_API_TOKEN=... scripts/dev/sentry-gh-sync.py [--days 30] [--dry-run]
The token needs event:read and event:write. Nothing is sent anywhere but sentry.io and GitHub.
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

ORG = "af-developments"
REPO = "thejaustin/ShizukuPlus"
API = f"https://sentry.io/api/0/organizations/{ORG}"

SHORT_ID = re.compile(r"\bSHIZUKUPLUS-[A-Z0-9]{1,6}\b")
EVENT_ID = re.compile(r"Sentry event:\s*([0-9a-f]{32})\b", re.I)
ISSUE_URL = re.compile(r"sentry\.io/(?:organizations/[\w-]+/)?issues/(\d+)")


def sentry(method, path, body=None):
    req = urllib.request.Request(
        API + path,
        method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={
            "Authorization": "Bearer " + os.environ["SENTRY_API_TOKEN"],
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def github_issues(days):
    out = subprocess.run(
        ["gh", "issue", "list", "-R", REPO, "--state", "all", "--limit", "300",
         "--search", f"updated:>={(datetime.date.today() - datetime.timedelta(days=days)).isoformat()}",
         "--json", "number,title,url,state,stateReason,body,comments"],
        check=True, capture_output=True, text=True,
    ).stdout
    return json.loads(out)


# Only these accounts' closes resolve Sentry issues: anyone can close their own issue, and an issue
# naming any Sentry id would otherwise let them resolve it.
MAINTAINERS = set(os.environ.get("SYNC_MAINTAINERS", "thejaustin").split(","))


def closed_by_maintainer(number):
    out = subprocess.run(
        ["gh", "api", f"repos/{REPO}/issues/{number}", "--jq", ".closed_by.login // empty"],
        capture_output=True, text=True,
    ).stdout.strip()
    return out in MAINTAINERS


def sentry_refs(issue):
    text = "\n".join([issue.get("body") or ""] + [c.get("body") or "" for c in issue.get("comments", [])])
    refs = set()
    for short in SHORT_ID.findall(text):
        g = sentry("GET", f"/issues/{short}/")
        if g:
            refs.add(g["id"])
    for ev in EVENT_ID.findall(text):
        r = sentry("GET", f"/eventids/{ev.lower()}/")
        if r:
            refs.add(str(r["groupId"]))
    refs.update(ISSUE_URL.findall(text))
    return refs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if not os.environ.get("SENTRY_API_TOKEN"):
        sys.exit("SENTRY_API_TOKEN is not set")

    for issue in github_issues(args.days):
        for gid in sorted(sentry_refs(issue)):
            group = sentry("GET", f"/issues/{gid}/")
            if not group:
                continue
            sid, status = group["shortId"], group["status"]
            line = f"#{issue['number']} ({issue['state'].lower()}) <-> {sid} ({status})"

            notes = sentry("GET", f"/issues/{gid}/comments/") or []
            if not any(issue["url"] in (n.get("data", {}).get("text") or "") for n in notes):
                line += " | note added"
                if not args.dry_run:
                    sentry("POST", f"/issues/{gid}/comments/",
                           {"text": f"GitHub #{issue['number']}: {issue['title']}\n{issue['url']}"})

            closed_fixed = issue["state"] == "CLOSED" and issue.get("stateReason") == "COMPLETED"
            if closed_fixed and status == "unresolved" and closed_by_maintainer(issue["number"]):
                line += " | resolved in next release"
                if not args.dry_run:
                    sentry("PUT", f"/issues/{gid}/",
                           {"status": "resolved", "statusDetails": {"inNextRelease": True}})
            elif issue["state"] == "OPEN" and status == "resolved":
                line += " | Sentry resolved, GitHub still open: close it if the fix shipped"
            print(line)


if __name__ == "__main__":
    main()
