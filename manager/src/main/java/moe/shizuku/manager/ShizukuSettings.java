package moe.shizuku.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import java.lang.annotation.Retention;
import java.util.Locale;
import moe.shizuku.manager.service.WatchdogService;
import moe.shizuku.manager.receiver.BootCompleteReceiver;
import moe.shizuku.manager.utils.Token;
import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;
import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static class Keys {
        public static final String KEY_START_ON_BOOT = "start_on_boot";
        public static final String KEY_WATCHDOG = "watchdog";
        public static final String KEY_TCP_MODE = "tcp_mode";
        public static final String KEY_TCP_PORT = "tcp_port";
        public static final String KEY_AUTO_DISABLE_USB_DEBUGGING = "auto_disable_usb_debugging";
        public static final String KEY_LANGUAGE = "language";
        public static final String KEY_TRANSLATION = "translation";
        public static final String KEY_TRANSLATION_CONTRIBUTORS = "translation_contributors";
        public static final String KEY_LIGHT_THEME = "light_theme";
        public static final String KEY_NIGHT_MODE = "night_mode";
        public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";
        public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";
        public static final String KEY_HELP = "help";
        public static final String KEY_REPORT_BUG = "report_bug";
        public static final String KEY_LEGACY_PAIRING = "legacy_pairing";
        public static final String KEY_CATEGORY_ADVANCED = "category_advanced";

        // Home screen visibility (Shizuku+ additions)
        public static final String KEY_SHOW_TERMINAL_HOME = "show_terminal_home";
        public static final String KEY_SHOW_AUTOMATION_HOME = "show_automation_home";
        public static final String KEY_SHOW_LEARN_MORE_HOME = "show_learn_more_home";
        public static final String KEY_SHOW_ACTIVITY_LOG_HOME = "show_activity_log_home";
        public static final String KEY_ENABLE_ACTIVITY_LOG = "enable_activity_log";
        public static final String KEY_LAST_DB_UPDATE = "last_db_update";
        public static final String KEY_REMOTE_DB_JSON = "remote_db_json";

        // Dhizuku & API (Shizuku+ additions)
        public static final String KEY_DHIZUKU_MODE = "dhizuku_mode";
        public static final String KEY_CUSTOM_API_ENABLED = "custom_api_enabled";
        public static final String KEY_SHELL_INTERCEPTOR_ENABLED = "shell_interceptor_enabled";
        public static final String KEY_AVF_MANAGER_ENABLED = "avf_manager_enabled";
        public static final String KEY_STORAGE_PROXY_ENABLED = "storage_proxy_enabled";
        public static final String KEY_CONTINUITY_BRIDGE_ENABLED = "continuity_bridge_enabled";
        public static final String KEY_AI_CORE_PLUS_ENABLED = "ai_core_plus_enabled";
        public static final String KEY_WINDOW_MANAGER_PLUS_ENABLED = "window_manager_plus_enabled";

        // Legacy Compatibility (Shizuku+ additions)
        public static final String KEY_ADB_PROXY_ENABLED = "adb_proxy_enabled";
        public static final String KEY_FAKE_SU_ENABLED = "fake_su_enabled";

        // Long-press action toggles (Shizuku+ additions)
        public static final String KEY_LP_OPEN_APP = "lp_open_app";
        public static final String KEY_LP_APP_INFO = "lp_app_info";
        public static final String KEY_LP_TOGGLE_PERMISSION = "lp_toggle_permission";
        public static final String KEY_LP_HIDE_FROM_LIST = "lp_hide_from_list";

        // Swipe action preferences (Shizuku+ additions)
        public static final String KEY_SWIPE_RIGHT_ACTION = "swipe_right_action";
        public static final String KEY_SWIPE_LEFT_ACTION = "swipe_left_action";
    }

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    @IntDef({
        LaunchMethod.UNKNOWN,
        LaunchMethod.ROOT,
        LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt("mode", method).apply();
    }

    public static boolean getAutoDisableUsbDebugging() {
        return getPreferences().getBoolean(Keys.KEY_AUTO_DISABLE_USB_DEBUGGING, false);
    }

    public static String getAuthToken() {
        String authToken = getPreferences().getString("auth_token", null);
        if (authToken == null || authToken.isEmpty()) {
            authToken = generateAuthToken();
        }
        return authToken;
    }

    public static String generateAuthToken() {
        String token = Token.generateToken();
        getPreferences().edit().putString("auth_token", token).apply();
        return token;
    }

    public static boolean getStartOnBoot(Context context) {
        ComponentName bootCompleteReceiver = new ComponentName(context.getPackageName(), BootCompleteReceiver.class.getName());
        int state = context.getPackageManager().getComponentEnabledSetting(bootCompleteReceiver);
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setStartOnBoot(Context context, boolean enable) {
        ComponentName bootCompleteReceiver = new ComponentName(context.getPackageName(), BootCompleteReceiver.class.getName());
        context.getPackageManager().setComponentEnabledSetting(
            bootCompleteReceiver,
            enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
        getPreferences().edit().putBoolean(Keys.KEY_START_ON_BOOT, enable).apply();
    }
    
    public static boolean getWatchdog() {
        return getPreferences().getBoolean(Keys.KEY_WATCHDOG, false);
    }

    public static boolean isWatchdogRunning() {
        return WatchdogService.isRunning();
    }

    public static void setWatchdog(Context context, boolean enable) {
        if (enable) {
            WatchdogService.start(context);
        } else {
            WatchdogService.stop(context);
        }
        getPreferences().edit().putBoolean(Keys.KEY_WATCHDOG, enable).apply();
        return;
    }

    public static boolean getTcpMode() {
        return getPreferences().getBoolean(Keys.KEY_TCP_MODE, true);
    }

    public static void setTcpMode(boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_TCP_MODE, enable).apply();
    }

    public static int getTcpPort() {
        try {
            return Integer.parseInt(getPreferences().getString(Keys.KEY_TCP_PORT, "5555"));
        } catch (NumberFormatException e) {
            return 5555;
        }
    }

    public static void setTcpPort(@Nullable Integer port) {
        if (port != null) {
            getPreferences().edit().putString(Keys.KEY_TCP_PORT, Integer.toString(port)).apply();
        } else {
            getPreferences().edit().remove(Keys.KEY_TCP_PORT).apply();
        }
        
    }

    public static boolean getLegacyPairing() {
        return getPreferences().getBoolean(Keys.KEY_LEGACY_PAIRING, false);
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        int defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (EnvironmentUtils.isWatch()) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES;
        }
        return getPreferences().getInt(Keys.KEY_NIGHT_MODE, defValue);
    }

    public static boolean getLongPressOpenApp() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_LP_OPEN_APP, true);
    }

    public static boolean getLongPressAppInfo() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_LP_APP_INFO, true);
    }

    public static boolean getLongPressTogglePermission() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_LP_TOGGLE_PERMISSION, true);
    }

    public static boolean getLongPressHideFromList() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_LP_HIDE_FROM_LIST, true);
    }

    public static String getSwipeRightAction() {
        SharedPreferences p = getPreferences();
        return p == null ? "open_app" : p.getString(Keys.KEY_SWIPE_RIGHT_ACTION, "open_app");
    }

    public static String getSwipeLeftAction() {
        SharedPreferences p = getPreferences();
        return p == null ? "app_info" : p.getString(Keys.KEY_SWIPE_LEFT_ACTION, "app_info");
    }

    public static boolean showTerminalHome() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_SHOW_TERMINAL_HOME, true);
    }

    public static boolean showAutomationHome() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_SHOW_AUTOMATION_HOME, true);
    }

    public static boolean showLearnMoreHome() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_SHOW_LEARN_MORE_HOME, true);
    }

    public static boolean showActivityLogHome() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_SHOW_ACTIVITY_LOG_HOME, true);
    }

    public static boolean isActivityLogEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_ENABLE_ACTIVITY_LOG, true);
    }

    public static String getRemoteDbJson() {
        SharedPreferences p = getPreferences();
        return p == null ? null : p.getString(Keys.KEY_REMOTE_DB_JSON, null);
    }

    public static void setRemoteDbJson(String json) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putString(Keys.KEY_REMOTE_DB_JSON, json).apply();
    }

    public static long getLastDbUpdate() {
        SharedPreferences p = getPreferences();
        return p == null ? 0 : p.getLong(Keys.KEY_LAST_DB_UPDATE, 0);
    }

    public static void setLastDbUpdate(long time) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putLong(Keys.KEY_LAST_DB_UPDATE, time).apply();
    }

    public static boolean isAppEnhancementEnabled(String packageName, String enhancementKey) {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean("enh_" + packageName + "_" + enhancementKey, false);
    }

    public static void setAppEnhancementEnabled(String packageName, String enhancementKey, boolean enabled) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean("enh_" + packageName + "_" + enhancementKey, enabled).apply();
    }

    public static boolean hasSeenOnboarding() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean("onboarding_seen", false);
    }

    public static void setOnboardingSeen() {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean("onboarding_seen", true).apply();
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString(Keys.KEY_LANGUAGE, null);
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(tag);
    }

    public static boolean isDhizukuModeEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_DHIZUKU_MODE, false);
    }

    public static void setDhizukuModeEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_DHIZUKU_MODE, enable).apply();
    }

    public static boolean isCustomApiEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_CUSTOM_API_ENABLED, true);
    }

    public static void setCustomApiEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_CUSTOM_API_ENABLED, enable).apply();
    }

    public static boolean isShellInterceptorEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_SHELL_INTERCEPTOR_ENABLED, true);
    }

    public static boolean isAvfManagerEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_AVF_MANAGER_ENABLED, true);
    }

    public static boolean isStorageProxyEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_STORAGE_PROXY_ENABLED, true);
    }

    public static boolean isContinuityBridgeEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_CONTINUITY_BRIDGE_ENABLED, true);
    }

    public static boolean isAICorePlusEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_AI_CORE_PLUS_ENABLED, true);
    }

    public static boolean isWindowManagerPlusEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_WINDOW_MANAGER_PLUS_ENABLED, true);
    }

    public static boolean isAdbProxyEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ADB_PROXY_ENABLED, false);
    }

    public static boolean isFakeSuEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_FAKE_SU_ENABLED, false);
    }
}
