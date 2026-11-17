package af.shizuku.common.util;

import android.content.pm.ApplicationInfo;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Packages that freezing, suspending or uninstalling can turn into a boot loop: the system UI,
 * settings, telephony and the permission/package plumbing, plus any app the system keeps
 * persistent. With one of these disabled the device restarts at the lock screen until it's
 * re-enabled from recovery or a factory reset (#686). Shared by the server's Plus APIs and the
 * manager's Device Owner freeze, so both refuse the same list.
 */
public final class BootCriticalPackages {

    private static final Set<String> NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.keychain",
            "com.android.se",
            "com.android.server.telecom",
            "com.android.inputdevices",
            "com.android.location.fused"
    )));

    private BootCriticalPackages() {
    }

    /** [info] is the package's ApplicationInfo when the caller has it, for the persistent flag. */
    public static boolean isBootCritical(String packageName, @Nullable ApplicationInfo info) {
        if (NAMES.contains(packageName)) return true;
        return info != null && (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
    }
}
