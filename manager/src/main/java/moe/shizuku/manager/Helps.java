package moe.shizuku.manager;

import moe.shizuku.manager.utils.MultiLocaleEntity;

public class Helps {

    public static final MultiLocaleEntity ADB = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_ANDROID11 = new MultiLocaleEntity();
    public static final MultiLocaleEntity APPS = new MultiLocaleEntity();
    public static final MultiLocaleEntity HOME = new MultiLocaleEntity();
    public static final MultiLocaleEntity DOWNLOAD = new MultiLocaleEntity();
    public static final MultiLocaleEntity SUI = new MultiLocaleEntity();
    public static final MultiLocaleEntity RISH = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_PERMISSION = new MultiLocaleEntity();

    static {
        ADB.put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup");
        ADB.put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup");
        ADB.put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup");

        ADB_ANDROID11.put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup");
        ADB_ANDROID11.put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup");
        ADB_ANDROID11.put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup");

        APPS.put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Supported-apps");
        APPS.put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Supported-apps");
        APPS.put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Supported-apps");

        HOME.put("en", "https://github.com/thejaustin/ShizukuPlus/tree/master/README.md#developer-guide");

        DOWNLOAD.put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/releases");
        DOWNLOAD.put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/releases");
        DOWNLOAD.put("en", "https://github.com/thejaustin/ShizukuPlus/releases");

        ADB_PERMISSION.put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup#troubleshooting");
        ADB_PERMISSION.put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup#troubleshooting");
        ADB_PERMISSION.put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Setup#troubleshooting");

        SUI.put("en", "https://github.com/RikkaApps/Sui");

        RISH.put("en", "https://github.com/thejaustin/ShizukuPlus-API/tree/master/rish");
    }
}
