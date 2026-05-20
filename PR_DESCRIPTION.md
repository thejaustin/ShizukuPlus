# ⚡ IPC Query Optimization in `ShizukuConfigManager.java`

💡 **What:**
Optimized the initialization process in `ShizukuConfigManager` to remove repetitive IPC queries inside tight loops. Pre-fetched `PackageInfo` map based on `UserManagerApis.getUserIdsNoThrow()` and `PackageManagerApis.getInstalledPackagesNoThrow()`.

🎯 **Why:**
The previous code queried `PackageManagerApis.getPackagesForUidNoThrow(uid)` iteratively for every package entry inside `config.packages`, leading to multiple IPC queries inside a loop causing high latency/CPU usage. It also reiterated over users and packages later. Now, everything is efficiently aggregated into a single set of package lists locally mapped by `uid`.

📊 **Measured Improvement:**
I was unable to show a direct performance improvement through isolated benchmark tests since this is an Android-system-dependent IPC method optimization that relies heavily on underlying Android `ActivityManager/PackageManager` state. Given standard IPC overhead in Android, avoiding multiple binder transactions iteratively directly results in lower system CPU consumption and faster initialization speeds in `ShizukuConfigManager`.
