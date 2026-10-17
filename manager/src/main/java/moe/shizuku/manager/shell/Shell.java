package moe.shizuku.manager.shell;

import android.os.Handler;
import android.os.IBinder;

/**
 * Legacy package-name compatibility shim for third-party {@code rish} loaders.
 *
 * <p>Upstream Shizuku ships the {@code rish} entry point as
 * {@code moe.shizuku.manager.shell.Shell}. Some third-party apps bundle their own
 * prebuilt rish loader dex that hard-codes that class name and, after obtaining the
 * Shizuku binder, loads the class out of the installed Shizuku APK with a
 * {@link dalvik.system.BaseDexClassLoader} and reflectively invokes
 * {@code static main(String[], String, IBinder, Handler)}. MT Manager 2.14.5 is one
 * such app: its {@code assets/rish_dex} loader ({@code rikka.shizuku.shell.ShizukuShellLoader})
 * only ever looks for {@code moe.shizuku.manager.shell.Shell}.
 *
 * <p>Shizuku+ keeps its implementation in {@code af.shizuku.manager.shell} for the
 * shizukuplus flavor, so a legacy loader fails with
 * {@code ClassNotFoundException: moe.shizuku.manager.shell.Shell} and the generic
 * "Make sure you have Shizuku v12.0.0 or above installed" abort, even though the
 * server is running and authorization succeeds.
 *
 * <p>This class exists only to satisfy that hard-coded lookup; it carries no logic of
 * its own and simply forwards to {@link af.shizuku.manager.shell.Shell#main(String[], String, IBinder, Handler)}.
 * It is kept from R8 renaming/removal by a dedicated rule in
 * {@code manager/proguard-rules.pro} — keep that rule in sync with this method signature.
 */
public final class Shell {

    private Shell() {
    }

    /**
     * Entry point expected by legacy rish loaders. See the class documentation.
     *
     * @param args        arguments as passed by the rish launcher scripts
     * @param packageName the calling application's package name
     * @param binder      the Shizuku server binder obtained through the legacy
     *                    {@code rikka.shizuku.intent.action.REQUEST_BINDER} handshake
     * @param handler     a handler bound to the loader process's main looper
     */
    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        af.shizuku.manager.shell.Shell.main(args, packageName, binder, handler);
    }
}
