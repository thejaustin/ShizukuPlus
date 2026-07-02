package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "af.shizuku.plus.permission.API_V23";
    public static final String PERMISSION_LEGACY = "af.shizuku.manager.permission.API_V23";
    public static final String PERMISSION_ORIGINAL = "moe.shizuku.manager.permission.API_V23";
    public static final String MANAGER_APPLICATION_ID = "af.shizuku.plus.api";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
    public static final int BINDER_TRANSACTION_isCustomApiEnabled = 10002;
    public static final int BINDER_TRANSACTION_getDhizukuBinder = 10003;
    // Direct getter for the running server's patch version. The patch version is otherwise only
    // delivered via the oneway bindApplication callback, which the manager's own client doesn't
    // reliably receive; this lets it (and any client) read it directly, like getVersion()/getUid().
    public static final int BINDER_TRANSACTION_getServerPatchVersion = 10004;
}
