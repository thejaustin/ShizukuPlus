# Contributing to Shizuku+

Thanks for considering a contribution — bug fixes, translations, and new Plus API work are all welcome.

## Migrating your own Shizuku fork onto Shizuku+

If you maintain a fork of the original Shizuku (or another derivative) and want to move onto Shizuku+, see **[FORK_MIGRATION.md](FORK_MIGRATION.md)** for an AI-assisted migration prompt and Shizuku+-specific tips. This is distinct from contributing a PR — it's for fork maintainers porting their own accumulated changes.

## Before you start

- **Check existing issues and PRs first.** A quick search saves everyone time — see [open issues](https://github.com/thejaustin/ShizukuPlus/issues) and [open PRs](https://github.com/thejaustin/ShizukuPlus/pulls).
- **For anything non-trivial, open an issue first** to discuss the approach before writing code. Small fixes (typos, obvious bugs, translations) don't need this.
- **Read the [wiki](https://github.com/thejaustin/ShizukuPlus/wiki)** for architecture context, especially [Shizuku vs. Shizuku+](https://github.com/thejaustin/ShizukuPlus/wiki/Shizuku-vs-Shizuku%2B) if you're new to the codebase.

## Development setup

- No local Android SDK is required to contribute — this project builds via GitHub Actions only (`.github/workflows/app.yml`). If you have a local SDK, `./gradlew :manager:assembleRelease` is the verification build.
- Two build flavors share most of the codebase: `Shizukuplus` (`af.shizuku.plus.api`, coexists with stock Shizuku) and `Dropin` (`moe.shizuku.privileged.api`, replaces it). Check which flavor a change needs to apply to.
- Never commit or modify `key.jks`, `signing.properties`, or anything matching `secrets*`.

## Submitting a PR

- Keep PRs focused — one fix or feature per PR is much easier to review than a bundle.
- Commit messages: `<type>: <what> - <why>` (e.g. `fix: null-check in FooBar.init - crashes on API 29`).
- Rebase onto current `master` before requesting review if it's been open a while — conflicts are much easier for you to resolve than for a reviewer.
- Describe how you tested the change (device/emulator + Android version) — there's no CI-run instrumented test coverage for most UI flows, so this is genuinely useful signal, not a formality.

## Translations

Translation contributions are very welcome. If you're adding or updating a locale, please localize the full string set rather than a partial pass where practical — see `manager/src/main/res/values-*/` for existing locales as a reference.

## Security issues

Please **do not** open a public issue for a security vulnerability — see [SECURITY.md](SECURITY.md) for responsible disclosure.
