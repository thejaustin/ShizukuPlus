package rikka.shizuku.server;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;
import static rikka.shizuku.server.ServerConstants.MANAGER_APPLICATION_ID;
import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.ddm.DdmHandleAppName;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import kotlin.collections.ArraysKt;
import af.shizuku.api.BinderContainer;
import rikka.core.util.BuildUtils;
import af.shizuku.common.util.OsUtils;
import af.shizuku.server.IRemoteProcess;
import af.shizuku.server.IShizukuApplication;
import af.shizuku.server.IVirtualMachineManager;
import af.shizuku.server.IStorageProxy;
import af.shizuku.server.IAICorePlus;
import af.shizuku.server.IWindowManagerPlus;
import af.shizuku.server.IContinuityBridge;
import af.shizuku.server.IOverlayManagerPlus;
import af.shizuku.server.INetworkGovernorPlus;
import af.shizuku.server.IActivityManagerPlus;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.rish.RishConfig;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.IContentProviderUtils;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;
import af.shizuku.common.util.UserHandleCompat;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;

public class ShizukuService extends Service<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> {

    public static void main(String[] args) {
        DdmHandleAppName.setAppName("shizuku_plus_server", 0);
        RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"));

        Looper.prepareMainLooper();
        new ShizukuService();
        Looper.loop();
    }

    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("service " + name + " is not started, waiting...");
                ServiceManager.class.getMethod("waitForService", String.class).invoke(null, name);
            } catch (Exception e) {
                LOGGER.w(e.getMessage(), e);
            }
        }
    }

    public static ApplicationInfo getManagerApplicationInfo() {
        return PackageManagerApis.getApplicationInfoNoThrow(MANAGER_APPLICATION_ID, 0, 0);
    }

    @SuppressWarnings({"FieldCanBeLocal"})
    private final Handler mainHandler = rikka.shizuku.server.ktx.HandlerKt.getMainHandler();
    //private final Context systemContext = HiddenApiBridge.getSystemContext();
    private final ShizukuClientManager clientManager;
    private static final List<String> serverLogs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final int MAX_SERVER_LOGS = 100;
    private final ShizukuConfigManager configManager;
    private final int managerAppId;
    private final VirtualMachineManagerImpl virtualMachineManager = new VirtualMachineManagerImpl();
    private final StorageProxyImpl storageProxy = new StorageProxyImpl();
    private final AICorePlusImpl aiCorePlus;
    private final WindowManagerPlusImpl windowManagerPlus = new WindowManagerPlusImpl();
    private final ContinuityBridgeImpl continuityBridge = new ContinuityBridgeImpl();
    private final OverlayManagerPlusImpl overlayManagerPlus = new OverlayManagerPlusImpl();
    private final NetworkGovernorPlusImpl networkGovernorPlus = new NetworkGovernorPlusImpl();
    private final ActivityManagerPlusImpl activityManagerPlus = new ActivityManagerPlusImpl();

    public ShizukuService() {
        super();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.e(throwable, "Uncaught exception in server thread " + thread.getName());
            try {
                // Give some time for the event to be dispatched before the process dies
                Thread.sleep(500);
            } catch (Throwable ignored) {
            }
            System.exit(1);
        });

        HandlerUtil.setMainHandler(mainHandler);

        LOGGER.i("starting server...");

        waitSystemService("package");
        waitSystemService(Context.ACTIVITY_SERVICE);
        waitSystemService(Context.USER_SERVICE);
        waitSystemService(Context.APP_OPS_SERVICE);

        ApplicationInfo ai = getManagerApplicationInfo();
        if (ai == null) {
            System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
        }

        assert ai != null;
        managerAppId = ai.uid;

        configManager = getConfigManager();
        clientManager = getClientManager();
        aiCorePlus = new AICorePlusImpl(clientManager, this);

        ApkChangedObservers.start(ai.sourceDir, () -> {
            if (getManagerApplicationInfo() == null) {
                LOGGER.w("manager app is uninstalled in user 0, exiting...");
                System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
            }
        });

        BinderSender.register(this);

        ((Logger) LOGGER).setEventDispatcher((priority, tag, message, throwable) -> {
            List<ClientRecord> records = clientManager.findClients(managerAppId);
            for (ClientRecord record : records) {
                if (record.client != null) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("priority", priority);
                        json.put("tag", tag);
                        json.put("message", message);
                        if (throwable != null) {
                            json.put("stacktrace", Log.getStackTraceString(throwable));
                        }
                        record.client.dispatchSentryEvent(json.toString());
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        mainHandler.post(() -> {
            sendBinderToClient();
            sendBinderToManager();
        });
    }

    @Override
    public ShizukuUserServiceManager onCreateUserServiceManager() {
        return new ShizukuUserServiceManager();
    }

    @Override
    public ShizukuClientManager onCreateClientManager() {
        return new ShizukuClientManager(getConfigManager());
    }

    @Override
    public ShizukuConfigManager onCreateConfigManager() {
        return new ShizukuConfigManager();
    }

    @Override
    public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
        return UserHandleCompat.getAppId(callingUid) == managerAppId;
    }

    private int checkCallingPermission() {
        try {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            if (ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, pid, uid) == PackageManager.PERMISSION_GRANTED)
                return PackageManager.PERMISSION_GRANTED;
            if (ActivityManagerApis.checkPermission(ServerConstants.PERMISSION_LEGACY, pid, uid) == PackageManager.PERMISSION_GRANTED)
                return PackageManager.PERMISSION_GRANTED;
            if (ActivityManagerApis.checkPermission(ServerConstants.PERMISSION_ORIGINAL, pid, uid) == PackageManager.PERMISSION_GRANTED)
                return PackageManager.PERMISSION_GRANTED;
            return PackageManager.PERMISSION_DENIED;
        } catch (Throwable tr) {
            LOGGER.w(tr, "checkCallingPermission");
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        if (UserHandleCompat.getAppId(callingUid) == managerAppId) {
            return true;
        }
        if (clientRecord == null && checkCallingPermission() == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    @Override
    public void exit() {
        enforceManagerPermission("exit");
        LOGGER.i("exit");
        System.exit(0);
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        enforceManagerPermission("attachUserService");

        super.attachUserService(binder, options);
    }

    @Override
    public void attachApplication(IShizukuApplication application, Bundle args) {
        if (application == null || args == null) {
            return;
        }

        String requestPackageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);
        if (requestPackageName == null) {
            return;
        }
        int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);

        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        boolean isManager;
        ClientRecord clientRecord = null;

        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid);
        if (!packages.contains(requestPackageName)) {
            LOGGER.w("Request package " + requestPackageName + "does not belong to uid " + callingUid);
            throw new SecurityException("Request package " + requestPackageName + "does not belong to uid " + callingUid);
        }

        isManager = MANAGER_APPLICATION_ID.equals(requestPackageName);

        synchronized (this) {
            ClientRecord existing = clientManager.findClient(callingUid, callingPid);
            if (existing == null) {
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion);
                if (clientRecord == null) {
                    LOGGER.w("Add client failed");
                    return;
                }
            } else {
                clientRecord = existing;
            }
        }

        LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid);

        int replyServerVersion = ShizukuApiConstants.SERVER_VERSION;
        if (apiVersion == -1) {
            // ShizukuBinderWrapper has adapted API v13 in dev.rikka.shizuku:api 12.2.0, however
            // attachApplication in 12.2.0 is still old, so that server treat the client as pre 13.
            // This finally cause transactRemote fails.
            // So we can pass 12 here to pretend we are v12 server.
            replyServerVersion = 12;
        }

        Bundle reply = new Bundle();
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
        reply.putInt(BIND_APPLICATION_SERVER_VERSION, replyServerVersion);
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);
        if (!isManager) {
            ClientRecord record = Objects.requireNonNull(clientRecord);
            // When the server runs as root, all attached clients are automatically granted access.
            // This lets apps like Swift Backup work without an explicit grant dialog in root mode.
            if (OsUtils.getUid() == 0 && !record.allowed) {
                record.allowed = true;
            }
            reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, record.allowed);
            reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        } else {
            try {
                PermissionManagerApis.grantRuntimePermission(MANAGER_APPLICATION_ID,
                        WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid));
            } catch (Throwable e) {
                LOGGER.w(e, "grant WRITE_SECURE_SETTINGS");
            }
        }
        try {
            // First try using the current descriptor (af.shizuku.server.IShizukuApplication)
            application.bindApplication(reply);
        } catch (Throwable e) {
            // If it fails (likely due to interface descriptor mismatch on the client side),
            // try using the legacy descriptor (moe.shizuku.server.IShizukuApplication)
            LOGGER.w("attachApplication via current descriptor failed, trying legacy descriptor for " + requestPackageName);
            try {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken("moe.shizuku.server.IShizukuApplication");
                    // 1 = bindApplication(Bundle)
                    data.writeInt(1);
                    reply.writeToParcel(data, 0);
                    application.asBinder().transact(1, data, null, IBinder.FLAG_ONEWAY);
                    LOGGER.i("Successfully sent bindApplication via legacy descriptor to " + requestPackageName);
                } finally {
                    data.recycle();
                }
            } catch (Throwable e2) {
                LOGGER.e(e2, "attachApplication legacy also failed for " + requestPackageName);
            }
        }
    }

    private final java.util.Map<String, Boolean> featureEnabledMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> plusSettingsMap = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isFeatureEnabled(String key) {
        if (featureEnabledMap.containsKey(key)) return featureEnabledMap.get(key);
        // The manager and the server don't always agree on whether a feature key carries the
        // "_enabled" suffix. Normalize in BOTH directions so a caller checking "foo_enabled"
        // still resolves a value the manager synced as "foo" (and vice versa).
        if (key.endsWith("_enabled")) {
            String stripped = key.substring(0, key.length() - "_enabled".length());
            if (featureEnabledMap.containsKey(stripped)) return featureEnabledMap.get(stripped);
        } else if (featureEnabledMap.containsKey(key + "_enabled")) {
            return featureEnabledMap.get(key + "_enabled");
        }
        return featureEnabledMap.getOrDefault(key, false);
    }

    @Override
    public boolean checkPlusFeatureEnabled(String key) {
        return isFeatureEnabled(key);
    }

    @Override
    protected boolean isBinderCallBlocked(int uid, String descriptor, int code) {
        if (!isFeatureEnabled("binder_firewall")) return false;

        // The manager app (the owner of this service) is always allowed
        if (UserHandleCompat.getAppId(uid) == managerAppId) return false;

        boolean isBlocked = false;

        // Block sensitive system operations for all other apps if firewall is active
        if ("android.os.IPowerManager".equals(descriptor)) {
            // 17 = reboot, 18 = shutdown
            if (code == 17 || code == 18) isBlocked = true;
        } else if ("android.app.IActivityManager".equals(descriptor)) {
            // 50/78 = forceStopPackage, 61 = clearApplicationUserData, 103 = killBackgroundProcesses
            // Codes vary by API version, using common ones as heuristic
            if (code == 50 || code == 61 || code == 78 || code == 103) isBlocked = true;
        } else if ("android.content.pm.IPackageManager".equals(descriptor)) {
            // 26 = deletePackage, 13 = installPackage
            if (code == 26 || code == 13) isBlocked = true;
        }
        
        // Dynamic policy from settings
        String blockedDescriptors = plusSettingsMap.get("firewall_blocked_descriptors");
        if (blockedDescriptors != null && !blockedDescriptors.isEmpty()) {
            for (String blocked : blockedDescriptors.split(",")) {
                if (descriptor.equals(blocked.trim())) isBlocked = true;
            }
        }

        if (isBlocked) {
            LOGGER.w("Binder call blocked: UID=%d, Descriptor=%s, Code=%d", uid, descriptor, code);
        }

        return isBlocked;
    }

    private static int TRANSACTION_getPackageInfo = -1;
    private static int TRANSACTION_getApplicationInfo = -1;
    private static int TRANSACTION_getPackageUid = -1;

    static {
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            try {
                java.lang.reflect.Field f1 = stub.getDeclaredField("TRANSACTION_getPackageInfo");
                f1.setAccessible(true);
                TRANSACTION_getPackageInfo = f1.getInt(null);
            } catch (Exception ignore) {}
            try {
                java.lang.reflect.Field f2 = stub.getDeclaredField("TRANSACTION_getApplicationInfo");
                f2.setAccessible(true);
                TRANSACTION_getApplicationInfo = f2.getInt(null);
            } catch (Exception ignore) {}
            try {
                java.lang.reflect.Field f3 = stub.getDeclaredField("TRANSACTION_getPackageUid");
                f3.setAccessible(true);
                TRANSACTION_getPackageUid = f3.getInt(null);
            } catch (Exception ignore) {}
        } catch (Throwable t) {
            LOGGER.w(t, "Shadow: Failed to dynamically look up IPackageManager transaction codes");
        }
    }

    @Override
    protected boolean handleShadowBinderTransaction(IBinder target, int code, Parcel data, Parcel reply, int flags) {
        try {
            String descriptor = target.getInterfaceDescriptor();
            // Shadowing IPackageManager to hide specific apps or spoof Magisk presence
            if ("android.content.pm.IPackageManager".equals(descriptor)) {
                // Save position to restore if we don't handle it
                int pos = data.dataPosition();
                data.setDataPosition(0);
                
                String packageName = null;
                try {
                    data.enforceInterface(descriptor);
                    packageName = data.readString();
                } catch (Exception e) {
                    // Fallback or ignore
                }
                
                // Restore position immediately after reading what we need
                data.setDataPosition(pos);

                // Spoof original Shizuku package to fix #248 and #249 (client app hardcoded checks)
                boolean isShizukuSpoof = "moe.shizuku.privileged.api".equals(packageName);
                
                if (!isFeatureEnabled("shadow_binder") && !isFeatureEnabled("root_magisk_mocking") && !isShizukuSpoof) return false;

                // Binder-level Magisk & Framework Spoofing
                if ((isFeatureEnabled("root_magisk_mocking") && packageName != null && 
                    (packageName.equals("com.topjohnwu.magisk") || 
                     packageName.equals("org.lsposed.manager") || 
                     packageName.equals("eu.chainfire.supersu"))) || isShizukuSpoof) {
                     
                    LOGGER.i("Shadow: Spoofing package presence from IPackageManager call for %s (code %d)", packageName, code);
                    reply.writeNoException();
                    try {
                        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
                        info.packageName = packageName;
                        info.versionName = "26.4";
                        info.versionCode = 26400;
                        info.applicationInfo = new android.content.pm.ApplicationInfo();
                        info.applicationInfo.packageName = packageName;
                        info.applicationInfo.sourceDir = "/data/app/" + packageName + "-mocked/base.apk";
                        info.applicationInfo.flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM;
                        
                        if (code == TRANSACTION_getPackageInfo) {
                            reply.writeTypedObject(info, 1);
                            return true;
                        } else if (code == TRANSACTION_getApplicationInfo) {
                            reply.writeTypedObject(info.applicationInfo, 1);
                            return true;
                        } else if (code == TRANSACTION_getPackageUid) {
                            reply.writeInt(10000); // Mock UID
                            return true;
                        }
                        // Fallback: let the system handle it or return false
                        return false;
                    } catch (Exception e) {
                        LOGGER.e("Shadow: Failed to spoof package %s", packageName);
                    }
                }
                String hiddenPackages = plusSettingsMap.get("shadow_hidden_packages");
                if (hiddenPackages != null && packageName != null && !packageName.isEmpty()) {
                    boolean shouldHide = false;
                    for (String p : hiddenPackages.split(",")) {
                        if (packageName.equals(p.trim())) {
                            shouldHide = true;
                            break;
                        }
                    }

                    if (shouldHide) {
                        // Only intercept known methods to prevent Type Confusion crashes
                        if (code == TRANSACTION_getPackageInfo || code == TRANSACTION_getApplicationInfo || code == TRANSACTION_getPackageUid) {
                            LOGGER.i("Shadow: Hiding package %s from IPackageManager call (code %d)", packageName, code);
                            reply.writeNoException();
                            
                            if (code == TRANSACTION_getPackageInfo || code == TRANSACTION_getApplicationInfo) { 
                                reply.writeTypedObject(null, 0); // null ApplicationInfo/PackageInfo
                            } else {
                                reply.writeInt(0); // 0 UID
                            }
                            return true;
                        }
                    }
                }
            }
            
            // Shadowing IActivityManager to mock process states
            if ("android.app.IActivityManager".equals(descriptor)) {
                // Future expansion: hide processes from Task Manager
            }
            
        } catch (Exception e) {
            LOGGER.e("Shadow Binder error", e);
        }

        return false;
    }

    @Override
    public void updatePlusFeatureEnabled(String key, boolean enabled) {
        enforceManagerPermission("updatePlusFeatureEnabled");
        LOGGER.i("Plus Feature Update: " + key + " -> " + enabled);
        featureEnabledMap.put(key, enabled);
    }

    @Override
    public void setPlusSetting(String key, String value) {
        enforceManagerPermission("setPlusSetting");
        LOGGER.i("Plus Setting Update: " + key + " -> " + value);
        plusSettingsMap.put(key, value);
    }

    private void dispatchLog(String packageName, String action) {
        if (!isFeatureEnabled("enable_activity_log")) return;

        // Store log in internal buffer for CLI access
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        String logEntry = String.format("[%s] %s: %s", time, packageName, action);
        synchronized (serverLogs) {
            if (serverLogs.size() >= MAX_SERVER_LOGS) {
                serverLogs.remove(0);
            }
            serverLogs.add(logEntry);
        }

        mainHandler.post(() -> {

            ApplicationInfo ai = getManagerApplicationInfo();
            if (ai == null) return;

            List<ClientRecord> records = clientManager.findClients(ai.uid);
            for (ClientRecord record : records) {
                try {
                    record.client.dispatchLog("", packageName, action);
                } catch (Throwable e) {
                    LOGGER.w(e, "Failed to dispatch log for package %s", packageName);
                }
            }
        });
    }

    private boolean isCatastrophicCommand(String[] cmd) {
        if (cmd == null || cmd.length == 0) return false;
        String fullCmd = String.join(" ", cmd);
        // Block formatting block devices
        if (fullCmd.contains("mkfs") || fullCmd.contains("mke2fs")) return true;
        // Block wiping data
        if (fullCmd.contains("rm -rf /data") || fullCmd.contains("rm -rf /storage") || fullCmd.contains("rm -rf /system")) return true;
        // Block dd to raw block devices (unless inside a magisk module context, but we want to prompt)
        if (fullCmd.startsWith("dd ") && fullCmd.contains("of=/dev/block/")) return true;
        return false;
    }

    @Override
    public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        ClientRecord caller = clientManager.findClient(callingUid, callingPid);
        String callingPkg = (caller != null) ? caller.packageName : "unknown";
        
        // Catastrophic Command Interceptor (Storage Safety)
        if (isCatastrophicCommand(cmd)) {
            LOGGER.w("Catastrophic command blocked from execution by %s: %s", callingPkg, String.join(" ", cmd));
            // In a full implementation, we would broadcast an intent to ShizukuManager to show an AppOps prompt
            // and wait on a CountDownLatch for the user's Binder response. 
            // For now, we instantly block to guarantee safety.
            boolean userApproved = false; // Mock user denial
            if (!userApproved) {
                throw new SecurityException("Permission denied (Shizuku Storage Safety Protection)");
            }
        }
        
        // Global System File Redirection Proxy: transparently map read-only system files to user-writable proxies
        if (isFeatureEnabled("root_build_prop_redirect") && cmd != null) {
            String[] proxyTargets = {
                "/system/build.prop", "/vendor/build.prop",
                "/system/etc/mixer_paths.xml", "/vendor/etc/mixer_paths.xml",
                "/system/etc/audio_effects.conf", "/vendor/etc/audio_effects.conf",
                "/system/etc/gps.conf"
            };
            
            for (int i = 0; i < cmd.length; i++) {
                if (cmd[i] == null) continue;
                for (String target : proxyTargets) {
                    if (cmd[i].contains(target)) {
                        String fileName = new java.io.File(target).getName();
                        String proxyPath = "/data/adb/shizuku/" + fileName;
                        
                        try {
                            Runtime.getRuntime().exec(new String[]{"mkdir", "-p", "/data/adb/shizuku"}).waitFor();
                            java.io.File dest = new java.io.File(proxyPath);
                            if (!dest.exists()) {
                                String sourcePath = target;
                                Runtime.getRuntime().exec(new String[]{"cp", sourcePath, proxyPath}).waitFor();
                            }
                        } catch (Exception e) {
                            LOGGER.w(e, "SUBridge: failed to prepare proxy file for " + target);
                        }
                        
                        cmd[i] = cmd[i].replace(target, proxyPath);
                        LOGGER.i("SUBridge: dynamically rewrote " + target + " to " + proxyPath);
                    }
                }
            }
        }

        // Global Magisk Environment Variable Injection
        if (isFeatureEnabled("root_magisk_mocking")) {
            if (env == null) {
                env = new String[]{"MAGISK_VER=26.4", "MAGISK_VER_CODE=26400"};
            } else {
                java.util.List<String> envList = new java.util.ArrayList<>(java.util.Arrays.asList(env));
                envList.add("MAGISK_VER=26.4");
                envList.add("MAGISK_VER_CODE=26400");
                env = envList.toArray(new String[0]);
            }
        }
        
        // SU Bridge interception: strip su wrapper and run command directly via Shizuku privileges
        if (isFeatureEnabled("su_bridge") && cmd != null && cmd.length > 0) {
            String base = cmd[0];
            if (base.equals("su") || base.endsWith("/su")) {
                dispatchLog(callingPkg, "su " + String.join(" ", cmd));
                java.util.List<String> args = new java.util.ArrayList<>();
                boolean inCommand = false;
                boolean skipNext = false;
                for (int i = 1; i < cmd.length; i++) {
                    if (skipNext) { skipNext = false; continue; }
                    if (inCommand) {
                        args.add(cmd[i]);
                    } else if (cmd[i].equals("-c") || cmd[i].equals("--command")) {
                        inCommand = true;
                    } else if (cmd[i].equals("-s") || cmd[i].equals("--shell") || 
                               cmd[i].equals("-cn") || cmd[i].equals("--context") ||
                               cmd[i].equals("-g") || cmd[i].equals("--group") ||
                               cmd[i].equals("-u") || cmd[i].equals("--user") ||
                               cmd[i].equals("-mm") || cmd[i].equals("--mount-master")) {
                        // These flags take a following argument — skip both
                        skipNext = true;
                    } else if (cmd[i].equals("-v") || cmd[i].equals("--version")) {
                        // Return a fake version string for su
                        return newProcessInternal(new String[]{"echo", "26.4:MAGISKSU"}, env, dir);
                    } else if (cmd[i].equals("-V")) {
                        return newProcessInternal(new String[]{"echo", "26400"}, env, dir);
                    } else if (cmd[i].equals("-l") || cmd[i].equals("--login") || cmd[i].equals("-") ||
                               cmd[i].equals("-M") || cmd[i].equals("--magisk-mode")) {
                        // Login or Magisk mode flags — skip safely
                    } else if (cmd[i].equals("-p") || cmd[i].equals("-m") || cmd[i].equals("--preserve-environment")) {
                        // Environment preservation flags — skip
                    } else if (!cmd[i].startsWith("-") && args.isEmpty()) {
                        // user/uid argument (e.g. "0", "root") — skip it
                    } else if (cmd[i].startsWith("-")) {
                        // Unknown flag — skip safely
                    } else {
                        // Positional argument without -c (some su binaries support this)
                        args.add(cmd[i]);
                    }
                }
                if (!args.isEmpty()) {
                    if (inCommand) {
                        // Safe split execution to preserve spaces and quote arguments correctly
                        String script = args.get(0);
                        String[] newCmd = new String[2 + args.size()];
                        newCmd[0] = "sh";
                        newCmd[1] = "-c";
                        newCmd[2] = script;
                        for (int i = 1; i < args.size(); i++) {
                            newCmd[2 + i] = args.get(i);
                        }
                        cmd = newCmd;
                        LOGGER.i("SUBridge: intercepted su -c call, safely routing arguments to sh");
                    } else {
                        // Direct binary/command execution
                        cmd = args.toArray(new String[0]);
                        LOGGER.i("SUBridge: intercepted su call, executing command directly");
                    }
                    
                    // Inject actual su path into environment PATH
                    String realSuPath = plusSettingsMap.get("su_path");
                    if (realSuPath != null && realSuPath.contains("/")) {
                        String suDir = realSuPath.substring(0, realSuPath.lastIndexOf("/"));
                        if (env == null) env = new String[]{"PATH=" + suDir + ":/sbin:/system/bin:/system/xbin"};
                        else {
                            boolean foundPath = false;
                            for (int i = 0; i < env.length; i++) {
                                if (env[i].startsWith("PATH=")) {
                                    env[i] = "PATH=" + suDir + ":" + env[i].substring(5);
                                    foundPath = true;
                                    break;
                                }
                            }
                            if (!foundPath) {
                                String[] newEnv = new String[env.length + 1];
                                System.arraycopy(env, 0, newEnv, 0, env.length);
                                newEnv[env.length] = "PATH=" + suDir + ":/sbin:/system/bin:/system/xbin";
                                env = newEnv;
                            }
                        }
                    }
                } else {
                    cmd = new String[]{"sh"};
                    LOGGER.i("SUBridge: intercepted interactive su, opening sh");
                }
            }
        }
        if (isFeatureEnabled("shell_interceptor") && cmd != null && cmd.length > 0) {
            // Unpack busybox calls so the underlying applet (cp, tar, rm) hits our hooks
            if (cmd[0].equals("busybox") || cmd[0].endsWith("/busybox")) {
                if (cmd.length == 1 || cmd[1].startsWith("-")) {
                    if (isFeatureEnabled("root_busybox_mocking")) {
                        LOGGER.i("SUBridge: mocking busybox version string");
                        return newProcessInternal(new String[]{"echo", "BusyBox v1.36.1 (Shizuku+ Built-in)"}, env, dir);
                    }
                } else {
                    String[] newCmd = new String[cmd.length - 1];
                    System.arraycopy(cmd, 1, newCmd, 0, newCmd.length);
                    cmd = newCmd;
                }
            }
            
            String baseCmd = cmd[0];
            
            // Dynamic Shell Function Injection for Deep Root Spoofing
            if (isFeatureEnabled("su_bridge") && (baseCmd.equals("sh") || baseCmd.endsWith("/sh")) && cmd.length >= 3 && (cmd[1].equals("-c") || cmd[1].equals("--command"))) {
                String originalScript = cmd[2];
                if (!originalScript.startsWith("id() {")) {
                    String mockHeader = "id() { if [ \"$1\" = \"-u\" ] || [ \"$1\" = \"-g\" ] || [ \"$1\" = \"-G\" ]; then echo 0; elif [ \"$1\" = \"-Z\" ]; then echo \"u:r:su:s0\"; else echo \"uid=0(root) gid=0(root) groups=0(root)\"; fi; }; " +
                                        "whoami() { echo root; }; " +
                                        "magisk() { if [ \"$1\" = \"-v\" ] || [ \"$1\" = \"--version\" ]; then echo \"26.4:MAGISKSU\"; elif [ \"$1\" = \"-V\" ]; then echo 26400; else echo \"Magisk v26.4 (26400) - Shizuku+ Bridge Mode\"; fi; }; " +
                                        "su() { if [ \"$1\" = \"-v\" ] || [ \"$1\" = \"--version\" ]; then echo \"26.4:MAGISKSU\"; elif [ \"$1\" = \"-V\" ]; then echo 26400; elif [ \"$1\" = \"-c\" ]; then shift; eval \"$@\"; else eval \"$@\"; fi; }; " +
                                        "getenforce() { echo Permissive; }; ";
                    cmd[2] = mockHeader + originalScript;
                    LOGGER.i("SUBridge: dynamically injected bash mock functions into sh -c payload");
                }
            }
            
            // Root Mocking: Fake common root environment checks
            if (isFeatureEnabled("su_bridge")) {
                if (baseCmd.equals("supolicy") || baseCmd.equals("magiskpolicy")) {
                    LOGGER.i("SUBridge: mocking SELinux policy injection for " + baseCmd);
                    return newProcessInternal(new String[]{"true"}, env, dir);
                } else if ((baseCmd.equals("iptables") || baseCmd.equals("ip6tables") || baseCmd.endsWith("/iptables") || baseCmd.endsWith("/ip6tables")) && isFeatureEnabled("root_iptables_mocking")) {
                    LOGGER.i("SUBridge: executing and mocking iptables command -> " + String.join(" ", cmd));
                    try {
                        java.lang.Process p = Runtime.getRuntime().exec(cmd);
                        int exitCode = p.waitFor();
                        if (exitCode == 0) {
                            return newProcessInternal(cmd, env, dir);
                        } else {
                            LOGGER.w("SUBridge: iptables exited with error (" + exitCode + "), returning mock success");
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                    } catch (Exception e) {
                        LOGGER.w("SUBridge: iptables exec failed, returning mock success");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (baseCmd.equals("pm") && cmd.length > 1 && cmd[1].equals("list") && String.join(" ", cmd).contains("packages")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking pm list packages to include Magisk");
                        try {
                            java.lang.Process p = Runtime.getRuntime().exec(cmd);
                            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                            java.lang.StringBuilder sb = new java.lang.StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                            sb.append("package:com.topjohnwu.magisk\n");
                            return newProcessInternal(new String[]{"echo", sb.toString().trim()}, env, dir);
                        } catch (Exception e) {
                            return newProcessInternal(new String[]{"echo", "package:com.topjohnwu.magisk"}, env, dir);
                        }
                    }
                } else if (baseCmd.equals("pm") && cmd.length > 2 && cmd[1].equals("path") && cmd[2].equals("com.topjohnwu.magisk")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking pm path for Magisk");
                        return newProcessInternal(new String[]{"echo", "package:/data/app/com.topjohnwu.magisk-mocked/base.apk"}, env, dir);
                    }
                }

                if (baseCmd.equals("id")) {
                    LOGGER.i("SUBridge: mocking id command");
                    if (cmd.length > 1 && (cmd[1].equals("-u") || cmd[1].equals("-g") || cmd[1].equals("-G"))) {
                        return newProcessInternal(new String[]{"echo", "0"}, env, dir);
                    }
                    return newProcessInternal(new String[]{"echo", "uid=0(root) gid=0(root) groups=0(root)"}, env, dir);
                } else if (baseCmd.equals("whoami")) {
                    LOGGER.i("SUBridge: mocking whoami command");
                    return newProcessInternal(new String[]{"echo", "root"}, env, dir);
                } else if (baseCmd.equals("getenforce")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking getenforce command");
                        return newProcessInternal(new String[]{"echo", "Permissive"}, env, dir);
                    }
                } else if (baseCmd.equals("setenforce") || baseCmd.equals("chcon") || baseCmd.equals("restorecon")) {
                    if (isFeatureEnabled("root_auto_grant")) {
                        LOGGER.i("SUBridge: mapping SELinux modification to AppOps elevation for caller package " + callingPkg);
                        performAppOpsElevation(callingPkg, callingUid);
                    }
                    return newProcessInternal(new String[]{"true"}, env, dir);
                } else if (baseCmd.equals("setprop") && cmd.length > 2) {
                    LOGGER.i("SUBridge: intercepted setprop " + cmd[1] + " " + cmd[2]);
                    try {
                        android.os.SystemProperties.set(cmd[1], cmd[2]);
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } catch (Exception e) {
                        LOGGER.e("SUBridge: setprop failed", e);
                        return newProcessInternal(new String[]{"false"}, env, dir);
                    }
                } else if (baseCmd.equals("mount") && cmd.length > 1 && String.join(" ", cmd).contains("remount")) {
                    String fullCmd = String.join(" ", cmd);
                    LOGGER.i("SUBridge: intercepting mount remount. Delegating to OverlayManager Proxy.");
                    if (isFeatureEnabled("overlay_fs_proxy_enabled") && (fullCmd.contains("/system") || fullCmd.contains("/vendor"))) {
                        try {
                            overlayManagerPlus.prepareShadowMount(callingPkg, "/system");
                        } catch (Exception e) {
                            LOGGER.e("SUBridge: shadow mount proxy failed", e);
                        }
                    }
                    return newProcessInternal(new String[]{"true"}, env, dir);
                } else if (baseCmd.equals("mount") && cmd.length > 3 && String.join(" ", cmd).contains("--bind")) {
                    String fullCmd = String.join(" ", cmd);
                    LOGGER.i("SUBridge: intercepting mount --bind request: " + fullCmd);
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        // Fake success for general systemless modifications
                        LOGGER.i("SUBridge: Faking mount --bind success for systemless module compatibility");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                    return newProcessInternal(new String[]{"true"}, env, dir);
                } else if (baseCmd.equals("losetup")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        String fullCmd = String.join(" ", cmd);
                        LOGGER.i("SUBridge: mocking losetup " + fullCmd);
                        // Respond with a fake loopback device if requested
                        if (fullCmd.contains("-f") || fullCmd.contains("--show")) {
                            return newProcessInternal(new String[]{"echo", "/dev/block/loop99"}, env, dir);
                        }
                        // Default fake success for setting up loop device
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (baseCmd.equals("mke2fs") || baseCmd.equals("mkfs.ext4") || baseCmd.equals("make_ext4fs")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking " + baseCmd + " success for systemless image creation");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (baseCmd.equals("chroot")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: intercepting chroot. Mocking chroot environment execution.");
                        if (cmd.length > 2) {
                            // Strip 'chroot' and the fake root directory, execute the remaining payload natively
                            String[] chrootCmd = new String[cmd.length - 2];
                            System.arraycopy(cmd, 2, chrootCmd, 0, cmd.length - 2);
                            return newProcessInternal(chrootCmd, env, dir);
                        }
                        // Default fake success
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if ((baseCmd.equals("cp") || baseCmd.equals("mv") || baseCmd.equals("tar")) && (String.join(" ", cmd).contains("/data/data") || String.join(" ", cmd).contains("/system"))) {
                    if (isFeatureEnabled("root_file_interceptor")) {
                        LOGGER.i("SUBridge: injecting permission-preservation flags for sensitive file operation");
                        java.util.List<String> newCmd = new java.util.ArrayList<>(java.util.Arrays.asList(cmd));
                        if (baseCmd.equals("cp") || baseCmd.equals("mv")) {
                            if (!newCmd.contains("-p")) newCmd.add(1, "-p"); // preserve permissions
                            if (!newCmd.contains("--preserve=all")) newCmd.add(1, "--preserve=all");
                        }
                        return newProcessInternal(newCmd.toArray(new String[0]), env, dir);
                    }
                } else if (baseCmd.equals("magisk") || baseCmd.endsWith("/magisk")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking magisk command");
                        if (cmd.length > 1) {
                            if (cmd[1].equals("-v") || cmd[1].equals("--version")) {
                                return newProcessInternal(new String[]{"echo", "26.4:MAGISKSU"}, env, dir);
                            } else if (cmd[1].equals("-V")) {
                                return newProcessInternal(new String[]{"echo", "26400"}, env, dir);
                            }
                        }
                        return newProcessInternal(new String[]{"echo", "Magisk v26.4 (26400) - Shizuku+ Bridge Mode"}, env, dir);
                    }
                } else if (baseCmd.equals("pm") && cmd.length > 3 && cmd[1].equals("grant")) {
                    if (isFeatureEnabled("root_auto_grant")) {
                        LOGGER.i("SUBridge: intercepting pm grant for " + cmd[2]);
                        // Auto-approve common root app requests
                        String targetPkg = cmd[2];
                        String perm = cmd[3];
                        if (perm.contains("WRITE_SECURE_SETTINGS") || perm.contains("DUMP") || perm.contains("PACKAGE_USAGE_STATS")) {
                            try {
                                Runtime.getRuntime().exec(new String[]{"pm", "grant", targetPkg, perm});
                                return newProcessInternal(new String[]{"true"}, env, dir);
                            } catch (Exception e) {
                                LOGGER.e("SUBridge: pm grant failed", e);
                            }
                        }
                    }
                } else if (baseCmd.equals("cat") && cmd.length > 1 && (cmd[1].equals("/sys/fs/selinux/enforce") || cmd[1].contains("/selinux/enforce"))) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking cat /sys/fs/selinux/enforce -> Permissive");
                        return newProcessInternal(new String[]{"echo", "0"}, env, dir);
                    }
                } else if ((baseCmd.equals("test") || baseCmd.equals("[")) && cmd.length > 1 && (String.join(" ", cmd).contains("/sbin/.magisk") || String.join(" ", cmd).contains("/data/adb/magisk") || String.join(" ", cmd).contains("/dev/magisk") || String.join(" ", cmd).contains("/proc/self/mounts") || String.join(" ", cmd).matches(".*\\b(su|magisk)\\b.*"))) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking test/[ for root/Magisk-related paths");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (baseCmd.equals("stat") && cmd.length > 1 && String.join(" ", cmd).matches(".*\\b(su|magisk)\\b.*")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking stat for su/magisk");
                        String target = String.join(" ", cmd).contains("magisk") ? "/sbin/magisk" : "/system/xbin/su";
                        return newProcessInternal(new String[]{"echo", "  File: " + target + "\n  Size: 157328\tBlocks: 312\tIO Block: 4096\tregular file\nAccess: (0755/-rwsr-xr-x)\tUid: (    0/    root)\tGid: (    0/    root)"}, env, dir);
                    }
                } else if (baseCmd.equals("ls") && cmd.length > 1 && (String.join(" ", cmd).contains("/su") || String.join(" ", cmd).contains("/sbin/.magisk") || String.join(" ", cmd).contains("/data/adb/magisk"))) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        if (String.join(" ", cmd).contains("su")) {
                            LOGGER.i("SUBridge: mocking ls for su path");
                            String customSuPath = plusSettingsMap.getOrDefault("custom_su_path", "");
                            if (customSuPath == null || customSuPath.trim().isEmpty()) customSuPath = "/system/xbin/su";
                            return newProcessInternal(new String[]{"echo", "-rwsr-xr-x 1 root root 157328 2026-03-11 12:00 " + customSuPath}, env, dir);
                        } else if (String.join(" ", cmd).contains("magisk")) {
                            LOGGER.i("SUBridge: mocking ls for Magisk path");
                            return newProcessInternal(new String[]{"echo", "-rwxr-xr-x 1 root root 14528 2026-03-11 12:00 /sbin/magisk"}, env, dir);
                        }
                    }
                } else if (baseCmd.equals("resetprop")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking resetprop " + String.join(" ", cmd));
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (baseCmd.equals("which") && cmd.length > 1 && cmd[1].equals("su")) {
                    String customSuPath = plusSettingsMap.getOrDefault("custom_su_path", "");
                    if (customSuPath == null || customSuPath.trim().isEmpty()) customSuPath = "/system/xbin/su";
                    LOGGER.i("SUBridge: mocking which su command -> " + customSuPath);
                    return newProcessInternal(new String[]{"echo", customSuPath}, env, dir);
                } else if (baseCmd.equals("getprop") && cmd.length > 1) {
                    String prop = cmd[1];
                    boolean forceReal = prop.startsWith("real.");
                    if (forceReal) prop = prop.substring(5);

                    if (!forceReal && (prop.startsWith("magisk.") || prop.equals("ro.debuggable") || prop.equals("ro.secure") || prop.equals("persist.magisk.hide"))) {
                        if (isFeatureEnabled("root_magisk_mocking")) {
                            LOGGER.i("SUBridge: mocking getprop " + prop);
                            String value = "0";
                            if (prop.equals("ro.debuggable")) value = "1";
                            else if (prop.equals("ro.secure")) value = "0";
                            else if (prop.equals("persist.magisk.hide")) value = "1";
                            else if (prop.contains("version")) value = "26.4";
                            else if (prop.contains("code")) value = "26400";
                            else if (prop.contains("path")) value = "/data/adb/magisk";
                            return newProcessInternal(new String[]{"echo", value}, env, dir);
                        }
                    } else if (prop.startsWith("ro.product.") || prop.startsWith("ro.build.")) {
                        if (!forceReal && isFeatureEnabled("spoof_device")) {
                            String target = plusSettingsMap.getOrDefault("spoof_target", "pixel_8_pro");
                            LOGGER.i("SUBridge: spoofing getprop " + prop + " as " + target);
                            String spoofValue = "";
                            
                            switch (target) {
                                case "pixel_9_pro_xl":
                                    if (prop.contains("model")) spoofValue = "Pixel 9 Pro XL";
                                    else if (prop.contains("manufacturer")) spoofValue = "Google";
                                    else if (prop.contains("brand")) spoofValue = "google";
                                    else if (prop.contains("device")) spoofValue = "komodo";
                                    else if (prop.contains("product")) spoofValue = "komodo";
                                    else if (prop.contains("fingerprint")) spoofValue = "google/komodo/komodo:15/AP3A.241005.015/12533500:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                                case "s24_ultra":
                                    if (prop.contains("model")) spoofValue = "SM-S928B";
                                    else if (prop.contains("manufacturer")) spoofValue = "samsung";
                                    else if (prop.contains("brand")) spoofValue = "samsung";
                                    else if (prop.contains("device")) spoofValue = "eureka";
                                    else if (prop.contains("product")) spoofValue = "eureka";
                                    else if (prop.contains("fingerprint")) spoofValue = "samsung/eureka/eureka:14/UP1A.231005.007/S928BXXU1AXB5:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                                case "s22_ultra":
                                    if (prop.contains("model")) spoofValue = "SM-S908B";
                                    else if (prop.contains("manufacturer")) spoofValue = "samsung";
                                    else if (prop.contains("brand")) spoofValue = "samsung";
                                    else if (prop.contains("device")) spoofValue = "b0s";
                                    else if (prop.contains("product")) spoofValue = "b0s";
                                    else if (prop.contains("fingerprint")) spoofValue = "samsung/b0s/b0s:14/UP1A.231005.007/S908BXXS7DWL1:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                                case "s23_ultra":
                                    if (prop.contains("model")) spoofValue = "SM-S918B";
                                    else if (prop.contains("manufacturer")) spoofValue = "samsung";
                                    else if (prop.contains("brand")) spoofValue = "samsung";
                                    else if (prop.contains("device")) spoofValue = "dm3";
                                    else if (prop.contains("product")) spoofValue = "dm3";
                                    else if (prop.contains("fingerprint")) spoofValue = "samsung/dm3/dm3:14/UP1A.231005.007/S918BXXU3BWK1:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                                case "oneplus_12":
                                    if (prop.contains("model")) spoofValue = "CPH2581";
                                    else if (prop.contains("manufacturer")) spoofValue = "OnePlus";
                                    else if (prop.contains("brand")) spoofValue = "OnePlus";
                                    else if (prop.contains("device")) spoofValue = "OP5929L1";
                                    else if (prop.contains("product")) spoofValue = "OP5929L1";
                                    else if (prop.contains("fingerprint")) spoofValue = "OnePlus/CPH2581/OP5929L1:14/UKQ1.230924.001/R.202401121400:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                                case "nothing_phone_2":
                                    if (prop.contains("model")) spoofValue = "A065";
                                    else if (prop.contains("manufacturer")) spoofValue = "Nothing";
                                    else if (prop.contains("brand")) spoofValue = "Nothing";
                                    else if (prop.contains("device")) spoofValue = "Pong";
                                    else if (prop.contains("product")) spoofValue = "Pong";
                                    else if (prop.contains("fingerprint")) spoofValue = "Nothing/Pong/Pong:14/UP1A.231005.007/2401121400:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                                case "pixel_8_pro":
                                default:
                                    if (prop.contains("model")) spoofValue = "Pixel 8 Pro";
                                    else if (prop.contains("manufacturer")) spoofValue = "Google";
                                    else if (prop.contains("brand")) spoofValue = "google";
                                    else if (prop.contains("device")) spoofValue = "husky";
                                    else if (prop.contains("product")) spoofValue = "husky";
                                    else if (prop.contains("fingerprint")) spoofValue = "google/husky/husky:14/UD1A.230803.041/10808577:user/release-keys";
                                    else spoofValue = android.os.SystemProperties.get(prop, "");
                                    break;
                            }
                            return newProcessInternal(new String[]{"echo", spoofValue}, env, dir);
                        } else {
                            // Functional: Return actual device identity
                            String actualValue = android.os.SystemProperties.get(prop, "");
                            LOGGER.i("SUBridge: getprop " + prop + " -> " + actualValue);
                            return newProcessInternal(new String[]{"echo", actualValue}, env, dir);
                        }
                    }
                } else if (isFeatureEnabled("experimental_root") && baseCmd.equals("setprop") && cmd.length == 3) {
                    String prop = cmd[1];
                    String value = cmd[2];
                    if (prop.equals("debug.hwui.anim_duration_scale") || prop.equals("persist.sys.anim_duration_scale")) {
                        return newProcessInternal(new String[]{"settings", "put", "global", "animator_duration_scale", value}, env, dir);
                    } else if (prop.equals("debug.hwui.force_dark")) {
                        String mappedValue = value.equals("true") || value.equals("1") ? "2" : "1";
                        return newProcessInternal(new String[]{"settings", "put", "secure", "ui_night_mode", mappedValue}, env, dir);
                    }
                } else if (isFeatureEnabled("experimental_root")) {
                    if (baseCmd.equals("pm") && cmd.length > 2 && cmd[1].equals("disable")) {
                        // Map global disable to user disable for shell compatibility
                        cmd[1] = "disable-user";
                        String[] newCmd = new String[cmd.length + 2];
                        System.arraycopy(cmd, 0, newCmd, 0, cmd.length);
                        newCmd[cmd.length] = "--user";
                        newCmd[cmd.length + 1] = "0";
                        return newProcessInternal(newCmd, env, dir);
                    } else if (baseCmd.equals("rm") && (String.join(" ", cmd).contains("/system/app/") || String.join(" ", cmd).contains("/system/priv-app/") || String.join(" ", cmd).contains("/product/app/"))) {
                        String targetPath = null;
                        for (String arg : cmd) {
                            if (arg.startsWith("/system/app/") || arg.startsWith("/system/priv-app/") || arg.startsWith("/product/app/")) {
                                targetPath = arg;
                                break;
                            }
                        }
                        if (targetPath != null) {
                            LOGGER.i("SUBridge: intercepting rm on system app, mapping to pm uninstall --user 0");
                            String safePath = targetPath.replace("'", "'\\''");
                            String script = "PKG=$(pm list packages -f | grep '" + safePath + "' | sed 's/.*=//' | head -n 1); " +
                                            "if [ ! -z \"$PKG\" ]; then pm uninstall -k --user 0 \"$PKG\"; else false; fi";
                            return newProcessInternal(new String[]{"sh", "-c", script}, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("kill")) {
                        String pid = cmd[cmd.length - 1]; // PID is usually the last argument
                        if (pid.matches("\\d+")) {
                            LOGGER.i("SUBridge: intercepting kill " + pid + ", mapping to am force-stop");
                            String script = "PKG=$(ps -p " + pid + " -o NAME= | sed 's/:.*//' | tr -d '[:space:]'); " +
                                            "if [ ! -z \"$PKG\" ] && [ \"$PKG\" != \"sh\" ] && [ \"$PKG\" != \"su\" ]; then am force-stop \"$PKG\"; else kill " + String.join(" ", java.util.Arrays.copyOfRange(cmd, 1, cmd.length)) + "; fi";
                            return newProcessInternal(new String[]{"sh", "-c", script}, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("pkill") || baseCmd.equals("killall")) {
                        String target = cmd[cmd.length - 1]; // Target name is usually the last argument
                        LOGGER.i("SUBridge: intercepting " + baseCmd + " " + target + ", mapping to am force-stop");
                        String safeTarget = target.replace("\"", "\\\"");
                        String script = "if [ ! -z \"" + safeTarget + "\" ] && [ \"" + safeTarget + "\" != \"sh\" ] && [ \"" + safeTarget + "\" != \"su\" ]; then am force-stop \"" + safeTarget + "\"; else false; fi";
                        return newProcessInternal(new String[]{"sh", "-c", script}, env, dir);
                    } else if (baseCmd.equals("insmod") || baseCmd.equals("rmmod") || baseCmd.equals("modprobe")) {
                        if (isFeatureEnabled("root_kernel_ghosting_enabled")) {
                            LOGGER.i("SUBridge: intercepting kernel module load/unload (" + baseCmd + "), returning mock success");
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("reboot")) {
                        if (isFeatureEnabled("bootloader_fastbootd_reboot_enabled") && cmd.length > 1 && (cmd[1].equals("bootloader") || cmd[1].equals("fastboot") || cmd[1].equals("recovery"))) {
                            LOGGER.i("SUBridge: Mapping unlocked bootloader reboot to svc power natively");
                            return newProcessInternal(new String[]{"svc", "power", "reboot", cmd[1]}, env, dir);
                        } else if (isFeatureEnabled("root_power_ghosting_enabled")) {
                            LOGGER.i("SUBridge: intercepting reboot request (ghosting): " + String.join(" ", cmd));
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("setprop") && cmd.length > 1 && cmd[1].startsWith("ctl.")) {
                        if (isFeatureEnabled("root_power_ghosting_enabled")) {
                            LOGGER.i("SUBridge: intercepting service control (soft reboot) " + cmd[1]);
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("dd") && String.join(" ", cmd).contains("/dev/block/")) {
                        if (isFeatureEnabled("root_partition_ghosting_enabled")) {
                            LOGGER.i("SUBridge: intercepting dd on block device. Mocking success.");
                            String script = "for arg in \"$@\"; do case $arg in of=*) touch \"${arg#of=}\" 2>/dev/null ;; esac; done; true";
                            String[] proxyCmd = new String[cmd.length + 3];
                            proxyCmd[0] = "sh"; proxyCmd[1] = "-c"; proxyCmd[2] = script;
                            System.arraycopy(cmd, 0, proxyCmd, 3, cmd.length);
                            return newProcessInternal(proxyCmd, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("update_engine_client") && isFeatureEnabled("bootloader_flash_ota_enabled")) {
                        LOGGER.i("SUBridge: Executing update_engine_client natively for systemless OTA flashing");
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("svc") && cmd.length >= 3) {
                        String service = cmd[1];
                        String action = cmd[2];
                        LOGGER.i("Plus Optimization: mapping svc " + service + " to cmd");
                        if (service.equals("wifi")) {
                            return newProcessInternal(new String[]{"cmd", "wifi", "set-wifi-enabled", action.equals("enable") ? "enabled" : "disabled"}, env, dir);
                        } else if (service.equals("data")) {
                            return newProcessInternal(new String[]{"cmd", "phone", "data", action}, env, dir);
                        } else if (service.equals("usb") && action.equals("setFunctions")) {
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        } else if (service.equals("power") && action.equals("reboot")) {
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                        return newProcessInternal(cmd, env, dir);
                    } else if (baseCmd.equals("ifconfig") && cmd.length >= 3) {
                        String iface = cmd[1];
                        String action = cmd[2];
                        LOGGER.i("SUBridge: mocking ifconfig " + iface + " " + action);
                        if (iface.startsWith("wlan") && (action.equals("up") || action.equals("down"))) {
                            return newProcessInternal(new String[]{"cmd", "wifi", "set-wifi-enabled", action.equals("up") ? "enabled" : "disabled"}, env, dir);
                        }
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if (baseCmd.equals("ip") && cmd.length >= 4 && cmd[1].equals("link") && cmd[2].equals("set")) {
                        String iface = cmd[3];
                        String action = cmd[cmd.length - 1]; // "up" or "down" usually at the end
                        LOGGER.i("SUBridge: mocking ip link set " + iface + " " + action);
                        if (iface.startsWith("wlan") && (action.equals("up") || action.equals("down"))) {
                            return newProcessInternal(new String[]{"cmd", "wifi", "set-wifi-enabled", action.equals("up") ? "enabled" : "disabled"}, env, dir);
                        }
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if (baseCmd.equals("dumpsys") && cmd.length >= 2 && (cmd[1].equals("battery") || cmd[1].equals("deviceidle"))) {
                        LOGGER.i("SUBridge: executing dumpsys " + cmd[1] + " natively under Shizuku DUMP permission");
                        return newProcessInternal(cmd, env, dir);
                    } else if (String.join(" ", cmd).contains("MASTER_CLEAR") || String.join(" ", cmd).contains("wipe_data") || (baseCmd.equals("sm") && cmd.length > 1 && cmd[1].equals("format"))) {
                        LOGGER.w("SUBridge: Intercepted highly destructive command! Ghosting success to prevent data wipe: " + String.join(" ", cmd));
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if (baseCmd.equals("chattr")) {
                        LOGGER.i("SUBridge: mocking chattr immutability applied");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if (baseCmd.equals("lsattr")) {
                        LOGGER.i("SUBridge: mocking lsattr output");
                        String target = cmd[cmd.length - 1];
                        return newProcessInternal(new String[]{"echo", "----i--------- " + target}, env, dir);
                    } else if (baseCmd.equals("chmod") || baseCmd.equals("chown")) {
                        LOGGER.i("SUBridge: intercepting " + baseCmd + ", returning mock success");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if (baseCmd.equals("iptables") || baseCmd.equals("ip6tables")) {
                        String fullCmd = String.join(" ", cmd);
                        if (fullCmd.contains("--uid-owner")) {
                            try {
                                // Extract UID from "--uid-owner <uid>"
                                int index = -1;
                                for (int i = 0; i < cmd.length; i++) {
                                    if (cmd[i].equals("--uid-owner")) { index = i + 1; break; }
                                }
                                if (index != -1 && index < cmd.length) {
                                    int uid = Integer.parseInt(cmd[index]);
                                    boolean restricted = !fullCmd.contains("-D"); // -A or -I means add/restrict, -D means delete/unrestrict
                                    LOGGER.i("SUBridge: mapping iptables for UID " + uid + " to NetworkPolicy (restricted=" + restricted + ")");
                                    
                                    IBinder binder = ServiceManager.getService("netpolicy");
                                    if (binder != null) {
                                        Object service = Class.forName("android.net.INetworkPolicyManager$Stub")
                                            .getMethod("asInterface", IBinder.class).invoke(null, binder);
                                        // 1 = POLICY_REJECT_METERED_BACKGROUND, 4 = POLICY_REJECT_ALL (if available on target android version)
                                        int policy = restricted ? 1 : 0; 
                                        service.getClass().getMethod("setUidPolicy", int.class, int.class).invoke(service, uid, policy);
                                    }
                                }
                            } catch (Exception e) {
                                LOGGER.e("SUBridge: failed to map iptables to NetworkPolicy", e);
                            }
                        }
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if ((baseCmd.equals("tar") || baseCmd.equals("cp")) && (String.join(" ", cmd).contains("/data/data/") || String.join(" ", cmd).contains("/data/app/") || String.join(" ", cmd).contains("/data/user/"))) {
                        String fullCmd = String.join(" ", cmd);
                        LOGGER.i("SUBridge: mapping backup command to native bu utility: " + fullCmd);
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/data/(?:data|app|user/\\d+)/([^/\\-\\s]+)").matcher(fullCmd);
                        if (m.find()) {
                            String targetPkg = m.group(1);
                            String archivePath = null;
                            for (int i = 1; i < cmd.length; i++) {
                                if (cmd[i].contains("f") && cmd[i].startsWith("-") && i + 1 < cmd.length) {
                                    archivePath = cmd[i + 1];
                                    break;
                                } else if (cmd[i].endsWith(".tar") || cmd[i].endsWith(".gz")) {
                                    archivePath = cmd[i];
                                    break;
                                }
                            }
                            boolean isRestore = fullCmd.contains("-x") || fullCmd.contains("--extract");
                            boolean isGzip = fullCmd.contains("-z") || fullCmd.contains("--gzip") || (archivePath != null && archivePath.endsWith(".gz"));

                            if (archivePath != null) {
                                if (isRestore) {
                                    String restoreCmd = "(printf 'ANDROID BACKUP\\n1\\n1\\nnone\\n' ; ";
                                    if (isGzip) restoreCmd += "gunzip -c " + archivePath;
                                    else restoreCmd += "cat " + archivePath;
                                    restoreCmd += ") | bu restore";
                                    return newProcessInternal(new String[]{"sh", "-c", restoreCmd}, env, dir);
                                } else {
                                    String backupCmd = "bu backup -apk -obb " + targetPkg + " | tail -c +25 ";
                                    if (isGzip) backupCmd += "| gzip -c ";
                                    backupCmd += "> " + archivePath;
                                    return newProcessInternal(new String[]{"sh", "-c", backupCmd}, env, dir);
                                }
                            } else {
                                if (isRestore) {
                                    String restoreCmd = "(printf 'ANDROID BACKUP\\n1\\n1\\nnone\\n' ; cat) | bu restore";
                                    return newProcessInternal(new String[]{"sh", "-c", restoreCmd}, env, dir);
                                } else {
                                    String backupCmd = "bu backup -apk -obb " + targetPkg + " | tail -c +25";
                                    return newProcessInternal(new String[]{"sh", "-c", backupCmd}, env, dir);
                                }
                            }
                        }
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    } else if (baseCmd.equals("screencap")) {
                        LOGGER.i("SUBridge: functional screencap mapping");
                        // The shell UID is allowed to run screencap
                        return newProcessInternal(cmd, env, dir);
                    }
                }
            }

            // Backporting: Native Acceleration for regular apps
            if (baseCmd.equals("am") && cmd.length >= 3) {
                if (cmd[1].equals("force-stop")) {
                    String pkg = cmd[2];
                    LOGGER.i("Plus Optimization: am force-stop " + pkg + " via ActivityManagerPlus");
                    if (activityManagerPlus.deepForceStop(pkg)) {
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (cmd[1].equals("freeze") || cmd[1].equals("suspend")) {
                    String pkg = cmd[2];
                    LOGGER.i("Plus Optimization: am freeze " + pkg + " -> restricted bucket");
                    if (activityManagerPlus.setAppStandbyBucket(pkg, 45)) { // 45 = RESTRICTED
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                }
            } else if (baseCmd.equals("settings") && cmd.length >= 5 && cmd[1].equals("put")) {
                String namespace = cmd[2];
                String key = cmd[3];
                String value = cmd[4];
                int userId = UserHandleCompat.getUserId(callingUid);
                LOGGER.i("Plus Optimization: settings put " + namespace + " " + key + " user=" + userId);
                try {
                    android.content.IContentProvider provider = ActivityManagerApis.getContentProviderExternal("settings", userId, null, "com.android.shell");
                    if (provider != null) {
                        android.os.Bundle extras = new android.os.Bundle();
                        extras.putString("value", value);
                        rikka.shizuku.server.api.IContentProviderUtils.callCompat(provider, null, "settings", "PUT_" + namespace, key, extras);
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } catch (Throwable tr) {
                    LOGGER.e(tr, "Plus Optimization: settings put failed");
                }
            } else if (baseCmd.equals("pm") && cmd.length >= 2 && cmd[1].equals("install")) {
                LOGGER.i("Plus Optimization: pm install");
                // For now, let it fall through to sh -c pm install which is already functional
            } else if (baseCmd.equals("appops") && cmd.length >= 5) {
                // Intercept appops set/get for native speed
                String op = cmd[1]; // set/get
                String pkg = cmd[2];
                String modeOrOp = cmd[3];
                int userId = UserHandleCompat.getUserId(callingUid);
                LOGGER.i("Plus Optimization: appops " + op + " " + pkg + " user=" + userId);
                try {
                    IBinder binder = ServiceManager.getService("appops");
                    if (binder != null) {
                        Class<?> stub = Class.forName("com.android.internal.app.IAppOpsService$Stub");
                        Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
                        if (op.equals("set")) {
                            String value = cmd[4];
                            int intOp = (int) service.getClass().getMethod("strOpToOp", String.class).invoke(service, modeOrOp);
                            int intMode = value.equals("allow") ? 0 : (value.equals("ignore") || value.equals("deny")) ? 1 : 2; 
                            
                            int targetUid = -1;
                            try {
                                targetUid = Integer.parseInt(pkg);
                            } catch (NumberFormatException e) {
                                ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(pkg, 0, userId);
                                if (ai != null) targetUid = ai.uid;
                            }
                            
                            if (targetUid != -1) {
                                service.getClass().getMethod("setMode", int.class, int.class, String.class, int.class).invoke(service, intOp, targetUid, pkg, intMode);
                                return newProcessInternal(new String[]{"true"}, env, dir);
                            }
                        }
                    }
                } catch (Throwable tr) {
                    LOGGER.e(tr, "Plus Optimization: appops failed");
                }
            } else if (baseCmd.equals("service") && cmd.length >= 4 && cmd[1].equals("call")) {
                String serviceName = cmd[2];
                int code = -1;
                try { 
                    code = Integer.parseInt(cmd[3]); 
                } catch (NumberFormatException ignored) {}

                if (code != -1) {
                    try {
                        IBinder target = ServiceManager.getService(serviceName);
                        if (target != null) {
                            String descriptor = target.getInterfaceDescriptor();
                            if (descriptor != null) {
                                // 1. Pass raw IPCs through Shizuku's Binder firewall
                                if (isBinderCallBlocked(callingUid, descriptor, code)) {
                                    LOGGER.i("SUBridge: blocked raw service call to %s (%s) code %d", serviceName, descriptor, code);
                                    // Mock standard Android 'service call' success output
                                    return newProcessInternal(new String[]{"echo", "Result: Parcel(00000000    '....')"}, env, dir);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.e("SUBridge: failed to evaluate raw service call securely", e);
                        // Default to mock success on error to prevent escalation
                        return newProcessInternal(new String[]{"echo", "Result: Parcel(00000000    '....')"}, env, dir);
                    }
                }
            } else if (isFeatureEnabled("storage_proxy") && (baseCmd.equals("ls") || baseCmd.equals("rm") || baseCmd.equals("mkdir") || baseCmd.equals("cat") || baseCmd.equals("stat"))) {
                String path = cmd[cmd.length - 1];
                if (path.startsWith("/data/data/") || path.startsWith("/sdcard/Android/data/") || path.startsWith("/data/app/")) {
                    LOGGER.i("Plus Optimization (Storage Bridge): mapping " + baseCmd + " " + path);
                    try {
                        if (baseCmd.equals("ls")) {
                            java.util.List<String> files = storageProxy.listFiles(path);
                            if (files != null) {
                                String joined = String.join("\n", files);
                                return newProcessInternal(new String[]{"echo", joined}, env, dir);
                            }
                        } else if (baseCmd.equals("cat")) {
                            android.os.ParcelFileDescriptor pfd = storageProxy.openFile(path, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                            if (pfd != null) {
                                return new ProxyRemoteProcess(pfd, 0);
                            }
                        } else if (baseCmd.equals("stat")) {
                            android.os.Bundle info = storageProxy.getFileInfo(path);
                            if (info.getBoolean("exists")) {
                                String statOut = "File: " + path + "\nSize: " + info.getLong("size") + "\nModify: " + info.getLong("lastModified");
                                return newProcessInternal(new String[]{"echo", statOut}, env, dir);
                            }
                        } else if (baseCmd.equals("rm")) {
                            if (storageProxy.delete(path)) {
                                return newProcessInternal(new String[]{"true"}, env, dir);
                            }
                        } else if (baseCmd.equals("mkdir")) {
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                    } catch (Exception e) {
                        LOGGER.e("SUBridge: StorageProxy command failed", e);
                    }
                }
            }
        }
        return super.newProcessInternal(cmd, env, dir);
    }

    @Override
@android.annotation.SuppressLint("NewApi")
    public void showPermissionConfirmation(int requestCode, @NonNull ClientRecord clientRecord, int callingUid, int callingPid, int userId) {
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(clientRecord.packageName, 0, userId);
        if (ai == null) {
            return;
        }

        PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(MANAGER_APPLICATION_ID, 0, userId);
        UserInfo userInfo = UserManagerApis.getUserInfo(userId);
        boolean isWorkProfileUser = BuildUtils.INSTANCE.getAtLeast30() ?
                "android.os.usertype.profile.MANAGED".equals(userInfo.userType) :
                (userInfo.flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;
        if (pi == null && !isWorkProfileUser) {
            LOGGER.w("Manager not found in non work profile user %d. Revoke permission", userId);
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        Intent intent = new Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
                .setPackage(MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", callingUid)
                .putExtra("pid", callingPid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai);
        ActivityManagerApis.startActivityNoThrow(intent, null, isWorkProfileUser ? 0 : userId);
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws RemoteException {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("dispatchPermissionConfirmationResult called not from the manager package");
            return;
        }

        if (data == null) {
            return;
        }

        boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED);
        boolean onetime = data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME);

        LOGGER.i("dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
                requestUid, requestPid, requestCode, Boolean.toString(allowed), Boolean.toString(onetime));

        List<ClientRecord> records = clientManager.findClients(requestUid);
        List<String> packages = new ArrayList<>();
        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid);
        } else {
            for (ClientRecord record : records) {
                packages.add(record.packageName);
                record.allowed = allowed;
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(requestCode, allowed);
                }
            }
        }

        if (!onetime) {
            configManager.update(requestUid, packages, ConfigManager.MASK_PERMISSION, allowed ? ConfigManager.FLAG_ALLOWED : ConfigManager.FLAG_DENIED);
        }

        if (!onetime && allowed) {
            int userId = UserHandleCompat.getUserId(requestUid);

            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(requestUid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null) {
                    continue;
                }

                String permToGrant = null;
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    permToGrant = PERMISSION;
                } else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)) {
                    permToGrant = ServerConstants.PERMISSION_ORIGINAL;
                } else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY)) {
                    permToGrant = ServerConstants.PERMISSION_LEGACY;
                }

                if (permToGrant == null) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                if (allowed) {
                    try {
                        PermissionManagerApis.grantRuntimePermission(packageName, permToGrant, userId);
                    } catch (Throwable e) {
                        LOGGER.w("grantRuntimePermission failed for " + permToGrant);
                    }
                } else {
                    try {
                        PermissionManagerApis.revokeRuntimePermission(packageName, permToGrant, userId);
                    } catch (Throwable e) {
                        LOGGER.w("revokeRuntimePermission failed for " + permToGrant);
                    }
                }
            }
        }
    }

    private int  getFlagsForUidInternal(int uid, int mask, boolean allowRuntimePermission) {
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        if (entry != null) {
            return entry.flags & mask;
        }

        if (allowRuntimePermission && (mask & ConfigManager.MASK_PERMISSION) != 0) {
            int userId = UserHandleCompat.getUserId(uid);
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null) {
                    continue;
                }

                try {
                    if (PermissionManagerApis.checkPermission(PERMISSION, uid) == PackageManager.PERMISSION_GRANTED ||
                        PermissionManagerApis.checkPermission(ServerConstants.PERMISSION_LEGACY, uid) == PackageManager.PERMISSION_GRANTED ||
                        PermissionManagerApis.checkPermission(ServerConstants.PERMISSION_ORIGINAL, uid) == PackageManager.PERMISSION_GRANTED) {
                        return ConfigManager.FLAG_ALLOWED;
                    }
                } catch (Throwable e) {
                    LOGGER.w("getFlagsForUid");
                }
            }
        }
        return 0;
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return 0;
        }
        return getFlagsForUidInternal(uid, mask, true);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) throws RemoteException {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return;
        }

        int userId = UserHandleCompat.getUserId(uid);

        if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
            boolean allowed = (value & ConfigManager.FLAG_ALLOWED) != 0;
            boolean denied = (value & ConfigManager.FLAG_DENIED) != 0;

            List<ClientRecord> records = clientManager.findClients(uid);
            for (ClientRecord record : records) {
                if (allowed) {
                    record.allowed = true;
                } else {
                    record.allowed = false;
                    ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid));
                    onPermissionRevoked(record.packageName);
                }
            }

            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null) {
                    continue;
                }

                String permToGrant = null;
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    permToGrant = PERMISSION;
                } else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)) {
                    permToGrant = ServerConstants.PERMISSION_ORIGINAL;
                } else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY)) {
                    permToGrant = ServerConstants.PERMISSION_LEGACY;
                }

                if (permToGrant == null) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                if (allowed) {
                    try {
                        PermissionManagerApis.grantRuntimePermission(packageName, permToGrant, userId);
                    } catch (Throwable e) {
                        LOGGER.w("grantRuntimePermission failed for " + permToGrant);
                    }
                } else {
                    try {
                        PermissionManagerApis.revokeRuntimePermission(packageName, permToGrant, userId);
                    } catch (Throwable e) {
                        LOGGER.w("revokeRuntimePermission failed for " + permToGrant);
                    }
                    onPermissionRevoked(packageName);
                }
            }
        }

        configManager.update(uid, null, mask, value);
    }

    private void onPermissionRevoked(String packageName) {
        getUserServiceManager().removeUserServicesForPackage(packageName);
    }

    private ParcelableListSlice<PackageInfo> getApplications(int userId) {
        List<PackageInfo> list = new ArrayList<>();
        List<Integer> users = new ArrayList<>();
        if (userId == -1) {
            users.addAll(UserManagerApis.getUserIdsNoThrow());
        } else {
            users.add(userId);
        }

        for (int user : users) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, user)) {
                if (Objects.equals(MANAGER_APPLICATION_ID, pi.packageName)) continue;
                if (pi.applicationInfo == null) continue;

                int uid = pi.applicationInfo.uid;
                try {
                    if (isHidden(uid)) continue;
                } catch (RemoteException e) {
                    continue;
                }
                
                int flags = 0;
                ShizukuConfig.PackageEntry entry = configManager.find(uid);
                if (entry != null) {
                    if (entry.packages != null && !entry.packages.contains(pi.packageName))
                        continue;
                    flags = entry.flags & ConfigManager.MASK_PERMISSION;
                }

                if (flags != 0) {
                    list.add(pi);
                } else if (pi.requestedPermissions != null && (
                        ArraysKt.contains(pi.requestedPermissions, PERMISSION) ||
                        ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY) ||
                        ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)
                )) {
                    list.add(pi);
                } else if (pi.applicationInfo.metaData != null
                        && pi.applicationInfo.metaData.getBoolean("af.shizuku.client.V3_SUPPORT", false)) {
                    list.add(pi);
                }
            }

        }
        return new ParcelableListSlice<>(list);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (isFeatureEnabled("binder_logging")) {
            LOGGER.i("Binder transaction: code=%d, calling uid=%d, flags=%d", code, Binder.getCallingUid(), flags);
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            int userId = data.readInt();
            ParcelableListSlice<PackageInfo> result = getApplications(userId);
            reply.writeNoException();
            result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            return true;
        } else if (code == ServerConstants.BINDER_TRANSACTION_isCustomApiEnabled) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            reply.writeNoException();
            reply.writeInt(1); // Shizuku+ server always has it enabled at server level if running
            return true;
        } else if (code == ServerConstants.BINDER_TRANSACTION_getDhizukuBinder) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            // In Shizuku+, we share the DevicePolicyManager binder if Dhizuku mode is "active"
            // (The manager app controls this via settings, but the server just provides the binder if asked)
            IBinder dpm = ServiceManager.getService(Context.DEVICE_POLICY_SERVICE);
            reply.writeNoException();
            reply.writeStrongBinder(dpm);
            return true;
        } else if (code == ServerConstants.BINDER_TRANSACTION_getServerPatchVersion) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            reply.writeNoException();
            reply.writeInt(ShizukuApiConstants.SERVER_PATCH_VERSION);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    void sendBinderToClient() {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(this, userId);
        }
    }

    private static void sendBinderToClient(Binder binder, int userId) {
        try {
            java.util.Set<String> runningPackages = new java.util.HashSet<>();
            try {
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = null;
                try {
                    java.lang.reflect.Method getService = android.app.ActivityManager.class.getMethod("getService");
                    Object am = getService.invoke(null);
                    processes = (java.util.List<android.app.ActivityManager.RunningAppProcessInfo>) am.getClass().getMethod("getRunningAppProcesses").invoke(am);
                } catch (Throwable t) {
                    try {
                        java.lang.reflect.Method getDefault = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault");
                        Object am = getDefault.invoke(null);
                        processes = (java.util.List<android.app.ActivityManager.RunningAppProcessInfo>) am.getClass().getMethod("getRunningAppProcesses").invoke(am);
                    } catch (Throwable ignored) {
                    }
                }
                if (processes != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                        if (process.pkgList != null) {
                            for (String pkg : process.pkgList) {
                                runningPackages.add(pkg);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            Stream<PackageInfo> packages =
                PackageManagerApis.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS, userId
                )
                .stream()
                .filter(pi -> pi != null && pi.requestedPermissions != null)
                .filter(pi -> ArraysKt.contains(pi.requestedPermissions, PERMISSION) || 
                              ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY) ||
                              ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL))
                .filter(pi -> runningPackages.contains(pi.packageName));

            LOGGER.i("sending binders");
            packages
                .parallel()
                .forEach(pi -> {
                    sendBinderToUserApp(binder, pi.packageName, userId);
                });
            LOGGER.i("sent binders");
        } catch (Throwable tr) {
            LOGGER.e("exception when call getInstalledPackages", tr);
        }
    }

    void sendBinderToManager() {
        sendBinderToManager(this);
    }

    private static void sendBinderToManager(Binder binder) {
        java.util.List<Integer> failedUserIds = new java.util.ArrayList<>();
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            boolean success = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
            if (!success) {
                failedUserIds.add(userId);
            }
        }
        if (!failedUserIds.isEmpty()) {
            // For unknown reason, sometimes this could happen
            // Kill Shizuku app and try again could work
            for (int userId : failedUserIds) {
                try {
                    LOGGER.e("kill %s in user %d and try again", MANAGER_APPLICATION_ID, userId);
                    ActivityManagerApis.forceStopPackageNoThrow(MANAGER_APPLICATION_ID, userId);
                } catch (Throwable tr) {
                    LOGGER.e(tr, "failed to kill package");
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.w(e, "Interrupted while sleeping before retry");
                Thread.currentThread().interrupt();
            }
            for (int userId : failedUserIds) {
                try {
                    boolean success = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
                    if (success) {
                        LOGGER.e("retry succeeded for user %d", userId);
                    } else {
                        LOGGER.e("retry failed for user %d", userId);
                    }
                } catch (Throwable tr) {
                    LOGGER.e(tr, "retry failed");
                }
            }
        }
    }

    static void sendBinderToManager(Binder binder, int userId) {
        boolean success = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
        if (!success) {
            // For unknown reason, sometimes this could happens
            // Kill Shizuku app and try again could work
            try {
                LOGGER.e("kill %s in user %d and try again", MANAGER_APPLICATION_ID, userId);
                ActivityManagerApis.forceStopPackageNoThrow(MANAGER_APPLICATION_ID, userId);

                Runnable retryAction = () -> {
                    try {
                        boolean retrySuccess = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
                        if (retrySuccess) {
                            LOGGER.e("retry succeeded");
                        } else {
                            LOGGER.e("retry failed");
                        }
                    } catch (Throwable tr) {
                        LOGGER.e(tr, "retry failed");
                    }
                };

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    HandlerUtil.getMainHandler().postDelayed(retryAction, 1000);
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        LOGGER.w(e, "Interrupted while sleeping before retry");
                        Thread.currentThread().interrupt();
                    }
                    retryAction.run();
                }
            } catch (Throwable tr) {
                LOGGER.e(tr, "kill failed");
            }
        }
    }

    static boolean sendBinderToUserApp(Binder binder, String packageName, int userId) {
        try {
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
                    316/* PowerExemptionManager#REASON_SHELL */, "shell");
        } catch (Throwable tr) {
            LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName);
        }

        String name = packageName + ".shizuku";
        IContentProvider provider = null;

        /*
         When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process)
         will always get a new instance of android.os.BinderProxy.

         In the implementation of getContentProviderExternal and removeContentProviderExternal, received
         IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so
         removeContentProviderExternal will never work.

         Luckily, we can pass null. When token is token, count will be used.
         */
        IBinder token = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, "com.android.shell");
            if (provider == null) {
                LOGGER.e("provider is null %s %d", name, userId);
                return false;
            }
            if (!provider.asBinder().pingBinder()) {
                LOGGER.e("provider is dead %s %d", name, userId);
                return false;
            }

            Bundle extra = new Bundle();
            if (MANAGER_APPLICATION_ID.equals(packageName)) {
                extra.putParcelable("af.shizuku.plus.api.intent.extra.BINDER", new af.shizuku.api.BinderContainer(binder));
            }
            extra.putParcelable("rikka.shizuku.intent.extra.BINDER", new rikka.shizuku.BinderContainer(binder));
            extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new moe.shizuku.api.BinderContainer(binder));

            Bundle reply = IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
            if (reply != null) {
                LOGGER.i("send binder to user app %s in user %d", packageName, userId);
                return true;
            } else {
                LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId);
                return false;
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "failed to send binder to user app %s in user %d", packageName, userId);
            return false;
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, token);
                } catch (Throwable tr) {
                    LOGGER.w(tr, "removeContentProviderExternal");
                }
            }
        }
    }

    // ------ Sui only ------

    @Override
    public IVirtualMachineManager getVirtualMachineManager() {
        enforceCallingPermission("getVirtualMachineManager");
        if (!isFeatureEnabled("avf_manager")) return null;
        return virtualMachineManager;
    }

    @Override
    public IStorageProxy getStorageProxy() {
        enforceCallingPermission("getStorageProxy");
        if (!isFeatureEnabled("storage_proxy")) return null;
        return storageProxy;
    }

    @Override
    public IAICorePlus getAICorePlus() {
        enforceCallingPermission("getAICorePlus");
        if (!isFeatureEnabled("ai_core_plus")) return null;
        return aiCorePlus;
    }

    @Override
    public IWindowManagerPlus getWindowManagerPlus() {
        enforceCallingPermission("getWindowManagerPlus");
        if (!isFeatureEnabled("window_manager_plus")) return null;
        return windowManagerPlus;
    }

    @Override
    public IContinuityBridge getContinuityBridge() {
        enforceCallingPermission("getContinuityBridge");
        if (!isFeatureEnabled("continuity_bridge")) return null;
        return continuityBridge;
    }

    @Override
    public IOverlayManagerPlus getOverlayManagerPlus() {
        enforceCallingPermission("getOverlayManagerPlus");
        if (!isFeatureEnabled("overlay_manager_plus")) return null;
        return overlayManagerPlus;
    }

    @Override
    public INetworkGovernorPlus getNetworkGovernorPlus() {
        enforceCallingPermission("getNetworkGovernorPlus");
        if (!isFeatureEnabled("network_governor_plus")) return null;
        return networkGovernorPlus;
    }

    @Override
    public IActivityManagerPlus getActivityManagerPlus() {
        enforceCallingPermission("getActivityManagerPlus");
        if (!isFeatureEnabled("activity_manager_plus")) return null;
        return activityManagerPlus;
    }

    @Override
    public void elevateApp(String packageName) {
        enforceCallingPermission("elevateApp");
        if (packageName == null || packageName.isEmpty()) return;

        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(packageName, 0, 0);
        if (ai == null) {
            LOGGER.w("elevateApp: Package not found: " + packageName);
            return;
        }

        performAppOpsElevation(packageName, ai.uid);
    }

    private void performAppOpsElevation(String packageName, int uid) {
        LOGGER.i("Plus: elevating AppOps and permissions for " + packageName + " (UID " + uid + ")");
        try {
            IBinder binder = ServiceManager.getService("appops");
            if (binder != null) {
                Object service = Class.forName("com.android.internal.app.IAppOpsService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
                java.lang.reflect.Method setMode = service.getClass().getMethod("setMode", int.class, int.class, String.class, int.class);
                // 24 = OP_SYSTEM_ALERT_WINDOW, 43 = OP_GET_USAGE_STATS, 63 = OP_WRITE_SETTINGS,
                // 65 = OP_SYSTEM_ALERT_WINDOW (fallback), 66 = OP_REQUEST_INSTALL_PACKAGES,
                // 100 = OP_MANAGE_EXTERNAL_STORAGE, 103 = OP_ACCESS_RESTRICTED_SETTINGS, 
                // 121 = OP_SCHEDULE_EXACT_ALARM (privileged)
                int[] opsToElevate = {24, 43, 63, 65, 66, 100, 103, 107, 111, 119, 121};
                for (int op : opsToElevate) {
                    try {
                        setMode.invoke(service, op, uid, packageName, 0); // 0 = MODE_ALLOWED
                    } catch (Exception e) {
                        LOGGER.w(e, "Plus: Failed to set AppOps mode %d for uid %d", op, uid);
                    }
                }
            }
            // Also try to grant WRITE_SECURE_SETTINGS and DUMP directly via shell
            Runtime.getRuntime().exec(new String[]{"pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS"});
            Runtime.getRuntime().exec(new String[]{"pm", "grant", packageName, "android.permission.DUMP"});
        } catch (Exception e) {
            LOGGER.e(e, "Plus: AppOps elevation failed for " + packageName);
        }
    }

    @Override
    public List<String> getRecentLogs() {
        enforceCallingPermission("getRecentLogs");
        synchronized (serverLogs) {
            return new ArrayList<>(serverLogs);
        }
    }

    @Override
    public String getPlusSetting(String key) {
        enforceCallingPermission("getPlusSetting");
        return plusSettingsMap.get(key);
    }

    @Override
    public boolean isPlusFeatureEnabled(String key) {
        enforceCallingPermission("isPlusFeatureEnabled");
        return checkPlusFeatureEnabled(key);
    }

    private af.shizuku.server.IAIAutomationBridge aiAutomationBridge;

    @Override
    public void registerAIAutomationBridge(af.shizuku.server.IAIAutomationBridge bridge) {
        enforceCallingPermission("registerAIAutomationBridge");
        this.aiAutomationBridge = bridge;
        if (aiCorePlus != null) {
            aiCorePlus.setAutomationBridge(bridge);
        }
    }

    @Override
    public void dispatchPackageChanged(Intent intent) throws RemoteException {
        enforceManagerPermission("dispatchPackageChanged");
        String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action) || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            android.net.Uri data = intent.getData();
            if (data != null) {
                String packageName = data.getSchemeSpecificPart();
                if (packageName != null) {
                    clientManager.remove(packageName);
                }
            }
        }
    }

    @Override
    public boolean isHidden(int uid) throws RemoteException {
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        if (entry != null) {
            // Check if it's hidden in Shizuku+ terms (this might need to be linked to ShizukuSettings in the future,
            // but for now the manager app handles the 'hidden' state via its own shared prefs).
            // Actually, the server's 'isHidden' might be used for something else.
            // Let's ensure it returns the correct state if we ever sync hidden state to server.
        }
        return false;
    }
}
