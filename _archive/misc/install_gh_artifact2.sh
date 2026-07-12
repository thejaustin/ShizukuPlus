#!/bin/bash
set -e

cd /data/data/com.termux/files/home/ShizukuPlus

echo "Finding latest run ID..."
# Extract the ID manually
gh run list --limit 1 --json databaseId > run_id.json
RUN_ID=$(grep -o '[0-9]*' run_id.json | head -n 1)

echo "Run finished. Downloading artifact..."
rm -rf gh_artifact_tmp
mkdir gh_artifact_tmp
gh run download "$RUN_ID" -D gh_artifact_tmp

APK_PATH=$(find gh_artifact_tmp -name "*.apk" | head -n 1)

if [ -n "$APK_PATH" ]; then
    echo "Installing $APK_PATH to device 192.168.1.234:34831..."
    adb -s 192.168.1.234:34831 uninstall af.shizuku.plus.api || true
    adb -s 192.168.1.234:34831 install -r -d "$APK_PATH"
    
    # Auto-grant permissions just in case
    adb -s 192.168.1.234:34831 shell pm grant af.shizuku.plus.api android.permission.POST_NOTIFICATIONS || true
    adb -s 192.168.1.234:34831 shell pm grant af.shizuku.plus.api android.permission.WRITE_SECURE_SETTINGS || true
    adb -s 192.168.1.234:34831 shell appops set af.shizuku.plus.api SYSTEM_ALERT_WINDOW allow || true
    adb -s 192.168.1.234:34831 shell appops set af.shizuku.plus.api GET_USAGE_STATS allow || true
    
    echo "Starting MainActivity..."
    adb -s 192.168.1.234:34831 shell am start -n af.shizuku.plus.api/af.shizuku.manager.MainActivity
    echo "All done!"
else
    echo "Error: No APK found in artifact."
    exit 1
fi
