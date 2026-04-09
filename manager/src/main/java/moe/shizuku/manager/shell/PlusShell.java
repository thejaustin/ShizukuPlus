package moe.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Arrays;
import java.util.List;

import moe.shizuku.server.IAICorePlus;
import moe.shizuku.server.IStorageProxy;
import moe.shizuku.server.IVirtualMachineManager;
import moe.shizuku.server.IShizukuService;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;
import moe.shizuku.manager.utils.Logger;

public class PlusShell {

    private static final Logger LOGGER = new Logger("PlusShell");

    private static void printHelp() {
        System.out.println("ShizukuPlus CLI Helper (plus)");
        System.out.println("Usage: plus [command] [args]");
        System.out.println("");
        System.out.println("Commands:");
        System.out.println("  vm list                   List all Microdroid VMs");
        System.out.println("  vm [start|stop|delete|status] [name]   Manage a specific VM");
        System.out.println("  aicore [touch|swipe|text|dump|pixel]   AI Automation & Intelligence");
        System.out.println("  storage [ls|cat|rm|mkdir|stat] [path]  Manage privileged storage");
        System.out.println("  su [command]              Run command via SU Bridge");
        System.out.println("  appops [pkg]              Elevate permissions for package");
        System.out.println("  log                       View the privileged activity log (server-side)");
        System.out.println("  doctor                    Run system diagnostics");
        System.out.println("  spoof                     View current device identity spoofing");
        System.out.println("  help                      Show this help message");
        System.out.flush();
    }

    private static void handleSu(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            System.out.println("Usage: plus su [command]");
            return;
        }

