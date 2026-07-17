package af.shizuku.common.compat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.Method;

import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;

/**
 * Android 17 (SDK 37) inserted a {@code deviceId} parameter (default {@link #DEVICE_ID_DEFAULT})
 * into a family of IPackageManager / IPermissionManager methods — getPackageInfo, getApplicationInfo,
 * checkPermission, grant/revokeRuntimePermission. rikka's {@code PackageManagerApis}/
 * {@code PermissionManagerApis} wrappers compile the pre-17 signatures, so on Android 17 they throw
 * {@code NoSuchMethodError} and every privileged package/permission operation the server performs
 * (config load, per-app permission grants during the confirmation flow, manager lookup) fails.
 *
 * <p>Each method here first calls the rikka wrapper (fast path for API &lt; 37), then on
 * {@code NoSuchMethodError} falls back to invoking the raw system binder reflectively, splicing in
 * {@code deviceId = 0}. {@link #findMethod}/{@link #invokeMethod} locate the correct overload and
 * position the extra argument regardless of the exact A17 signature shape.
 *
 * <p>Ported from thedjchi's field-verified Android 17 fork. Only the flags-cast to {@code int}
 * (to bind against the int-flag rikka overloads ShizukuPlus's 4.4.0 hidden-compat is known to
 * expose) and the package differ from the original. Runs only in the privileged server process,
 * where {@link ServiceManager} yields privileged binders; the manager never invokes it.
 */
public class Android17Compat {

    private static final String TAG = "ShizukuAndroid17Compat";
    private static final int DEVICE_ID_DEFAULT = 0;

    private static volatile Object sPackageManager;
    private static volatile Method sGetPackageInfoMethod;
    private static volatile Method sGetApplicationInfoMethod;

    private static volatile Object sPermissionManager;
    private static volatile Method sGrantRuntimePermissionMethod;
    private static volatile Method sRevokeRuntimePermissionMethod;
    private static volatile Method sCheckPermissionMethod;
    private static volatile Method sCheckPermissionUidMethod;

    private static synchronized Object getPackageManager() throws Exception {
        if (sPackageManager == null) {
            IBinder binder = ServiceManager.getService("package");
            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            sPackageManager = stubClass.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
        }
        return sPackageManager;
    }

    private static synchronized Object getPermissionManager() throws Exception {
        if (sPermissionManager == null) {
            IBinder binder = ServiceManager.getService("permissionmgr");
            Class<?> stubClass = Class.forName("android.permission.IPermissionManager$Stub");
            sPermissionManager = stubClass.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
        }
        return sPermissionManager;
    }

