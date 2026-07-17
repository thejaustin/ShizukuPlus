package af.shizuku.common.compat;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Android 17 (SDK 37) reworked IPackageManager#getInstalledPackages: the hidden method now takes a
 * {@code long} flags argument and returns a new {@code PackageInfoList} type instead of the legacy
 * {@code ParceledListSlice}. rikka's {@code PackageManagerApis.getInstalledPackages} compiles the
 * old signature, so on Android 17 it throws {@code NoSuchMethodError}/casts fail and the manager
 * shows "no authorized apps" while the privileged server can't enumerate packages for its config.
 *
 * <p>This shim (ported from thedjchi's fork, whose Android 17 build is field-verified) resolves the
 * package list reflectively so it works across API levels and process contexts:
 * <ul>
 *   <li>In the <b>manager</b> process it first tries the context {@code PackageManager}
 *       ({@code getInstalledPackagesAsUser}); the manager holds {@code QUERY_ALL_PACKAGES}, so this
 *       returns the full list without any hidden API.</li>
 *   <li>In the <b>privileged server</b> (app_process, no application context) it falls back to
 *       rikka's {@code Services.packageManager} binder and reflectively picks the {@code long}-flag
 *       overload on API 33+, unwrapping either {@code ParceledListSlice} or the new
 *       {@code PackageInfoList} via {@code getList()}.</li>
 * </ul>
 * Lives in {@code af.shizuku.common.compat} (the one module the manager, server and server-shared
 * all depend on) so a single copy serves every call site.
 */
public final class InstalledPackagesCompat {

    private static final String TAG = "InstalledPackagesCompat";
    private static final int ANDROID_13 = 33;
    private static final String PARCELED_LIST_SLICE = "android.content.pm.ParceledListSlice";

    private InstalledPackagesCompat() {
    }

    public static List<PackageInfo> getInstalledPackagesNoThrow(long flags, int userId) {
        try {
            List<PackageInfo> packages = getInstalledPackages(flags, userId);
            return packages == null ? Collections.emptyList() : packages;
        } catch (Throwable e) {
            Log.w(TAG, "getInstalledPackages failed", e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<PackageInfo> getInstalledPackages(long flags, int userId) throws ReflectiveOperationException {
        try {
            Object packageManager = getContextPackageManager();
            Method method = packageManager.getClass().getMethod("getInstalledPackagesAsUser", int.class, int.class);
            Object result = invoke(method, packageManager, (int) flags, userId);
            return result == null ? Collections.emptyList() : (List<PackageInfo>) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            Log.d(TAG, "getInstalledPackagesAsUser failed, falling back to hidden API", e);
        }

        Object packageManager = getPackageManager();
        Method method;
        Object result;

        if (Build.VERSION.SDK_INT >= ANDROID_13) {
            method = packageManager.getClass().getMethod("getInstalledPackages", long.class, int.class);
            result = invoke(method, packageManager, flags, userId);
        } else {
            method = packageManager.getClass().getMethod("getInstalledPackages", int.class, int.class);
            result = invoke(method, packageManager, (int) flags, userId);
        }

        if (result == null) {
            return Collections.emptyList();
        }

        String resultClassName = result.getClass().getName();
        if (resultClassName.startsWith(PARCELED_LIST_SLICE) || resultClassName.contains("PackageInfoList")) {
            Object list = result.getClass().getMethod("getList").invoke(result);
            return list == null ? Collections.emptyList() : (List<PackageInfo>) list;
        }

        throw new IllegalStateException("Unsupported getInstalledPackages return type: " + resultClassName);
    }

    private static Object getPackageManager() throws ReflectiveOperationException {
        Class<?> servicesClass = Class.forName("rikka.hidden.compat.Services");
        var field = servicesClass.getDeclaredField("packageManager");
        field.setAccessible(true);
        Object service = field.get(null);
        return service.getClass().getMethod("get").invoke(service);
    }

    private static Object getContextPackageManager() throws ReflectiveOperationException {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        if (activityThread != null) {
            Object application = activityThreadClass.getMethod("getApplication").invoke(activityThread);
            if (application != null) {
                return application.getClass().getMethod("getPackageManager").invoke(application);
            }
        }

        activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Object systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
        return systemContext.getClass().getMethod("getPackageManager").invoke(systemContext);
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException) {
                throw (ReflectiveOperationException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
