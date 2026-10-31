package rikka.shizuku.shell;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.Os;
import android.text.TextUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dalvik.system.BaseDexClassLoader;
import rikka.hidden.compat.PackageManagerApis;
import stub.dalvik.system.VMRuntimeHidden;

public class ShizukuShellLoader {

    private static final Logger LOGGER = Logger.getLogger("ShizukuShellLoader");

    private static final String PLUS_APPLICATION_ID = "af.shizuku.plus.api";
    private static final String DROPIN_APPLICATION_ID = "moe.shizuku.privileged.api";

    private static String[] args;
    private static String callingPackage;
    private static Handler handler;
    private static Runnable timeoutCallback;
    private static Runnable waitingNotice;

    private static final Binder receiverBinder = new Binder() {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                IBinder binder = data.readStrongBinder();

                String sourceDir = data.readString();
                if (binder != null) {
                    handler.post(() -> onBinderReceived(binder, sourceDir));
                } else {
                    LOGGER.severe("Server is not running");
                    System.exit(1);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // This process is spawned fresh via app_process by the "rish"/"plus" shell scripts, in the
    // calling app's own UID - it has no way to see the server's runtime-resolved
    // ServerConstants.MANAGER_APPLICATION_ID, so it must independently work out which flavor is
    // actually installed. Same class of bug as #371's ServiceStarter.kt fix: a hardcoded
    // "af.shizuku.plus.api" here means REQUEST_BINDER never reaches anyone on a Drop-In-only
    // install, since Intent.setPackage() silently drops the broadcast when that package isn't
    // present (rish then just times out after 15s with a misleading "connection may be blocked"
    // message).
    private static String resolveManagerPackageName() {
        if (PackageManagerApis.getApplicationInfoNoThrow(PLUS_APPLICATION_ID, 0, 0) != null) {
            return PLUS_APPLICATION_ID;
        }
        if (PackageManagerApis.getApplicationInfoNoThrow(DROPIN_APPLICATION_ID, 0, 0) != null) {
            return DROPIN_APPLICATION_ID;
        }
        return PLUS_APPLICATION_ID;
    }

    private static void requestForBinder() throws RemoteException {
        Bundle data = new Bundle();
        data.putBinder("binder", receiverBinder);

        String authToken = System.getenv("SHIZUKU_TOKEN");

        Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                .setPackage(resolveManagerPackageName())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("data", data);

        if (!TextUtils.isEmpty(authToken)) {
            intent.putExtra("auth", authToken);
        }

        if (!TextUtils.isEmpty(callingPackage)) {
            intent.putExtra("callingPackage", callingPackage);
        }
        // Always include the UID. Receivers use this when PackageManager.getApplicationInfo()
        // is unavailable (e.g. classic rish_shizuku.dex omits callingPackage, or PM lookup
        // fails). This UID is authoritative: this process runs as the caller's UID.
        intent.putExtra("callingUid", Os.getuid());

        IBinder amBinder = ServiceManager.getService("activity");
        IActivityManager am;
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder);
        } else {
            am = ActivityManagerNative.asInterface(amBinder);
        }

        try {
            am.broadcastIntent(null, intent, null, null, 0, null, null,
                    null, -1, null, true, false, 0);
        } catch (Throwable e) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
                    || !Objects.equals(e.getMessage(), "Calling application did not provide package name")) {
                throw e;
            }

            LOGGER.warning("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity");

            Intent baseActivityIntent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("data", data);

            if (!TextUtils.isEmpty(authToken)) {
                baseActivityIntent.putExtra("auth", authToken);
            }

            Intent activityIntent = Intent.createChooser(
                    baseActivityIntent,
                    "Request binder from Shizuku"
            );

