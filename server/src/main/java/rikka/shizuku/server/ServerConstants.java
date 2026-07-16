package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "af.shizuku.plus.permission.API_V23";
    public static final String PERMISSION_LEGACY = "af.shizuku.manager.permission.API_V23";
    public static final String PERMISSION_ORIGINAL = "moe.shizuku.manager.permission.API_V23";

    // Both the Plus and Drop-In flavors share this same server binary but ship under different
    // application ids. This defaults to the Plus id; ShizukuService.getManagerApplicationInfo()
    // corrects it at startup to whichever flavor is actually installed, since a Drop-In-only
    // install would otherwise never find "the manager app" and exit(MANAGER_APP_NOT_FOUND)
    // immediately. Not final so that correction can take effect everywhere this is read.
    public static String MANAGER_APPLICATION_ID = "af.shizuku.plus.api";
    public static final String PLUS_APPLICATION_ID = "af.shizuku.plus.api";
    public static final String DROPIN_APPLICATION_ID = "moe.shizuku.privileged.api";

    // Computed on demand (rather than a constant) because it derives from MANAGER_APPLICATION_ID,
    // which can be corrected after this class is first loaded - a constant would freeze in the
    // default flavor's action string.
    public static String getRequestPermissionAction() {
        return MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";
    }

    public static final int BINDER_TRANSACTION_getApplications = 10001;
    public static final int BINDER_TRANSACTION_isCustomApiEnabled = 10002;
    public static final int BINDER_TRANSACTION_getDhizukuBinder = 10003;
    // Direct getter for the running server's patch version. The patch version is otherwise only
    // delivered via the oneway bindApplication callback, which the manager's own client doesn't
    // reliably receive; this lets it (and any client) read it directly, like getVersion()/getUid().
    public static final int BINDER_TRANSACTION_getServerPatchVersion = 10004;
}
