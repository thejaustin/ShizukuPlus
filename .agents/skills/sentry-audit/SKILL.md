---
name: sentry-audit
description: Fetches unresolved Sentry issues and maps stack traces to local codebase for ShizukuPlus.
license: Complete terms in LICENSE
metadata:
  author: Google LLC
  keywords:
  - sentry
  - audit
  - crashes
  - debugging
---
# Sentry Audit Skill

This skill minimizes token usage while extracting actionable crash data from the Sentry REST API and mapping it to the local codebase.

## Execution Steps:

1. **Fetch Unresolved Issues:**
   Run a `curl` command against the Sentry API to fetch unresolved issues. Extract ONLY the `id`, `shortId`, `title`, and `culprit`.
   ```bash
   curl -s -H 'Authorization: Bearer <SENTRY_TOKEN>' "https://sentry.io/api/0/projects/af-developments/shizukuplus/issues/?query=is:unresolved" | jq -c '.[] | {id, shortId, title, culprit}'
   ```
   *Note: Do NOT fetch the full JSON dump into context; it is massive and wastes tokens.*

2. **Fetch Stack Trace for Top Issue:**
   Pick the highest priority issue ID and fetch its exact stack trace.
   ```bash
   curl -s -H 'Authorization: Bearer <SENTRY_TOKEN>' "https://sentry.io/api/0/issues/<ISSUE_ID>/events/latest/" | jq -r '.entries[] | select(.type=="exception") | .data.values[].stacktrace.frames[] | "\(.filename):\(.lineNo) - \(.function)"'
   ```
   *Note: Using `jq` filters the payload down to just filename, line number, and function, drastically saving context space.*

3. **Map to Codebase:**
   Use the `grep_search` tool to find the corresponding file in `manager/src/main/` based on the `.filename` from the stack trace.

4. **Repair:**
   Analyze the bug and use the `replace_file_content` or `multi_replace_file_content` tool to patch the error.

5. **Resolve via API:**
   Once pushed to master, automatically resolve the issue on Sentry:
   ```bash
   curl -s -X PUT -H 'Authorization: Bearer <SENTRY_TOKEN>' -H "Content-Type: application/json" -d '{"status":"resolved"}' "https://sentry.io/api/0/issues/<ISSUE_ID>/"
   ```
