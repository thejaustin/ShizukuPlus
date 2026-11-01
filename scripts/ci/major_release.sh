#!/usr/bin/env bash
# Finds the most recent MAJOR release for the release notes' callout and prints two lines:
#   <tag>
#   <highlights; separated by "; ">
# (nothing when no release in the window qualifies).
#
# A release is the commits between one release tag and the next. It is major when:
#   1. a commit in it carries a "Release-Type: major" trailer (or "minor"/"patch" to rule it
#      out), the manual override; otherwise
#   2. it adds at least MIN_ENTRIES new user-facing CHANGELOG.md entries ("- **...**") and has
#      at least one feat: commit, or it has at least MIN_FEATS feat: commits.
# Highlights come from the CHANGELOG entries that release added (written for users), falling
# back to its feat: commit subjects.
#
# Usage: major_release.sh [current-tag]   (the current tag must already exist locally)
set -euo pipefail

MIN_ENTRIES="${MIN_ENTRIES:-4}"
MIN_FEATS="${MIN_FEATS:-3}"
MAX_HIGHLIGHTS="${MAX_HIGHLIGHTS:-4}"
TAG_RE='^(r[0-9]+|[0-9]+\.[0-9]+\.[0-9]+\.r[0-9]+|v[0-9]+\.[0-9]+\.[0-9]+\.r[0-9]+)$'

tags=$(git tag --list --sort=-creatordate | grep -E "$TAG_RE" | head -60 || true)
newer=""
for t in $tags; do
  if [ -n "$newer" ]; then
    range="${t}..${newer}"
    trailer=$(git log "$range" --no-merges --format='%(trailers:key=Release-Type,valueonly)' | tr 'A-Z' 'a-z' | grep -oE 'major|minor|patch' | head -1 || true)
    feats=$(git log "$range" --no-merges --pretty=format:'%s' | grep -cE '^feat(\([^)]+\))?!?: ' || true)
    entries=$(git diff "$t" "$newer" -- CHANGELOG.md | grep -E '^\+- \*\*' | sed -E 's/^\+- \*\*([^*]+)\*\*.*/\1/' | sed -E 's/ — .*//; s/[.:]$//' || true)
    entry_count=$(printf '%s' "$entries" | grep -c . || true)
    major=0
    case "$trailer" in
      major) major=1 ;;
      minor|patch) major=0 ;;
      *)
        if { [ "$entry_count" -ge "$MIN_ENTRIES" ] && [ "$feats" -ge 1 ]; } || [ "$feats" -ge "$MIN_FEATS" ]; then major=1; fi
        ;;
    esac
    if [ "$major" = 1 ]; then
      # Feature entries first: list each added entry with its CHANGELOG section, sorting the
      # ones under a "Fix" heading last.
      highlights=$(printf '%s\n' "$entries" | grep . | while IFS= read -r lead; do
        section=$(git show "${newer}:CHANGELOG.md" 2>/dev/null | awk -v lead="**${lead}" '
          /^### / { sec = $0 }
          index($0, "- " lead) == 1 { print sec; exit }')
        case "$section" in *[Ff]ix*) echo "1	$lead" ;; *) echo "0	$lead" ;; esac
      done | sort -s -k1,1 | cut -f2- || true)
      if [ -z "$highlights" ]; then
        highlights=$(git log "$range" --no-merges --pretty=format:'%s' | grep -E '^feat(\([^)]+\))?!?: ' | sed -E 's/^feat(\([^)]+\))?!?: //' || true)
      fi
      echo "$newer"
      printf '%s\n' "$highlights" | head -"$MAX_HIGHLIGHTS" | paste -sd ';' - | sed 's/;/; /g'
      exit 0
    fi
  fi
  newer="$t"
done
