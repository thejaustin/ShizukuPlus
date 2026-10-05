# Fork Migration — Rebasing Your Shizuku Fork onto Shizuku+

If you maintain a Shizuku fork and want to rebase it onto Shizuku+, this document provides an AI-assisted migration prompt and guidance for the process.

## When to use this

- You forked the original RikkaApps/Shizuku (or an upstream derivative) and have accumulated your own changes.
- You want those changes to run on top of Shizuku+'s codebase instead of vanilla Shizuku.
- The histories may have diverged significantly, making a literal `git rebase` risky.

## AI-assisted migration prompt

The following prompt was contributed by [@djbclark (Daniel JB Clark)](https://github.com/djbclark), who migrated [frdminc/ShizukuTendCF](https://github.com/frdminc/ShizukuTendCF) onto Shizuku+ and published the prompt for the community to reuse. Use it with Claude Code, Claude claude.ai, or any capable AI coding assistant.

---

> You are helping me move my Shizuku fork onto ShizukuPlus while preserving the changes that are genuinely mine. Treat this as a careful migration, not as a presumed literal Git rebase. Work from repository evidence, explain decisions, preserve recoverability, and do not claim success without verification.
>
> Start by asking me for the information below. Ask in one concise, organized message and wait for my answers before making changes:
>
> - Which fork repository and remote contain my work, and which remote/branch is its current source of truth?
> - Which ShizukuPlus repository and branch should be the target?
> - What old upstream repository and branch did my fork originally follow, if known?
> - Which local and remote branches must be preserved, and am I authorizing any push, branch replacement, or other remote write? Do not assume permission to rewrite or force-push a branch.
> - Which Android devices, OS versions, Android variants, and app flavors are available for testing? Which can I use for destructive or migration-sensitive checks?
> - How is the app signed for development and release (local signing, CI signing, or both)? Is continuity with an existing signing identity required? Do not ask me to send passwords, private keys, keystores, tokens, or other secrets; ask only how signing is configured and where the authorized material is managed.
> - Are there constraints on app identity, package names, branding, API/submodule selection, release compatibility, and whether the resulting fork should track ShizukuPlus's moving branch or pin a reviewed revision?
> - May you run builds and tests locally, and what environment or operational restrictions should you observe?
>
> Once I answer, inspect the repository's agent instructions, migration/build documentation, CI workflows, remotes, branches, submodules, and current worktree state. Start with read-only inspection. Preserve unrelated local changes, never overwrite or discard work, and report any pre-existing dirty state before touching affected files. Do not expose secrets or signing material in logs, diffs, reports, or commands. Before the first staging operation, check whether the target base ignores keystores and signing files; add appropriate ignore protection before staging if needed, without reading or copying any secret file.
>
> First establish the migration strategy from Git history and repository structure. Compare the actual merge base, ancestry, and relevant trees. If the histories are compatible, determine whether a normal rebase is appropriate; if upstream rewrote history or the divergence makes that unsafe, use a fresh branch from the selected ShizukuPlus revision and port only the verified delta. Do not treat a rewritten history, branch name, remote-tracking ref, or local copy as authoritative without checking it. Fetch before relying on remote branch tips. If a backup is appropriate, verify that it points to the intended source commit. Never replace a shared or public branch, force-push, or alter a remote without my explicit authorization and a final confirmation immediately before that action.
>
> Before porting anything, discover my fork's actual changes relative to its original upstream base. Inspect source diffs and history, not just READMEs, issue descriptions, commit messages, or remembered feature lists. Include user-visible features, bug fixes, security changes, build and CI changes, configuration, resources/translations, packaging, and documentation. Separate inherited upstream behavior from changes introduced by my fork; identify reverted or experimental changes where evidence permits. Then inspect the corresponding ShizukuPlus implementation directly to determine what is already present and how it works. Cite paths and relevant symbols or line ranges from both trees for each conclusion.
>
> Produce an overlap matrix with columns for: change/feature; evidence in my fork; evidence in ShizukuPlus; classification (already present, partially present, absent, or uncertain); and proposed action. List uncertain cases and operator decisions separately. Do not port something merely because its file differs: determine whether the behavior is genuinely missing, whether the upstream implementation supersedes it, and what regressions or compatibility changes a port could introduce.
>
> Show me the complete inventory and matrix, then ask exactly: "Did I find them all?" Stop and wait for my answer before modifying files or beginning the port. Incorporate anything I add or correct, update the inventory, and resolve material uncertainties with me before proceeding.
>
> After I confirm the inventory, propose a concise migration plan and ask for decisions that remain open, including the history/branch strategy, API submodule or dependency source and revision, pin-versus-track policy, app identity/branding constraints, optional changes, and release/signing compatibility. Explain tradeoffs based on this repository's evidence. Do not silently choose an irreversible option.
>
> Implement the approved plan on a dedicated working branch. Keep changes small, reviewable, and grouped by purpose. Prefer ShizukuPlus's implementation when it already provides the needed behavior; port only confirmed missing changes and adapt them to the target architecture rather than mechanically copying old files. Preserve upstream conventions and APIs. Keep a migration record of the inventory, decisions, applied changes, checks, and unresolved follow-ups so another developer can resume without relying on chat history.
>
> Treat submodules and API dependencies as an independent compatibility boundary. Inspect the target repository's gitlink or dependency revision and compare it with the corresponding API repository's available revisions and declarations. Server code may be ahead of the API revision it pins, or an API change may not yet be consumed by the manager. Check this explicitly before attributing compile failures to the port. Select a compatible revision deliberately, update the gitlink/dependency in a clearly scoped change, and repeat this check on future upstream syncs.
>
> Use evidence-first debugging for binder and service failures: capture relevant logs and transaction/service state before changing behavior; trace the failing call across client, binder interface/proxy, transaction handling, and server implementation; then make the narrowest supported fix. Do not suppress exceptions, silently return success, or infer a cause from a symptom alone. Verify transaction numbering and compatibility against the API actually used by each client.
>
> Review security and compatibility at each affected boundary. Check exported components and manifest permissions, caller identity and authorization, input validation, file/path/provider restrictions, permission persistence and revocation semantics, sensitive data in errors/logs, and whether newer platform APIs are guarded for the project's minimum supported Android version. In permission logic, distinguish "no stored decision" from an explicitly revoked or cleared decision; defaults must not undo a user's revocation. Verify trust anchors and signing certificates against the intended, authorized release identity and documented source. Never embed a private key or secret. Avoid broad exception handling unless the platform/API contract requires it and failures remain visible and safely handled.
>
> Check app IDs, flavors, resource overrides, labels, explicit broadcast targets, update endpoints, version codes, and installation/upgrade behavior wherever the migration changes identity or packaging. Inspect CI end to end: ensure release signing fails closed when required secrets are unavailable, secrets are passed through the workflow's supported environment mechanism rather than interpolated into shell commands, and release artifacts are actually signed with the intended certificate. Check optional CI steps for missing credentials (including reporting/symbol-upload steps) so a missing token does not create a misleading build failure or a success-shaped skip.
>
> Build and test using the project's documented commands and the selected flavors. Establish a baseline where feasible, run focused checks after each related change, and run the project's required release validation before declaring completion. Do not trust an unverified "it builds" report. When a check fails, determine whether it is a pre-existing upstream/API mismatch or a migration regression, fix issues introduced or exposed by the migration when in scope, and rerun the relevant checks. Report exact commands and outcomes, plus anything unavailable or skipped and why.
>
> Test on real devices from the set I identified before declaring the migration done. Cover the relevant install/upgrade path, start/stop and binder connection, authorization and permission behavior, reboot or wireless/ADB paths if affected, and any migrated feature that depends on Android or OEM behavior. Validate the actual app flavor and signing identity used for the test. Check device logs when behavior fails, fix issues found en route, and repeat the affected device checks. If a device or path cannot be tested, state that limitation plainly; do not substitute a build result for device evidence or claim full validation.
>
> Before any final branch replacement or release, present the completed change inventory, security review outcome, build/test/device evidence, remaining risks, and exact target/source revisions. Pause for my explicit approval before any authorized remote rewrite or release action.
>
> END by performing an upstream-contribution pass after the migration is stable and the agreed device checks are complete. Identify generally useful fixes and features separately from fork-specific behavior. Generalize fork-specific code, configuration, names, or assumptions before proposing upstream changes; do not send private deployment details upstream unnecessarily. Split contributions into small, focused pull requests, and use focused issues for work that is not ready to propose as code. Arrange at least two independent agent reviews of each proposed PR's changes before opening that PR; report the review outcomes and address findings first. For anything that appears exploitable, do not publish exploit details in an issue, pull request, public report, or commit: use the upstream project's private security disclosure channel and coordinate disclosure responsibly. Conclude by asking me to approve the proposed upstream issues/PRs and any private disclosure, without opening or publishing them unless I explicitly authorize that action.

---

## Tips specific to Shizuku+

- **Read `CLAUDE.md` first** — it has the critical crash rules, build commands, and ProGuard keep requirements that the AI prompt alone won't know. Share it with your AI assistant at the start of your session.
- **API submodule boundary** — Shizuku+ uses a private `api/` submodule for all AIDL definitions. If your fork adds new IPC calls, you'll need to add them here and update the submodule pointer. See [CONTRIBUTING.md](CONTRIBUTING.md).
- **Server / manager split** — the server (`server/`) runs as root or shell; the manager (`manager/`) is the UI app. Changes to one usually require matching changes to the other.
- **Binder transaction codes are explicit** — Shizuku+ uses `= N` explicit codes in AIDL, not positional. If your fork added methods in the middle of an interface, you need to assign them explicit codes that don't collide with existing ones.
- **ProGuard keeps are hand-maintained** — `manager/proguard-rules.pro` must explicitly keep every class accessed via reflection. Missing keeps cause silent release crashes that don't appear in debug builds.

## Lessons from a completed migration

These come from moving one long-lived fork onto Shizuku+; they are not specific to that fork.

- **Rename with a build-time resource overlay, not by editing `strings.xml`.** A Gradle task that reads upstream's `values*/strings.xml` and generates an overriding resource file for your flavor leaves upstream's strings and every translation byte-identical, so later rebases do not conflict in each locale. Keep an explicit list of string names that must keep the upstream name (the API and permission names other apps request, the "Shizuku+ API" names, URLs) and match by name rather than by English phrasing, so the exceptions hold in every language.
- **Restart the server after every in-place install.** `adb install -r` leaves the old server process running the previous build's code, so the first test after a rebase can silently exercise old server code. Stop and start the service (or compare the running server's APK path with the installed one) before testing.
- **Re-test with apps built against the stock Shizuku API after any binder change.** Transaction codes and the legacy compatibility path are easy to break in a merge, and the failure shows up only in client apps, as a call landing on the wrong method or an exception with no server-side log.
- **Test the authorisation path, not just "it starts".** If you add automation that starts the service unattended, revoke USB debugging authorisations and check that each explicit start raises exactly one "Allow USB debugging?" dialog and that nothing unattended raises another.

## Contributing back

If your migration surfaces a bug fix or genuinely useful feature that isn't fork-specific, please consider opening a PR against `master`. See [CONTRIBUTING.md](CONTRIBUTING.md) for submission guidelines.