    public static PackageInfo getPackageInfo(String packageName, long flags, int userId) {
        try {
            return PackageManagerApis.getPackageInfoNoThrow(packageName, (int) flags, userId);
        } catch (NoSuchMethodError e) {
            try {
                Object pm = getPackageManager();
                if (sGetPackageInfoMethod == null) {
                    synchronized (Android17Compat.class) {
                        if (sGetPackageInfoMethod == null) {
                            sGetPackageInfoMethod = findMethod(pm, "getPackageInfo", String.class, long.class);
                        }
                    }
                }
                if (sGetPackageInfoMethod != null) {
                    return (PackageInfo) invokeMethod(pm, sGetPackageInfoMethod, packageName, flags, userId);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for getPackageInfo failed", ex);
            }
            return null;
        }
    }

    public static ApplicationInfo getApplicationInfo(String packageName, long flags, int userId) {
        try {
            return PackageManagerApis.getApplicationInfoNoThrow(packageName, (int) flags, userId);
        } catch (NoSuchMethodError e) {
            try {
                Object pm = getPackageManager();
                if (sGetApplicationInfoMethod == null) {
                    synchronized (Android17Compat.class) {
                        if (sGetApplicationInfoMethod == null) {
                            sGetApplicationInfoMethod = findMethod(pm, "getApplicationInfo", String.class, long.class);
                        }
                    }
                }
                if (sGetApplicationInfoMethod != null) {
                    return (ApplicationInfo) invokeMethod(pm, sGetApplicationInfoMethod, packageName, flags, userId);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for getApplicationInfo failed", ex);
            }
            return null;
        }
    }

    // Declares throws RemoteException (and propagates it) to stay a drop-in replacement for
    // PermissionManagerApis.checkPermission, whose contract ShizukuPlus's call sites already handle;
    // only the Android-17 NoSuchMethodError path is swallowed to DENIED.
    public static int checkPermission(String permissionName, String packageName, int userId) throws RemoteException {
        try {
            return PermissionManagerApis.checkPermission(permissionName, packageName, userId);
        } catch (NoSuchMethodError e) {
            try {
                Object pm = getPermissionManager();
                if (sCheckPermissionMethod == null) {
                    synchronized (Android17Compat.class) {
                        if (sCheckPermissionMethod == null) {
                            sCheckPermissionMethod = findMethod(pm, "checkPermission", String.class, String.class);
                        }
                    }
                }
                if (sCheckPermissionMethod != null) {
                    return (int) invokeMethod(pm, sCheckPermissionMethod, permissionName, packageName, userId);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for checkPermission(String, String, int) failed", ex);
            }
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
    }

    public static int checkPermission(String permissionName, int uid) throws RemoteException {
        try {
            return PermissionManagerApis.checkPermission(permissionName, uid);
        } catch (NoSuchMethodError e) {
            try {
                Object pm = getPermissionManager();
                if (sCheckPermissionUidMethod == null) {
                    synchronized (Android17Compat.class) {
                        if (sCheckPermissionUidMethod == null) {
                            sCheckPermissionUidMethod = findMethod(pm, "checkPermission", String.class, int.class);
                        }
                    }
                }
                if (sCheckPermissionUidMethod != null) {
                    Class<?>[] paramTypes = sCheckPermissionUidMethod.getParameterTypes();
                    if (paramTypes.length == 3 && paramTypes[1] == int.class && paramTypes[2] == int.class) {

                        return (int) sCheckPermissionUidMethod.invoke(pm, permissionName, DEVICE_ID_DEFAULT, uid);
                    }
                    return (int) sCheckPermissionUidMethod.invoke(pm, permissionName, uid);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for checkPermission(String, int) failed", ex);
            }
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
    }

    public static void grantRuntimePermission(String packageName, String permissionName, int userId) throws android.os.RemoteException {
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId);
        } catch (NoSuchMethodError e) {
            try {
                Object pm = getPermissionManager();
                if (sGrantRuntimePermissionMethod == null) {
                    synchronized (Android17Compat.class) {
                        if (sGrantRuntimePermissionMethod == null) {
                            sGrantRuntimePermissionMethod = findMethod(pm, "grantRuntimePermission", String.class, String.class);
                        }
                    }
                }
                if (sGrantRuntimePermissionMethod != null) {
                    invokeMethod(pm, sGrantRuntimePermissionMethod, packageName, permissionName, userId);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for grantRuntimePermission failed", ex);
            }
        }
    }

    public static void revokeRuntimePermission(String packageName, String permissionName, int userId) throws android.os.RemoteException {
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId);
        } catch (NoSuchMethodError e) {
            try {
                Object pm = getPermissionManager();
                if (sRevokeRuntimePermissionMethod == null) {
                    synchronized (Android17Compat.class) {
                        if (sRevokeRuntimePermissionMethod == null) {
                            sRevokeRuntimePermissionMethod = findMethod(pm, "revokeRuntimePermission", String.class, String.class);
                        }
                    }
                }
                if (sRevokeRuntimePermissionMethod != null) {
                    Class<?>[] paramTypes = sRevokeRuntimePermissionMethod.getParameterTypes();
                    if (paramTypes.length == 5 && paramTypes[4] == String.class) {
                        sRevokeRuntimePermissionMethod.invoke(pm, packageName, permissionName, DEVICE_ID_DEFAULT, userId, "shizuku");
                    } else {
                        invokeMethod(pm, sRevokeRuntimePermissionMethod, packageName, permissionName, userId);
                    }
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for revokeRuntimePermission failed", ex);
            }
        }
    }

    private static Method findMethod(Object obj, String name, Class<?>... prefixTypes) {
        Method bestMethod = null;
        for (Method method : obj.getClass().getMethods()) {
            if (name.equals(method.getName())) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length >= prefixTypes.length) {
                    boolean match = true;
                    for (int i = 0; i < prefixTypes.length; i++) {
                        if (paramTypes[i] != prefixTypes[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        if (bestMethod == null || paramTypes.length > bestMethod.getParameterTypes().length) {
                            bestMethod = method;
                        }
                    }
                }
            }
        }
        return bestMethod;
    }

    private static Object invokeMethod(Object obj, Method method, Object... prefixArgs) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        int prefixLen = prefixArgs.length - 1;
        int userIdIdx = prefixArgs.length - 1;
        Object userId = prefixArgs[userIdIdx];

        if (paramTypes.length == prefixArgs.length + 1) {
            System.arraycopy(prefixArgs, 0, args, 0, prefixLen);
            args[prefixLen] = DEVICE_ID_DEFAULT;
            args[prefixLen + 1] = userId;
            for (int i = prefixLen + 2; i < paramTypes.length; i++) {
                if (paramTypes[i] == int.class) args[i] = 0;
                else if (paramTypes[i] == String.class) args[i] = null;
            }
        } else {
            System.arraycopy(prefixArgs, 0, args, 0, Math.min(prefixArgs.length, paramTypes.length));
            for (int i = prefixArgs.length; i < paramTypes.length; i++) {
                if (paramTypes[i] == int.class) args[i] = userId;
            }
        }
        return method.invoke(obj, args);
    }
}
