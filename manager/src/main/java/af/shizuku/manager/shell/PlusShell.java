package af.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Arrays;
import java.util.List;

import af.shizuku.server.IAICorePlus;
import af.shizuku.server.IStorageProxy;
import af.shizuku.server.IVirtualMachineManager;
import af.shizuku.server.IShizukuService;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;
import af.shizuku.manager.utils.Logger;

public class PlusShell {

    private static final Logger LOGGER = new Logger("PlusShell");

    private static final Logger LOGGER = new Logger("PlusShell");

    private static void printHelp() {
        LOGGER.i("Shizuku+ CLI Helper (plus)");
        LOGGER.w("Usage: plus [command] [args]");
        LOGGER.i("");
        LOGGER.i("Commands:");
        LOGGER.i("  vm list                   List all Microdroid VMs");
        LOGGER.i("  vm [start|stop|delete|status] [name]   Manage a specific VM");
        LOGGER.i("  aicore [touch|swipe|text|dump|pixel]   AI Automation & Intelligence");
        LOGGER.i("  storage [ls|cat|rm|mkdir|stat] [path]  Manage privileged storage");
        LOGGER.i("  am [freeze|unfreeze|stop|clear|kill-all] [pkg]  Manage app state");
        LOGGER.i("  wm [immersive|dex-high-refresh] [on|off]  Manage display and windows");
        LOGGER.i("  su [command]              Run command via SU Bridge");
        LOGGER.i("  reboot [recovery|download] Reboot to specialized modes");
        LOGGER.i("  appops [pkg]              Elevate permissions for package");
        LOGGER.i("  log                       View the privileged activity log (server-side)");
        LOGGER.i("  doctor                    Run system diagnostics");
        LOGGER.i("  spoof                     View current device identity spoofing");
        LOGGER.i("  help                      Show this help message");
    }

    private static void handleSu(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus su [command]");
            return;
        }