        String[] fullCmd = new String[args.length];
        fullCmd[0] = "su";
        System.arraycopy(args, 1, fullCmd, 1, args.length - 1);

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        // This will be intercepted by ShizukuService.newProcess
        service.newProcess(fullCmd, null, null);
        System.out.println("Command sent to SU Bridge.");
    }

    private static void handleAppOps(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            System.out.println("Usage: plus appops [package_name]");
            return;
        }

        String packageName = args[1];
        System.out.println("Requesting permission elevation for: " + packageName);

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        service.elevateApp(packageName);
        System.out.println("Elevation request sent to server.");
    }

    private static void handleLog(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        List<String> logs = service.getRecentLogs();
        if (logs == null || logs.isEmpty()) {
            System.out.println("No recent privileged activities recorded in server buffer.");
        } else {
            System.out.println("Recent Privileged Activities (Server-side Buffer):");
            for (String log : logs) {
                System.out.println(log);
            }
        }
        System.out.flush();
    }

    private static void handleVm(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            System.out.println("Usage: plus vm [list|start|stop|delete|status]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IVirtualMachineManager vmManager = service.getVirtualMachineManager();
        if (vmManager == null) {
            System.out.println("Error: VM Manager feature is disabled in Shizuku+ settings.");
            return;
        }
        
        String command = args[1];
        String name = args.length > 2 ? args[2] : null;

        switch (command) {
            case "list":
                List<String> vms = vmManager.list();
                if (vms.isEmpty()) System.out.println("No Microdroid VMs found.");
                else {
                    System.out.println("Microdroid VMs:");
                    for (String vm : vms) System.out.println("  - " + vm);
                }
                break;
            case "start":
                if (name == null) System.out.println("Usage: plus vm start [name]");
                else {
                    System.out.println("Starting VM: " + name);
                    if (vmManager.start(name)) System.out.println("VM started successfully.");
                    else System.out.println("Failed to start VM.");
                }
                break;
            case "stop":
                if (name == null) System.out.println("Usage: plus vm stop [name]");
                else {
                    if (vmManager.stop(name)) System.out.println("VM stopped.");
                    else System.out.println("Failed to stop VM.");
                }
                break;
            case "delete":
                if (name == null) System.out.println("Usage: plus vm delete [name]");
                else {
                    if (vmManager.delete(name)) System.out.println("VM deleted.");
                    else System.out.println("Failed to delete VM.");
                }
                break;
            case "status":
                if (name == null) System.out.println("Usage: plus vm status [name]");
                else {
                    String status = vmManager.getStatus(name);
                    System.out.println("VM Status (" + name + "): " + (status != null ? status : "UNKNOWN"));
                }
                break;
            default:
                System.out.println("Unknown VM command: " + command);
                System.out.println("Usage: plus vm [list|start|stop|delete|status]");
        }
    }

    private static void handleAiCore(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            System.out.println("Usage: plus aicore [touch|swipe|text|dump|pixel|context] [args]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IAICorePlus aicore = service.getAICorePlus();
        if (aicore == null) {
            System.out.println("Error: AICore+ feature is disabled in Shizuku+ settings.");
            return;
        }

        String command = args[1];
        switch (command) {
            case "touch":
                if (args.length < 4) System.out.println("Usage: plus aicore touch [x] [y]");
                else {
                    float x = Float.parseFloat(args[2]);
                    float y = Float.parseFloat(args[3]);
                    if (aicore.simulateTouch(x, y)) System.out.println("Touch simulated at (" + x + ", " + y + ")");
                    else System.out.println("Failed to simulate touch.");
                }
                break;
            case "swipe":
                if (args.length < 6) System.out.println("Usage: plus aicore swipe [x1] [y1] [x2] [y2] [duration_ms]");
                else {
                    float x1 = Float.parseFloat(args[2]);
                    float y1 = Float.parseFloat(args[3]);
                    float x2 = Float.parseFloat(args[4]);
                    float y2 = Float.parseFloat(args[5]);
                    int duration = args.length > 6 ? Integer.parseInt(args[6]) : 300;
                    if (aicore.simulateSwipe(x1, y1, x2, y2, duration)) System.out.println("Swipe simulated.");
                    else System.out.println("Failed to simulate swipe.");
                }
                break;
            case "text":
                if (args.length < 3) System.out.println("Usage: plus aicore text [content]");
                else {
                    StringBuilder text = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        text.append(args[i]).append(i == args.length - 1 ? "" : " ");
                    }
                    if (aicore.simulateText(text.toString())) System.out.println("Text input simulated.");
                    else System.out.println("Failed to simulate text input.");
                }
                break;
            case "dump":
                String hierarchy = aicore.getWindowHierarchy();
                if (hierarchy != null && !hierarchy.isEmpty()) {
                    System.out.println(hierarchy);
                } else {
                    System.out.println("Error: Failed to dump window hierarchy.");
                }
                break;
            case "pixel":
                if (args.length < 4) System.out.println("Usage: plus aicore pixel [x] [y]");
                else {
                    int x = Integer.parseInt(args[2]);
                    int y = Integer.parseInt(args[3]);
                    int color = aicore.getPixelColor(x, y);
                    System.out.printf("Pixel at (%d, %d): #%08X\n", x, y, color);
                }
                break;
            case "context":
                Bundle context = aicore.getSystemContext();
                if (context != null) {
                    System.out.println("AICore+ System Context:");
                    for (String key : context.keySet()) {
                        System.out.println("  " + key + ": " + context.get(key));
                    }
                } else {
                    System.out.println("Error: Failed to get system context.");
                }
                break;
            default:
                System.out.println("Unknown aicore command: " + command);
        }
        System.out.flush();
    }

    private static void handleStorage(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 3) {
            System.out.println("Usage: plus storage [ls|cat|rm|mkdir|stat] [path]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IStorageProxy storage = service.getStorageProxy();
        if (storage == null) {
            System.out.println("Error: Storage Proxy feature is disabled in Shizuku+ settings.");
            return;
        }

        String command = args[1];
        String path = args[2];

        switch (command) {
            case "ls":
                List<String> files = storage.listFiles(path);
                if (files == null) {
                    System.out.println("Error: Could not access path or directory empty.");
                } else {
                    for (String file : files) System.out.println(file);
                }
                break;
            case "cat":
                try (android.os.ParcelFileDescriptor pfd = storage.openFile(path, 0x10000000 /* MODE_READ_ONLY */)) {
                    if (pfd == null) {
                        System.out.println("Error: Could not open file: " + path);
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
                    System.err.println("Error reading file: " + e.getMessage());
                }
                break;
            case "rm":
                if (storage.delete(path)) System.out.println("Deleted: " + path);
                else System.out.println("Failed to delete: " + path);
                break;
            case "mkdir":
                if (storage.mkdir(path)) System.out.println("Created directory: " + path);
                else System.out.println("Failed to create directory: " + path);
                break;
            case "stat":
                Bundle info = storage.getFileInfo(path);
                if (info.getBoolean("exists")) {
                    System.out.println("File: " + path);
                    System.out.println("Size: " + info.getLong("size") + " bytes");
                    System.out.println("Last Modified: " + new java.util.Date(info.getLong("lastModified")));
                    System.out.println("Type: " + (info.getBoolean("isDirectory") ? "Directory" : "File"));
                } else {
                    System.out.println("Path does not exist: " + path);
                }
                break;
            default:
                System.out.println("Unknown storage command: " + command);
                System.out.println("Usage: plus storage [ls|cat|rm|mkdir|stat] [path]");
        }
        System.out.flush();
    }

    private static void handleSpoof(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        boolean enabled = service.isPlusFeatureEnabled("spoof_device");
        String target = service.getPlusSetting("spoof_target");
        
        System.out.println("Identity Spoofing: " + (enabled ? "ACTIVE" : "DISABLED"));
        if (enabled) {
            System.out.println("Current Target: " + (target != null ? target : "None (Default)"));
        }
        System.out.println("Note: Spoof targets are managed via Shizuku+ Settings > Root Compatibility.");
    }

    private static void handleDoctor(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        System.out.println("Shizuku+ System Doctor Diagnostics");
        System.out.println("==================================");
        System.out.println("Server Version: " + service.getVersion());
        System.out.println("Server UID: " + service.getUid());
        System.out.println("SELinux Context: " + service.getSELinuxContext());
        
        System.out.println("\nPlus Features Status:");
        String[] features = {"su_bridge", "shell_interceptor", "avf_manager", "storage_proxy", "ai_core_plus"};
        for (String f : features) {
            System.out.printf("  %-18s: %s\n", f, service.isPlusFeatureEnabled(f) ? "ENABLED" : "DISABLED");
        }
        
        System.out.println("\nDevice Identity:");
        System.out.println("  Model: " + android.os.Build.MODEL);
        System.out.println("  Brand: " + android.os.Build.BRAND);
        System.out.println("  SDK: " + android.os.Build.VERSION.SDK_INT);
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
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
                case "aicore":
                    handleAiCore(args, binder);
                    break;
                case "storage":
                    handleStorage(args, binder);
                    break;
                case "su":
                    handleSu(args, binder);
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
                    System.out.println("Unknown command: " + args[0]);
                    printHelp();
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "Uncaught exception in PlusShell");
        } finally {
            System.out.flush();
            System.exit(0);
        }
    }
}
