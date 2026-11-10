# Workspace Rules & Guidelines for ShizukuPlus Development

These rules apply to any agent working on the ShizukuPlus project inside this workspace.

## 🚨 Build & Verification Policy
- **Primary build target**: Always use `:manager:assembleRelease` for final release validation.
- **Local testing**: Debug builds skip Sentry symbols upload and can be used for quick local iteration.
- **Signing Materials**: Do not edit `key.jks`, `signing.properties`, or files matching `secrets*` as they contain signing material.
- **CI Workflows**: CI builds are handled by GitHub Actions at `.github/workflows/app.yml`. Use `scripts/dev/*` for common tasks instead of re-deriving gradle commands.

## 🏗️ Architecture & Style Guidelines
- **Provider authorities**: Never declare an authority or permission another app owns (e.g. Dhizuku's `com.rosan.dhizuku.server.provider` / `.dhizuku_server.provider`, or stock Shizuku's `moe.shizuku.privileged.api` outside the Drop-In flavor). Android refuses to install an app whose authority is already taken, so this blocks updates for anyone with that app installed (#670). Dhizuku clients on API 2.5+ already reach us through `${applicationId}.dhizuku_server.provider`.
- **Entry Activity**: Use `MainActivity` as the primary entry activity (do not use the abstract `HomeActivity`).
- **Settings & Preferences**:
  - Settings keys are defined inside `manager/src/main/java/af/shizuku/manager/ShizukuSettings.java` (inner class `Keys`).
  - Preference XMLs reside under `manager/src/main/res/xml/settings_*.xml`.
- **Material 3 Expressive**: Use M3 components with the theme `Theme.Material3Expressive.*` (do not use Legacy AppCompat widgets).
- **App Widgets**: Use only standard framework views in `widget_*.xml`. Do not use MaterialButton/MaterialSwitch inside RemoteViews as it will crash.
