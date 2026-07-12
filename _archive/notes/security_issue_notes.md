# 🔒 Security Fix: Exported Receivers Without Permissions in BinderRequestReceiver

## Issue Description
The `AndroidManifest.xml` explicitly marks `BinderRequestReceiver` as `exported="true"`. Although `AuthenticatedReceiver` handles intent authentication elsewhere, this receiver directly calls `ShellBinderRequestHandler.handleRequest` without verifying intent sources or caller identity.

## Progress & Notes
Attempted to fix this by:
1. Extending `BinderRequestReceiver` from `AuthenticatedReceiver` and using `onAuthenticated`.
2. Adding `auth` extra validation to `ShellRequestHandlerActivity`.
3. Reading `SHIZUKU_TOKEN` in `ShizukuShellLoader.java`.

However, the approach introduced a brittle custom authentication layer over an already existing and proven one (`AuthenticatedReceiver`). Modifying `ShizukuShellLoader.java` to fetch `System.getenv("SHIZUKU_TOKEN")` was noted in code review as incorrect and unnecessary. Additionally, `ShizukuSettings.getAuthToken()` caused build failures due to hallucination/non-existent methods.

The codebase has been reset to its original state. The fix will be completed via the Gemini CLI as per the user's instructions.
