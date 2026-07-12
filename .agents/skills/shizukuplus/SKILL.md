---
name: shizukuplus
description: Guidelines and instructions for developing, testing, and troubleshooting ShizukuPlus (an enhanced Shizuku manager with OneUI 8+ fixes, Dhizuku mode, and Plus APIs).
license: Complete terms in LICENSE
metadata:
  author: Google LLC
  last-updated: '2026-07-10'
  keywords:
  - ShizukuPlus
  - Shizuku
  - Dhizuku
  - OneUI
  - AIDL
  - Android
---
# ShizukuPlus Development Specialist

This skill provides instructions for developing, building, and verifying the ShizukuPlus manager.

## Prerequisites & Environment
- Target environment: Termux / Android.
- The build uses gradle. Pinned to the `:manager:assembleRelease` task for production validation.

## Workflows
This skill enables the caller to work on the following aspects of ShizukuPlus:
- *[Architecture & Core Features](references/architecture.md)*: Details on the Unified Privilege Provider (Root, ADB, Dhizuku), OneUI 8+ overlays, and custom daemon processes.
- *[Testing & API Usage](references/testing.md)*: Instructions for testing Plus APIs, utilizing `su` wraps, process control, and debugging connections.

## Critical Constraints
- **Signing Integrity**: Never modify or expose signing configuration details (`key.jks`, `signing.properties`, etc.).
- **Material 3 Only**: Do not use Legacy AppCompat widgets in settings or preferences.
- **App Widgets Constraints**: RemoteViews widgets must only reference standard Android framework views. Do not use custom Material views inside app widgets.
