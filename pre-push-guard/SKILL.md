---
name: pre-push-guard
description: ShizukuPlus codebase integrity checks. Use before pushing or committing to ensure no common build-breaking issues (CMake versions, missing imports, Java/Kotlin interop) exist.
---

# Pre-Push Guard

This skill automates the verification of common issues that have caused GitHub Action build failures in the ShizukuPlus project.

## Workflow

1.  **Execute the validation script**: Run the bundled bash script to perform all automated checks.
2.  **Review results**: Analyze any FAIL or WARN outputs.
3.  **Apply fixes**: Correct any identified issues before pushing.

## Automated Checks

The `scripts/pre_push_check.sh` script performs the following:

-   **CMake Version**: Verifies `cmake_minimum_required` is set to `3.22.1` (runner standard).
-   **Java/Kotlin Interop**: Ensures Java files access Kotlin objects via `.INSTANCE` (e.g., `BuildUtils.INSTANCE`).
-   **Resource Integrity**: Scans `strings.xml` for duplicate attribute names.
-   **Kotlin Imports**: Detects missing `import moe.shizuku.manager.R` when `R` is used.
-   **Ambiguous Imports**: Finds duplicate `android.os.Bundle` imports.
-   **Coroutine Contexts**: Verifies that `launch` and `Dispatchers` have their required `kotlinx.coroutines` imports, and that `lifecycleScope` has its `androidx.lifecycle` import.
-   **Syntax Integrity**: Detects Kotlin files where `import` statements are accidentally placed below the class definition.
-   **Submodule Sync**: Ensures the local `api` submodule commit actually exists on the remote before allowing a push.
-   **AAPT Errors**: Prevents `Android resource linking failed` by scanning for hardcoded package names (e.g., `moe.shizuku.privileged.api:`) in XML.
-   **Theme References**: Warns if `colorPrimary` is used directly in Kotlin without the `R.attr.` prefix.
-   **Diagnostic Quality**: Flags remaining `printStackTrace()` calls.

## Usage

```bash
bash pre-push-guard/scripts/pre_push_check.sh
```

## Common Outliers to Watch For

-   **Submodule Desync**: Always check `git status` for "modified content" in the `api` submodule.
-   **Dependency Leakage**: Ensure new library features used in `server` are also declared in `server/build.gradle`.
-   **Layout Shadowing**: In `AppBarActivity` subclasses, ensure local `binding` variables don't shadow parent fields if parent access is needed.
