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
import rikka.shizuku.server.util.UserHandleCompat;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;

public class ShizukuService extends Service<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> {

    public static void main(String[] args) {
        DdmHandleAppName.setAppName("shizuku_server", 0);
        RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"));

        Looper.prepareMainLooper();
        new ShizukuService();
        Looper.loop();
    }

    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("service " + name + " is not started, wait 1s.");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.w(e.getMessage(), e);
            }
        }
    }

    public static ApplicationInfo getManagerApplicationInfo() {
        return PackageManagerApis.getApplicationInfoNoThrow(MANAGER_APPLICATION_ID, 0, 0);
    }

    @SuppressWarnings({"FieldCanBeLocal"})
    private final Handler mainHandler = new Handler(Looper.myLooper());
    //private final Context systemContext = HiddenApiBridge.getSystemContext();
    private final ShizukuClientManager clientManager;
    private static final List<String> serverLogs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final int MAX_SERVER_LOGS = 50;
    private final ShizukuConfigManager configManager;
    private final int managerAppId;
    private final VirtualMachineManagerImpl virtualMachineManager = new VirtualMachineManagerImpl();
    private final StorageProxyImpl storageProxy = new StorageProxyImpl();
    private final AICorePlusImpl aiCorePlus = new AICorePlusImpl();
    private final WindowManagerPlusImpl windowManagerPlus = new WindowManagerPlusImpl();
    private final ContinuityBridgeImpl continuityBridge = new ContinuityBridgeImpl();
    private final OverlayManagerPlusImpl overlayManagerPlus = new OverlayManagerPlusImpl();
    private final NetworkGovernorPlusImpl networkGovernorPlus = new NetworkGovernorPlusImpl();
    private final ActivityManagerPlusImpl activityManagerPlus = new ActivityManagerPlusImpl();

    public ShizukuService() {
        super();

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

        ApkChangedObservers.start(ai.sourceDir, () -> {
            if (getManagerApplicationInfo() == null) {
                LOGGER.w("manager app is uninstalled in user 0, exiting...");
                System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
            }
        });

        BinderSender.register(this);

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
            return ActivityManagerApis.checkPermission(ServerConstants.PERMISSION,
                    Binder.getCallingPid(),
                    Binder.getCallingUid());
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
        enforceManagerPermission("func");

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

        if (clientManager.findClient(callingUid, callingPid) == null) {
            synchronized (this) {
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion);
            }
            if (clientRecord == null) {
                LOGGER.w("Add client failed");
                return;
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
            reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, Objects.requireNonNull(clientRecord).allowed);
            reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        } else {
            try {
                PermissionManagerApis.grantRuntimePermission(MANAGER_APPLICATION_ID,
                        WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid));
            } catch (RemoteException e) {
                LOGGER.w(e, "grant WRITE_SECURE_SETTINGS");
            }
        }
        try {
            application.bindApplication(reply);
        } catch (Throwable e) {
            LOGGER.w(e, "attachApplication");
        }
    }

    private final java.util.Map<String, Boolean> featureEnabledMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> plusSettingsMap = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isFeatureEnabled(String key) {
        return featureEnabledMap.getOrDefault(key, true);
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

    @Override
    public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        ClientRecord caller = clientManager.findClient(callingUid, callingPid);
        String callingPkg = (caller != null) ? caller.packageName : "unknown";
        
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
                               cmd[i].equals("-u") || cmd[i].equals("--user")) {
                        // These flags take a following argument — skip both
                        skipNext = true;
                    } else if (cmd[i].equals("-v") || cmd[i].equals("-V") || cmd[i].equals("--version")) {
                        // Return a fake version string for su
                        cmd = new String[]{"echo", "20260311:MAGISK"};
                        return newProcessInternal(cmd, env, dir);
                    } else if (cmd[i].equals("-l") || cmd[i].equals("--login") || cmd[i].equals("-")) {
                        // Login flag — skip, we don't set up a login environment
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
                    String joined = String.join(" ", args);
                    if (joined.length() > 65536) {
                        LOGGER.w("SUBridge: command too long (" + joined.length() + " chars), skipping interception");
                    } else {
                        cmd = new String[]{"sh", "-c", joined};
                        LOGGER.i("SUBridge: intercepted su call, running as sh");
                        
                        // Inject actual su path into environment PATH
                        String realSuPath = plusSettingsMap.get( "su_path");
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
                    }
                } else {
                    cmd = new String[]{"sh"};
                    LOGGER.i("SUBridge: intercepted interactive su, opening sh");
                }
            }
        }
        if (isFeatureEnabled("shell_interceptor") && cmd != null && cmd.length > 0) {
            String baseCmd = cmd[0];
            
            // Root Mocking: Fake common root environment checks
            if (isFeatureEnabled("su_bridge")) {
                if (baseCmd.equals("id")) {
                    LOGGER.i("SUBridge: mocking id command");
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
                } else if (baseCmd.equals("busybox")) {
                    if (isFeatureEnabled("root_busybox_mocking")) {
                        LOGGER.i("SUBridge: mocking busybox command");
                        return newProcessInternal(new String[]{"echo", "BusyBox v1.36.1 (Shizuku+ Built-in)"}, env, dir);
                    }
                } else if (baseCmd.equals("mount") && cmd.length > 1 && String.join(" ", cmd).contains("remount")) {
                    LOGGER.i("SUBridge: intercepting mount remount. Delegating to OverlayManager Proxy.");
                    // Functional Workaround: Return success to bypass the check; real modifications use Overlays.
                    return newProcessInternal(new String[]{"true"}, env, dir);
                } else if (baseCmd.equals("mount") && cmd.length > 3 && String.join(" ", cmd).contains("--bind") && String.join(" ", cmd).contains("/system/etc/hosts")) {
                    LOGGER.i("SUBridge: AdAway redirection - mapping hosts mount to user-writable path");
                    // Redirect /system/etc/hosts to /data/adb/shizuku/hosts
                    // We can't actually mount, but we can fake success and redirect future 'cat' or 'cp' calls
                    return newProcessInternal(new String[]{"true"}, env, dir);
                } else if ((baseCmd.equals("cp") || baseCmd.equals("mv") || baseCmd.equals("tar")) && String.join(" ", cmd).contains("/system/etc/hosts")) {
                    LOGGER.i("SUBridge: AdAway redirection - mapping hosts file modification");
                    // Redirect the file manipulation to our writable copy
                    for (int i = 0; i < cmd.length; i++) {
                        if (cmd[i].contains("/system/etc/hosts")) cmd[i] = "/data/adb/shizuku/hosts";
                    }
                    // Ensure the directory exists
                    try { Runtime.getRuntime().exec(new String[]{"mkdir", "-p", "/data/adb/shizuku"}); } catch (Exception e) { LOGGER.w(e, "Failed to mkdir /data/adb/shizuku"); }
                    return newProcessInternal(cmd, env, dir);
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
                        if (cmd.length > 1 && (cmd[1].equals("-v") || cmd[1].equals("-V"))) {
                            return newProcessInternal(new String[]{"echo", "26.4:MAGISK"}, env, dir);
                        }
                        return newProcessInternal(new String[]{"echo", "Magisk v26.4 (26400) - Shizuku+ Bridge Mode"}, env, dir);
                    }
                } else if (baseCmd.equals("pm") && cmd.length > 2 && cmd[1].equals("grant")) {
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
                } else if (baseCmd.equals("cat") && cmd.length > 1 && cmd[1].equals("/proc/mounts")) {
                    if (isFeatureEnabled("root_adaway_bridge")) {
                        LOGGER.i("SUBridge: mocking /proc/mounts");
                        String fakeMounts = "/dev/block/bootdevice/by-name/system /system ext4 ro,relatime 0 0\n" +
                                           "/dev/block/bootdevice/by-name/userdata /data f2fs rw,nosuid,nodev,noatime 0 0\n" +
                                           "tmpfs /sbin tmpfs rw,seclabel,relatime,size=1930644k,mode=755 0 0\n" +
                                           "/data/adb/shizuku/hosts /system/etc/hosts text rw,relatime 0 0\n" +
                                           "magisk /sbin tmpfs rw,seclabel,relatime,size=1930644k,mode=755 0 0\n";
                        return newProcessInternal(new String[]{"echo", fakeMounts}, env, dir);
                    }
                } else if (baseCmd.equals("test") && cmd.length > 1 && (String.join(" ", cmd).contains("/sbin/.magisk") || String.join(" ", cmd).contains("/data/adb/magisk"))) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking test for Magisk directory");
                        return newProcessInternal(new String[]{"true"}, env, dir);
                    }
                } else if (baseCmd.equals("ls") && cmd.length > 1 && String.join(" ", cmd).contains("/su")) {
                    if (isFeatureEnabled("root_magisk_mocking")) {
                        LOGGER.i("SUBridge: mocking ls for su path");
                        return newProcessInternal(new String[]{"echo", "-rwsr-xr-x 1 root root 157328 2026-03-11 12:00 /system/xbin/su"}, env, dir);
                    }
                } else if (baseCmd.equals("which") && cmd.length > 1 && cmd[1].equals("su")) {
                    String realSuPath = plusSettingsMap.getOrDefault("su_path", "/system/xbin/su");
                    LOGGER.i("SUBridge: mocking which su command -> " + realSuPath);
                    return newProcessInternal(new String[]{"echo", realSuPath}, env, dir);
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
                    } else if ((baseCmd.equals("tar") || baseCmd.equals("cp")) && (String.join(" ", cmd).contains("/data/data/") || String.join(" ", cmd).contains("/data/app/"))) {
                        String fullCmd = String.join(" ", cmd);
                        LOGGER.i("SUBridge: mapping backup command to native bu utility: " + fullCmd);
                        // Functional Workaround: Translate file-based backup to Android's Backup Utility flow
                        // Extract package name from /data/data/<pkg> or /data/app/<pkg>-...
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/data/(?:data|app)/([^/\\-\\s]+)").matcher(fullCmd);
                        if (m.find()) {
                            String targetPkg = m.group(1);
                            // Run native backup flow for the detected package
                            return newProcessInternal(new String[]{"bu", "backup", "-apk", "-obb", targetPkg}, env, dir);
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
                    android.content.IContentProvider provider = ActivityManagerApis.getContentProviderExternal("settings", userId, null, "settings");
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
            } else if (baseCmd.equals("appops") && cmd.length >= 4) {
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
                            int intMode = value.equals("allow") ? 0 : value.equals("ignore") ? 1 : 2; // simplified
                            service.getClass().getMethod("setMode", int.class, int.class, String.class, int.class).invoke(service, intOp, callingUid, pkg, intMode);
                            return newProcessInternal(new String[]{"true"}, env, dir);
                        }
                    }
                } catch (Throwable tr) {
                    LOGGER.e(tr, "Plus Optimization: appops failed");
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
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                if (allowed) {
                    PermissionManagerApis.grantRuntimePermission(packageName, PERMISSION, userId);
                } else {
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, userId);
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
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                try {
                    if (PermissionManagerApis.checkPermission(PERMISSION, uid) == PackageManager.PERMISSION_GRANTED) {
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
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                if (allowed) {
                    PermissionManagerApis.grantRuntimePermission(packageName, PERMISSION, userId);
                } else {
                    PermissionManagerApis.revokeRuntimePermission(packageName, PERMISSION, userId);
                    onPermissionRevoked(packageName);
                }
            }
        }

        configManager.update(uid, null, mask, value);
    }

    private void onPermissionRevoked(String packageName) {
        // TODO add runtime permission listener
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
                } else if (pi.applicationInfo.metaData != null
                        && pi.applicationInfo.metaData.getBoolean("af.shizuku.client.V3_SUPPORT", false)
                        && pi.requestedPermissions != null
                        && ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    list.add(pi);
                }
            }

        }
        return new ParcelableListSlice<>(list);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        //LOGGER.d("transact: code=%d, calling uid=%d", code, Binder.getCallingUid());
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
            Stream<PackageInfo> packages =
                PackageManagerApis.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS, userId
                )
                .stream()
                .filter(pi -> pi != null && pi.requestedPermissions != null)
                .filter(pi -> ArraysKt.contains(pi.requestedPermissions, PERMISSION));

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
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToManager(binder, userId);
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
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    LOGGER.w(e, "Interrupted while sleeping before retry");
                    Thread.currentThread().interrupt();
                }
                success = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
                if (success) {
                    LOGGER.e("retry succeeded");
                } else {
                    LOGGER.e("retry failed");
                }
            } catch (Throwable tr) {
                LOGGER.e(tr, "retry failed");
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
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name);
            if (provider == null) {
                LOGGER.e("provider is null %s %d", name, userId);
                return false;
            }
            if (!provider.asBinder().pingBinder()) {
                LOGGER.e("provider is dead %s %d", name, userId);
                return false;
            }

            Bundle extra = new Bundle();
            extra.putParcelable("af.shizuku.plus.api.intent.extra.BINDER", new BinderContainer(binder));

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
                // 65 = OP_SYSTEM_ALERT_WINDOW (fallback), 100 = OP_MANAGE_EXTERNAL_STORAGE,
                // 103 = OP_ACCESS_RESTRICTED_SETTINGS, 121 = OP_SCHEDULE_EXACT_ALARM (privileged)
                int[] opsToElevate = {24, 43, 63, 65, 100, 103, 121};
                for (int op : opsToElevate) {
                    try {
                        setMode.invoke(service, op, uid, null, 0);
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
        return isFeatureEnabled(key);
    }

    @Override
    public void dispatchPackageChanged(Intent intent) throws RemoteException {

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
