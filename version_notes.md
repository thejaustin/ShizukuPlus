## Shizuku+ v13.6.0.r1527-shizukuplus

### 🔧 Improvements & Changes
- 6ddac79d fix: Remove duplicate SettingsActivity declaration from manifest
- e2bdead0 fix: Downgrade androidx.core to 1.15.0 to avoid manifest merger conflict
- 1a305312 fix: Simplify manifest permission removal
- 975ee767 fix: Remove DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION with tools:node="remove"
- 8cb4305b fix: Remove DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (provided by androidx.core)
- 22e00c37 fix: Add tools:node="merge" to fix manifest merger error
- 85564d6f chore: Make pre-push-check script executable
- b0fe31c7 chore: Add /**/build/ to gitignore
- 1a7c5c8e refactor: Remove all Jetpack Compose UI code and dependencies
- acf7da56 fix: Add proper tint attributes to remaining icons
- eca93233 fix: Fix ic_server_restart.xml hardcoded color
- 5ec9d5a8 fix: Fix more icons with improper viewports and hardcoded colors
- 56caa344 fix: Replace broken settings icons with clean Material Design paths
- 1f834430 fix: Restore original XML-based home screen and settings
- 25d796d4 Revert "Refactor: Complete Home UI migration to Jetpack Compose, enhance Shizuku connections, and clean up legacy components."
- 749d72a6 Refactor: Complete Home UI migration to Jetpack Compose, enhance Shizuku connections, and clean up legacy components.

