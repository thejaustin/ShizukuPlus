# Shizuku+ Pre-Release Checklist

## ✅ Code Quality & Stability

### Crash Fixes (770+ crashes addressed)
- [x] **toolbarContainer not found** (494 crashes) - Fixed in aeb0a3f1
  - Added coordinator_root and appbar to tutorial/starter layouts
  - Improved AppBarActivity error messages
  
- [x] **SuperNotCalledException: MainActivity** (256 crashes) - Fixed in 130af3a0
  - Added detailed logging to track onCreate flow
  - Added recovery mechanism for failed onCreates
  - Added onStart() override for state machine updates

- [x] **port out of range:-1** (16 crashes) - Fixed in c05c177d
  - Port validation in StartWirelessAdbViewHolder (1-65535)
  - Port validation in StarterActivity

- [x] **RecyclerView LayoutTransition** (4 crashes) - Fixed in 130af3a0
  - Disabled LayoutTransition in BaseSettingsFragment
  - Disabled change animations in DefaultItemAnimator

### Sentry Issues Status
- [x] All 4 major issues marked as **resolved** in Sentry
- [x] Sentry release tracking configured
- [x] GitHub commit association enabled
- [x] Performance monitoring enabled (20% traces, 10% profiles)

## ✅ Build & CI/CD

### GitHub Actions
- [x] Build workflow passing
- [x] Sentry release integration configured
- [x] Automatic commit association with releases
- [x] Deploy tracking to production environment
- [x] Release notes with auto-generated changelog

### Build Configuration
- [x] androidx.core 1.15.0 (avoiding manifest merger issues)
- [x] Compose dependencies removed
- [x] ProGuard mapping upload enabled
- [x] Native symbols upload enabled

## ✅ Sentry Integration

### Configuration (ShizukuApplication.kt)
```kotlin
- [x] DSN configured via BuildConfig
- [x] Release format: shizuku-plus@{VERSION_NAME}
- [x] Dist: {VERSION_CODE}
- [x] Environment: development/production
- [x] Screenshot attachment: enabled
- [x] View hierarchy attachment: enabled
- [x] ANR detection: enabled (5s timeout)
- [x] NDK crash reporting: enabled
- [x] Session tracking: enabled (30s interval)
- [x] Breadcrumbs: 100 max
- [x] Performance traces: 20% sampling
- [x] Profiling: 10% sampling
```

### GitHub Actions Integration
```yaml
- [x] Sentry release creation step
- [x] Commit association (--auto)
- [x] Deploy tracking (production)
- [x] Optional (continue-on-error: true)
- [x] Only runs on push to master
```

### Release Tracking
- [x] Version format consistent: shizuku-plus@X.X.X.rXXXX-shizukuplus
- [x] GitHub release notes include Sentry link
- [x] Commits linked to each release
- [x] Source context uploaded for better stack traces

## 📋 Pre-Release Testing

### Manual Testing Checklist
- [x] App launches successfully (cold start)
- [x] Splash screen displays correctly
- [x] Home screen loads without crashes
- [x] Settings screen opens and displays all icons
- [x] Wireless ADB card displays correctly
- [x] Terminal card displays correctly (plus CLI bridge)
- [x] Start via Root card displays correctly
- [x] All home card icons visible and properly tinted
- [x] Drag-to-reorder works on home screen
- [x] Settings navigation works
- [x] ADB pairing flow works
- [x] Service starts successfully via Root
- [x] Service starts successfully via ADB
- [x] Service starts successfully via Wireless ADB

### Automated Testing
- [x] GitHub Actions build passes
- [x] Pre-push guard passes
- [x] No lint errors
- [x] No duplicate resource keys
- [x] No missing R imports

## 📊 Monitoring & Analytics

### Sentry Dashboard
- [x] Release appears in Sentry dashboard
- [x] Commits are associated with release
- [x] Crash-free sessions tracking active
- [x] Performance monitoring data flowing
- [x] ANR reports appearing (if any)

### Key Metrics to Watch (Post-Release)
- **Crash-free users**: Target > 99%
- **Crash-free sessions**: Target > 99.5%
- **ANR rate**: Target < 0.1%
- **Cold start time**: Monitor via performance traces
- **Adoption rate**: Monitor via release health

## 🚀 Release Process

### Before Release
1. [ ] All pre-release testing completed
2. [ ] Sentry issues reviewed and resolved
3. [ ] GitHub Actions build passing
4. [ ] Version name updated in build.gradle
5. [ ] CHANGELOG.md updated (if exists)
6. [ ] README.md updated with new features (if applicable)

### Release Steps
1. [ ] Merge to master branch
2. [ ] GitHub Actions builds release APK
3. [ ] Sentry release created automatically
4. [ ] GitHub release created with changelog
5. [ ] APK attached to GitHub release
6. [ ] Release notes include Sentry link

### Post-Release Monitoring
1. [ ] Monitor Sentry for new crashes (first 24h)
2. [ ] Check crash-free session rate
3. [ ] Review performance metrics
4. [ ] Monitor GitHub issues for bug reports
5. [ ] Check adoption rate in Sentry

## 📝 Release Notes Template

```markdown
## Shizuku+ v{VERSION}

### 🐛 Bug Fixes
- Fixed `plus log` not showing any activity logs due to process isolation
- Fixed `plus appops` elevating the caller's UID instead of the target package
- Fixed `plus storage` failing to list/read some privileged paths
- Removed broken recovery code in `MainActivity` that caused crashes when recovering from a startup failure
- Removed duplicate `busybox` handling in the SU Bridge interceptor

### 🔧 Improvements
- **Enhanced CLI Bridge (`plus`)**: 
  - Added `plus aicore` for AI automation: `touch`, `swipe`, `text` simulation, and XML window `dump`.
  - Added `plus storage` support for `cat`, `rm`, `mkdir`, and `stat` commands.
  - Added `plus vm` support for `status` and `delete` lifecycle operations.
  - Added server-side log buffering (up to 50 entries) for `plus log` retrieval.
  - Added full status diagnostics via `plus doctor`.
  - Added actual spoof target status in `plus spoof`.
- **Server Extensions**:
  - Implemented `elevateApp` on the server for more reliable privilege granting.
  - Added support for `mkdir` and directory listing in the `IStorageProxy` interface.
  - Enhanced feature status querying via new AIDL methods.

### 📊 Sentry Integration
- Each release now tracked in Sentry
- Direct links between GitHub releases and Sentry
- Automatic changelog from git commits
- Deploy tracking for production releases

**Sentry Release:** https://af-developments.sentry.io/projects/shizukuplus/releases/{VERSION}
```

## 🔗 Quick Links

- **Sentry Dashboard:** https://af-developments.sentry.io/projects/shizukuplus/
- **GitHub Releases:** https://github.com/thejaustin/ShizukuPlus/releases
- **GitHub Actions:** https://github.com/thejaustin/ShizukuPlus/actions
- **Sentry Issues:** https://af-developments.sentry.io/projects/shizukuplus/issues/

---

**Last Updated:** 2026-04-04
**Release Version:** 13.6.1.r{COUNT}-shizukuplus
**Status:** ✅ Ready for Release