        String[] fullCmd = new String[args.length];
        fullCmd[0] = "su";
        System.arraycopy(args, 1, fullCmd, 1, args.length - 1);

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        // This will be intercepted by ShizukuService.newProcess
        service.newProcess(fullCmd, null, null);
        LOGGER.i("Command sent to SU Bridge.");
    }

    private static void handleAppOps(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus appops [package_name]");
            return;
        }

        String packageName = args[1];
        LOGGER.i("Requesting permission elevation for: " + packageName);

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        service.elevateApp(packageName);
        LOGGER.i("Elevation request sent to server.");
    }

    private static void handleReboot(String[] args) {
        String mode = args.length > 1 ? args[1] : "";
        try {
            String[] cmd = mode.isEmpty() ? new String[]{"reboot"} : new String[]{"reboot", mode};
            LOGGER.i("Rebooting to " + (mode.isEmpty() ? "system" : mode) + "...");
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            LOGGER.e("Reboot failed: " + e.getMessage());
        }
    }

    private static void handleLog(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        List<String> logs = service.getRecentLogs();
        if (logs == null || logs.isEmpty()) {
            LOGGER.i("No recent privileged activities recorded in server buffer.");
        } else {
            LOGGER.i("Recent Privileged Activities (Server-side Buffer):");
            for (String log : logs) {
                LOGGER.i(log);
            }
        }
    }

    private static void handleVm(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus vm [list|start|stop|delete|status]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IVirtualMachineManager vmManager = service.getVirtualMachineManager();
        if (vmManager == null) {
            LOGGER.e("Error: VM Manager feature is disabled in Shizuku+ settings.");
            return;
        }
        
        String command = args[1];
        String name = args.length > 2 ? args[2] : null;

        switch (command) {
            case "list":
                List<String> vms = vmManager.list();
                if (vms.isEmpty()) LOGGER.i("No Microdroid VMs found.");
                else {
                    LOGGER.i("Microdroid VMs:");
                    for (String vm : vms) LOGGER.i("  - " + vm);
                }
                break;
            case "start":
                if (name == null) LOGGER.w("Usage: plus vm start [name]");
                else {
                    LOGGER.i("Starting VM: " + name);
                    if (vmManager.start(name)) LOGGER.i("VM started successfully.");
                    else LOGGER.e("Failed to start VM.");
                }
                break;
            case "stop":
                if (name == null) LOGGER.w("Usage: plus vm stop [name]");
                else {
                    if (vmManager.stop(name)) LOGGER.i("VM stopped.");
                    else LOGGER.e("Failed to stop VM.");
                }
                break;
            case "delete":
                if (name == null) LOGGER.w("Usage: plus vm delete [name]");
                else {
                    if (vmManager.delete(name)) LOGGER.i("VM deleted.");
                    else LOGGER.e("Failed to delete VM.");
                }
                break;
            case "status":
                if (name == null) LOGGER.w("Usage: plus vm status [name]");
                else {
                    String status = vmManager.getStatus(name);
                    LOGGER.i("VM Status (" + name + "): " + (status != null ? status : "UNKNOWN"));
                }
                break;
            default:
                LOGGER.w("Unknown VM command: " + command);
                LOGGER.w("Usage: plus vm [list|start|stop|delete|status]");
        }
    }

    private static void handleAm(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus am [freeze|unfreeze|stop|kill-all] [package_name]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        af.shizuku.server.IActivityManagerPlus am = service.getActivityManagerPlus();
        if (am == null) {
            LOGGER.e("Error: Activity Manager Plus feature is disabled in Shizuku+ settings.");
            return;
        }

        String command = args[1];
        String packageName = args.length > 2 ? args[2] : null;

        switch (command) {
            case "freeze":
                if (packageName == null) LOGGER.w("Usage: plus am freeze [package]");
                else {
                    if (am.freezeApp(packageName)) LOGGER.i("App frozen: " + packageName);
                    else LOGGER.e("Failed to freeze app.");
                }
                break;
            case "unfreeze":
                if (packageName == null) LOGGER.w("Usage: plus am unfreeze [package]");
                else {
                    if (am.unfreezeApp(packageName)) LOGGER.i("App unfrozen: " + packageName);
                    else LOGGER.e("Failed to unfreeze app.");
                }
                break;
            case "stop":
                if (packageName == null) LOGGER.w("Usage: plus am stop [package]");
                else {
                    if (am.deepForceStop(packageName)) LOGGER.i("App force-stopped: " + packageName);
                    else LOGGER.e("Failed to stop app.");
                }
                break;
            case "clear":
                if (packageName == null) LOGGER.w("Usage: plus am clear [package]");
                else {
                    if (am.clearAppData(packageName)) LOGGER.i("App data cleared: " + packageName);
                    else LOGGER.e("Failed to clear app data.");
                }
                break;
            case "kill-all":
                if (am.killAllBackgroundProcesses()) LOGGER.i("All background processes killed.");
                else LOGGER.e("Failed to kill processes.");
                break;
            default:
                LOGGER.w("Unknown am command: " + command);
        }
    }

    private static void handleWm(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 3) {
            LOGGER.w("Usage: plus wm [immersive|dex-high-refresh] [on|off]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        af.shizuku.server.IWindowManagerPlus wm = service.getWindowManagerPlus();
        if (wm == null) {
            LOGGER.e("Error: Window Manager Plus feature is disabled.");
            return;
        }

        String command = args[1];
        boolean enabled = "on".equalsIgnoreCase(args[2]);

        switch (command) {
            case "immersive":
                wm.setImmersiveMode(enabled);
                LOGGER.i("Immersive mode: " + (enabled ? "ON" : "OFF"));
                break;
            case "dex-high-refresh":
                wm.setDexHighRefreshRate(enabled);
                LOGGER.i("DeX High Refresh Rate (120Hz): " + (enabled ? "ON" : "OFF"));
                break;
            default:
                LOGGER.w("Unknown wm command: " + command);
        }
    }

    private static void handleAiCore(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus aicore [touch|swipe|text|dump|pixel|context] [args]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IAICorePlus aicore = service.getAICorePlus();
        if (aicore == null) {
            LOGGER.e("Error: AICore+ feature is disabled in Shizuku+ settings.");
            return;
        }

        String command = args[1];
        switch (command) {
            case "touch":
                if (args.length < 4) LOGGER.w("Usage: plus aicore touch [x] [y]");
                else {
                    float x = Float.parseFloat(args[2]);
                    float y = Float.parseFloat(args[3]);
                    if (aicore.simulateTouch(x, y)) LOGGER.i("Touch simulated at (" + x + ", " + y + ")");
                    else LOGGER.e("Failed to simulate touch.");
                }
                break;
            case "swipe":
                if (args.length < 6) LOGGER.w("Usage: plus aicore swipe [x1] [y1] [x2] [y2] [duration_ms]");
                else {
                    float x1 = Float.parseFloat(args[2]);
                    float y1 = Float.parseFloat(args[3]);
                    float x2 = Float.parseFloat(args[4]);
                    float y2 = Float.parseFloat(args[5]);
                    int duration = args.length > 6 ? Integer.parseInt(args[6]) : 300;
                    if (aicore.simulateSwipe(x1, y1, x2, y2, duration)) LOGGER.i("Swipe simulated.");
                    else LOGGER.e("Failed to simulate swipe.");
                }
                break;
            case "text":
                if (args.length < 3) LOGGER.w("Usage: plus aicore text [content]");
                else {
                    StringBuilder text = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        text.append(args[i]).append(i == args.length - 1 ? "" : " ");
                    }
                    if (aicore.simulateText(text.toString())) LOGGER.i("Text input simulated.");
                    else LOGGER.e("Failed to simulate text input.");
                }
                break;
            case "dump":
                String hierarchy = aicore.getWindowHierarchy();
                if (hierarchy != null && !hierarchy.isEmpty()) {
                    LOGGER.i(hierarchy);
                } else {
                    LOGGER.e("Error: Failed to dump window hierarchy.");
                }
                break;
            case "pixel":
                if (args.length < 4) LOGGER.w("Usage: plus aicore pixel [x] [y]");
                else {
                    int x = Integer.parseInt(args[2]);
                    int y = Integer.parseInt(args[3]);
                    int color = aicore.getPixelColor(x, y);
                    LOGGER.i("Pixel at (%d, %d): #%08X", x, y, color);
                }
                break;
            case "context":
                Bundle context = aicore.getSystemContext();
                if (context != null) {
                    LOGGER.i("AICore+ System Context:");
                    for (String key : context.keySet()) {
                        LOGGER.i("  " + key + ": " + context.get(key));
                    }
                } else {
                    LOGGER.e("Error: Failed to get system context.");
                }
                break;
            default:
                LOGGER.w("Unknown aicore command: " + command);
        }
    }

    private static void handleStorage(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 3) {
            LOGGER.w("Usage: plus storage [ls|cat|rm|mkdir|stat] [path]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IStorageProxy storage = service.getStorageProxy();
        if (storage == null) {
            LOGGER.e("Error: Storage Proxy feature is disabled in Shizuku+ settings.");
            return;
        }

        String command = args[1];
        String path = args[2];

        switch (command) {
            case "ls":
                List<String> files = storage.listFiles(path);
                if (files == null) {
                    LOGGER.e("Error: Could not access path or directory empty.");
                } else {
                    for (String file : files) LOGGER.i(file);
                }
                break;
            case "cat":
                try (android.os.ParcelFileDescriptor pfd = storage.openFile(path, 0x10000000 /* MODE_READ_ONLY */)) {
                    if (pfd == null) {
                        LOGGER.e("Error: Could not open file: " + path);
                        return;
                    }
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor())) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) != -1) {
                            System.out.write(buffer, 0, len);
                        }
                    }
                } catch (java.io.IOException e) {
                    LOGGER.e("Error reading file: " + e.getMessage());
                }
                break;
            case "rm":
                if (storage.delete(path)) LOGGER.i("Deleted: " + path);
                else LOGGER.e("Failed to delete: " + path);
                break;
            case "mkdir":
                if (storage.mkdir(path)) LOGGER.i("Created directory: " + path);
                else LOGGER.e("Failed to create directory: " + path);
                break;
            case "stat":
                Bundle info = storage.getFileInfo(path);
                if (info.getBoolean("exists")) {
                    LOGGER.i("File: " + path);
                    LOGGER.i("Size: " + info.getLong("size") + " bytes");
                    LOGGER.i("Last Modified: " + new java.util.Date(info.getLong("lastModified")));
                    LOGGER.i("Type: " + (info.getBoolean("isDirectory") ? "Directory" : "File"));
                } else {
                    LOGGER.i("Path does not exist: " + path);
                }
                break;
            default:
                LOGGER.w("Unknown storage command: " + command);
                LOGGER.w("Usage: plus storage [ls|cat|rm|mkdir|stat] [path]");
        }
    }

    private static void handleSpoof(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        boolean enabled = service.isPlusFeatureEnabled("spoof_device");
        String target = service.getPlusSetting("spoof_target");
        
        LOGGER.i("Identity Spoofing: " + (enabled ? "ACTIVE" : "DISABLED"));
        if (enabled) {
            LOGGER.i("Current Target: " + (target != null ? target : "None (Default)"));
        }
        LOGGER.i("Note: Spoof targets are managed via Shizuku+ Settings > Root Compatibility.");
    }

    private static void handleDoctor(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        LOGGER.i("Shizuku+ System Doctor Diagnostics");
        LOGGER.i("==================================");
        LOGGER.i("Server Version: " + service.getVersion());
        LOGGER.i("Server UID: " + service.getUid());
        LOGGER.i("SELinux Context: " + service.getSELinuxContext());
        
        LOGGER.i("\nPlus Features Status:");
        String[] features = {"su_bridge", "shell_interceptor", "avf_manager", "storage_proxy", "ai_core_plus"};
        for (String f : features) {
            LOGGER.i("  %-18s: %s", f, service.isPlusFeatureEnabled(f) ? "ENABLED" : "DISABLED");
        }
        
        LOGGER.i("\nDevice Identity:");
        LOGGER.i("  Model: " + android.os.Build.MODEL);
        LOGGER.i("  Brand: " + android.os.Build.BRAND);
        LOGGER.i("  SDK: " + android.os.Build.VERSION.SDK_INT);
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        timber.log.Timber.plant(new timber.log.Timber.DebugTree());
        if (args.length == 0 || args[0].equals("help")) {
            printHelp();
            System.exit(0);
        }

        Shizuku.onBinderReceived(binder, packageName);
        
        try {
            switch (args[0]) {
                case "log":
                    handleLog(binder);
                    break;
                case "vm":
                    handleVm(args, binder);
                    break;
                case "am":
                    handleAm(args, binder);
                    break;
                case "wm":
                    handleWm(args, binder);
                    break;
                case "aicore":
                    handleAiCore(args, binder);
                    break;
                case "storage":
                    handleStorage(args, binder);
                    break;
                case "su":
                    handleSu(args, binder);
                    break;
                case "reboot":
                    handleReboot(args);
                    break;
                case "appops":
                    handleAppOps(args, binder);
                    break;
                case "spoof":
                    handleSpoof(binder);
                    break;
                case "doctor":
                    handleDoctor(binder);
                    break;
                default:
                    LOGGER.w("Unknown command: " + args[0]);
                    printHelp();
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "Uncaught exception in PlusShell");
        } finally {
            System.exit(0);
        }
    }
}
