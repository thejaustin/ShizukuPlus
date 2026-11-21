#!/usr/bin/env bash
# Create or update the triage labels (idempotent; never deletes or renames). Needs `gh` auth.
set -euo pipefail
cd "$(dirname "$0")/../.."
L() { gh label create "$1" --color "$2" --description "$3" --force >/dev/null && echo "ok  $1"; }

# Priority: how soon
L "priority: high"      B60205 "Affects many users or a core flow; do soon"
L "priority: medium"    FBCA04 "Real problem, not urgent"
L "priority: low"       C2E0C6 "Nice to have / edge case"

# Status: where it is in triage (what we are waiting on)
L "status: needs-info"      D4C5F9 "Waiting on the reporter: logs, device, steps"
L "status: needs-repro"     D4C5F9 "We can't reproduce it yet"
L "status: confirmed"       0E8A16 "Reproduced / agreed it's real"
L "status: in-progress"     1D76DB "Someone is working on it"
L "status: fixed-in-prerelease" 0E8A16 "Fixed on master; ships in the next release"
L "status: blocked-upstream" 5319E7 "Needs a change in Android, Shizuku, Dhizuku or another project"

# Where: flavor / environment, so reports sort by who they hit
L "flavor: plus"        0052CC "ShizukuPlus flavor"
L "flavor: drop-in"     0052CC "Drop-In (original Shizuku package) flavor"
L "device: samsung"     1428A0 "Samsung / One UI specific"
L "device: pixel"       4285F4 "Pixel / stock Android specific"
L "area: dhizuku"       C5DEF5 "Dhizuku compatibility and Device Owner"
L "area: root-compat"   C5DEF5 "Root bridge / su compatibility"
L "area: whats-new"     C5DEF5 "What's New / release notes / update flow"
L "area: doctor"        C5DEF5 "Service Doctor / setup help"
L "area: onboarding"    C5DEF5 "First run, pairing, setup"
L "area: crowdin"       C5DEF5 "Translations and the Crowdin sync"

# Community
L "good first issue"    7057FF "Small and well scoped; a good way into the code"
L "help wanted"         008672 "We'd love a hand with this"
L "community: thanks"   F9D0C4 "Contributed by someone outside the core team; credit in notes"
L "type: question"      D876E3 "A usage question, not a bug"
