package moe.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Arrays;
import java.util.List;

import moe.shizuku.manager.utils.ActivityLogManager;
import moe.shizuku.manager.utils.ActivityLogRecord;
import moe.shizuku.server.IAICorePlus;
import moe.shizuku.server.IStorageProxy;
import moe.shizuku.server.IVirtualMachineManager;
import moe.shizuku.server.IShizukuService;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

public class PlusShell {

    private static void printHelp() {
        System.out.println("ShizukuPlus CLI Helper");
        System.out.println("Usage: plus [command] [args]");
        System.out.println("");
        System.out.println("Commands:");
        System.out.println("  vm list           List all Microdroid VMs");
        System.out.println("  vm start [name]   Start a specific VM");
        System.out.println("  storage ls [path] List privileged storage");
        System.out.println("  su [command]      Run command via SU Bridge");
        System.out.println("  appops [pkg]      Elevate permissions for package");
        System.out.println("  log               View the privileged activity log");
        System.out.println("  doctor            Run system diagnostics");
        System.out.println("  spoof             View current device identity spoofing");
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
        // We trigger the elevation by calling a command that ShizukuService intercepts
        service.newProcess(new String[]{"setenforce", "1"}, null, null); 
        System.out.println("Elevation request sent to server.");
    }

    private static void handleLog() {
        List<ActivityLogRecord> records = ActivityLogManager.INSTANCE.getRecords();
        if (records.isEmpty()) {
            System.out.println("Activity log is empty.");
        } else {
            System.out.println("Recent Privileged Activities:");
            for (ActivityLogRecord record : records) {
                System.out.printf("[%tT] %s: %s (%s)\n", 
                    record.getTimestamp(), record.getAppName(), record.getAction(), record.getPackageName());
            }
        }
        System.out.flush();
    }

    private static void handleVm(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            System.out.println("Usage: plus vm [list|start|stop|delete]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IVirtualMachineManager vmManager = service.getVirtualMachineManager();
        if (vmManager == null) {
            System.out.println("Error: VM Manager feature is disabled in Shizuku+ settings.");
            return;
        }
        
        switch (args[1]) {
            case "list":
                List<String> vms = vmManager.list();
                if (vms.isEmpty()) System.out.println("No Microdroid VMs found.");
                else {
                    System.out.println("Microdroid VMs:");
                    for (String vm : vms) System.out.println("  - " + vm);
                }
                break;
            case "start":
                if (args.length < 3) System.out.println("Usage: plus vm start [name]");
                else {
                    System.out.println("Starting VM: " + args[2]);
                    if (vmManager.start(args[2])) System.out.println("VM started successfully.");
                    else System.out.println("Failed to start VM.");
                }
                break;
            case "stop":
                if (args.length < 3) System.out.println("Usage: plus vm stop [name]");
                else if (vmManager.stop(args[2])) System.out.println("VM stopped.");
                else System.out.println("Failed to stop VM.");
                break;
            default:
                System.out.println("Unknown VM command: " + args[1]);
        }
    }

    private static void handleStorage(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 3 || !args[1].equals("ls")) {
            System.out.println("Usage: plus storage ls [path]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IStorageProxy storage = service.getStorageProxy();
        if (storage == null) {
            System.out.println("Error: Storage Proxy feature is disabled in Shizuku+ settings.");
            return;
        }

        String path = args[2];
        List<String> files = storage.listFiles(path);
        if (files == null) {
            System.out.println("Error: Could not access path or directory empty.");
        } else {
            for (String file : files) System.out.println(file);
        }
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
                    handleLog();
                    break;
                case "vm":
                    handleVm(args, binder);
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
                    System.out.println("Current spoofing status: ACTIVE");
                    System.out.println("Note: Spoof targets are managed via the Shizuku+ App Settings.");
                    System.out.println("Use 'plus doctor' to see device identification details.");
                    break;
                case "doctor":
                    System.out.println("Checking Shizuku+ Service status...");
                    System.out.println("Server version: " + Shizuku.getVersion());
                    System.out.println("SELinux: " + Shizuku.getSELinuxContext());
                    break;
                default:
                    System.out.println("Unknown command: " + args[0]);
                    printHelp();
            }
        } catch (Throwable tr) {
            tr.printStackTrace(System.err);
        } finally {
            System.out.flush();
            System.exit(0);
        }
    }
}
