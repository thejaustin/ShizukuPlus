#!/usr/bin/env bash
# Lists past bug fixes (commits citing an issue, e.g. "fix: ... (#686)") that touched the files
# you're changing, so you can re-check those issues before committing. Informational only.
#
# Usage: scripts/dev/regression-watch.sh            # uncommitted changes (staged + unstaged)
#        scripts/dev/regression-watch.sh <base>     # changes in <base>..HEAD, e.g. origin/master
set -u
cd "$(git rev-parse --show-toplevel)" || exit 0

if [ $# -gt 0 ]; then
  files=$(git diff --name-only "$1"...HEAD)
else
  files=$(git diff --name-only HEAD)
fi
files=$(printf '%s\n' "$files" | command grep -E '\.(kt|java|cpp|c|h|aidl|pro|gradle|kts)$|AndroidManifest\.xml$' \
  | command grep -vE '(^|/)(docs|\.github)/|/res/values-' || true)
[ -n "$files" ] || exit 0

out=""
while IFS= read -r f; do
  [ -e "$f" ] || continue
  # Fix commits that cite an issue number (#686, Fixes #686), newest first, at most 5.
  hits=""; n=0
  for h in $(git log -n 60 --format=%h -E --grep='#[0-9]+' -- "$f"); do
    msg=$(git show -s --format=%B "$h"); subj=${msg%%$'\n'*}
    printf '%s' "$msg" | command grep -qiE '^(fix|revert)|(fix(es|ed)?|close[sd]?) #[0-9]' || continue
    issues=$(printf '%s' "$msg" | command grep -oE '#[0-9]{2,5}([^0-9a-fA-F]|$)' | tr -dc '#0-9\n' | sort -u | tr '\n' ' ')
    [ -n "$issues" ] || continue
    hits+="    $h $(printf '%s' "$subj" | cut -c1-80)  [${issues% }]"$'\n'
    n=$((n + 1)); [ $n -ge 5 ] && break
  done
  [ -n "$hits" ] && out+="  $f"$'\n'"$hits"
done <<< "$files"

if [ -n "$out" ]; then
  echo "Files you changed were part of earlier bug fixes. Re-check these issues still hold:"
  printf '%s' "$out"
fi
echo "(Cite the issue as #NNN in fix commit messages so future changes get this warning.)"