            am.startActivityAsUser(null, callingPackage, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000);
        }
    }

    private static void onBinderReceived(IBinder binder, String sourceDir) {
        if (waitingNotice != null) {
            handler.removeCallbacks(waitingNotice);
        }
        if (timeoutCallback != null) {
            handler.removeCallbacks(timeoutCallback);
        }

        var base = sourceDir.substring(0, sourceDir.lastIndexOf('/'));
        String librarySearchPath = base + "/lib/" + VMRuntimeHidden.getRuntime().vmInstructionSet();
        String systemLibrarySearchPath = System.getProperty("java.library.path");
        if (!TextUtils.isEmpty(systemLibrarySearchPath)) {
            librarySearchPath += File.pathSeparatorChar + systemLibrarySearchPath;
        }

        try {
            var classLoader = new BaseDexClassLoader(sourceDir, null, librarySearchPath, ClassLoader.getSystemClassLoader());
            String className = "plus".equals(System.getProperty("shizuku.cmd")) 
                ? "af.shizuku.manager.shell.PlusShell" 
                : "af.shizuku.manager.shell.Shell";
            Class<?> cls = classLoader.loadClass(className);
            cls.getDeclaredMethod("main", String[].class, String.class, IBinder.class, Handler.class)
                    .invoke(null, args, callingPackage, binder, handler);
        } catch (ClassNotFoundException tr) {
            abort("Class not found. Make sure you have Shizuku v12.0.0 or above installed.: " + tr, tr);
        } catch (Throwable tr) {
            // invoke() wraps the target method's own exceptions in InvocationTargetException,
            // whose toString()/message carry nothing useful - unwrap to the real cause.
            Throwable cause = (tr instanceof InvocationTargetException && tr.getCause() != null) ? tr.getCause() : tr;
            abort("Failed to load shell class: " + cause, cause);
        }
    }

    public static void main(String[] args) {
        ShizukuShellLoader.args = args;

        String packageName = System.getenv("RISH_APPLICATION_ID");
        var pkg = PackageManagerApis.getPackagesForUidNoThrow(Os.getuid());
        if (TextUtils.isEmpty(packageName) || "PKG".equals(packageName)) {
            if (pkg != null && !pkg.isEmpty()) {
                if (pkg.contains("com.termux")) {
                    packageName = "com.termux";
                } else {
                    packageName = pkg.get(0);
                }
            } else {
                abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)");
                System.exit(1);
            }
        }

        ShizukuShellLoader.callingPackage = packageName;

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        handler = new Handler(Looper.getMainLooper());

        try {
            requestForBinder();
        } catch (Throwable tr) {
            abort("Failed to request binder: " + tr, tr);
        }

        // The 90s failure-path budget below (see the comment on the postDelayed call) covers a
        // genuine wait for a human to notice and tap the consent notification, but prints nothing
        // while it's waiting - which reads identically to a hang for a setup that's actually
        // broken (dialog never displayed, wrong flavor installed, etc). One line up front at least
        // tells the caller what it's blocked on. Only when the binder is slow to arrive: an app
        // that is already authorised gets it within a few milliseconds, and printing on every
        // command clutters scripts' stderr.
        waitingNotice = () -> System.err.println("Waiting for Shizuku authorization... check your notifications.");
        handler.postDelayed(waitingNotice, 1500);

        timeoutCallback = () -> abort(
                String.format(
                        "Request timeout. The connection between the current app (%1$s) and Shizuku app may be blocked by your system. " +
                                "Please disable all battery optimization features for both current app (%1$s) and Shizuku app.",
                        callingPackage)
        );
        // 15s was sized for the consent dialog launching directly (see the commit that introduced
        // this value). Since then, the dialog is routed through a notification the user has to
        // notice, open, and tap first to dodge Android's background-activity-launch restrictions
        // (#377) - that extra human step can easily eat the whole budget on its own, so a fully
        // successful, on-time consent grant can still race this timer and lose (#377, still timing
        // out after the notification/consent flow itself works). 90s gives real margin for that
        // flow; onBinderReceived() above cancels this the moment the binder actually arrives, so a
        // fast path isn't slowed down, only the failure path waits longer before giving up.
        handler.postDelayed(timeoutCallback, 90000);

        Looper.loop();
        System.exit(0);
    }

    private static void abort(String message) {
        System.err.println(message);
        LOGGER.severe(message);
        System.exit(1);
    }

    private static void abort(String message, Throwable tr) {
        System.err.println(message);
        tr.printStackTrace();
        LOGGER.log(Level.SEVERE, message, tr);
        System.exit(1);
    }
}
