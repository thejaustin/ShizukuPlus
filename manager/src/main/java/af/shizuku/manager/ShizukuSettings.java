package af.shizuku.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import timber.log.Timber;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import java.lang.annotation.Retention;
import java.util.Locale;
import af.shizuku.manager.service.WatchdogService;
import af.shizuku.manager.receiver.BootCompleteReceiver;
import af.shizuku.manager.utils.Token;
import af.shizuku.manager.utils.EmptySharedPreferencesImpl;
import af.shizuku.manager.utils.EnvironmentUtils;
import af.shizuku.manager.utils.InputValidationUtils;
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

        // Home screen visibility (Shizuku+ additions)
        public static final String KEY_SHOW_TERMINAL_HOME = "show_terminal_home";
        public static final String KEY_SHOW_AUTOMATION_HOME = "show_automation_home";
        public static final String KEY_SHOW_LEARN_MORE_HOME = "show_learn_more_home";
        public static final String KEY_SHOW_ACTIVITY_LOG_HOME = "show_activity_log_home";
        public static final String KEY_ENABLE_ACTIVITY_LOG = "enable_activity_log";
        public static final String KEY_ACTIVITY_LOG_RETENTION = "activity_log_retention";
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
        public static final String KEY_OVERLAY_MANAGER_PLUS_ENABLED = "overlay_manager_plus_enabled";
        public static final String KEY_NETWORK_GOVERNOR_PLUS_ENABLED = "network_governor_plus_enabled";
        public static final String KEY_ACTIVITY_MANAGER_PLUS_ENABLED = "activity_manager_plus_enabled";
        public static final String KEY_EXPERIMENTAL_ROOT_COMPAT = "experimental_root_compat";
        public static final String KEY_SPOOF_DEVICE_ENABLED = "spoof_device_enabled";
        public static final String KEY_SPOOF_TARGET = "spoof_target";
        public static final String KEY_VECTOR_ENABLED = "vector_enabled";
        public static final String KEY_HIDE_DISABLED_PLUS_FEATURES = "hide_disabled_plus_features";

        // Home card extras (Shizuku+ additions)
        public static final String KEY_SHOW_START_ADB_HOME = "show_start_adb_home";
        public static final String KEY_CARD_ORDER = "home_card_order";
        public static final String KEY_HIDDEN_HOME_CARDS = "hidden_home_cards";

        // Legacy Compatibility (Shizuku+ additions)
        public static final String KEY_ADB_PROXY_ENABLED = "adb_proxy_enabled";
        public static final String KEY_ON_DEVICE_ADB_TCP = "on_device_adb_tcp";
        public static final String KEY_FORCE_START_WADB = "force_start_wadb";
        public static final String KEY_SU_BRIDGE_ENABLED = "su_bridge_enabled";
        public static final String KEY_ROOT_ADAWAY_BRIDGE_ENABLED = "root_adaway_bridge_enabled";
        public static final String KEY_ROOT_MAGISK_MOCKING_ENABLED = "root_magisk_mocking_enabled";
        public static final String KEY_ROOT_AUTO_GRANT_ENABLED = "root_auto_grant_enabled";
        public static final String KEY_ROOT_FILE_INTERCEPTOR_ENABLED = "root_file_interceptor_enabled";
        public static final String KEY_ROOT_BUSYBOX_MOCKING_ENABLED = "root_busybox_mocking_enabled";
        public static final String KEY_EXPORT_DIR_URI = "export_dir_uri";

        // Long-press action toggles (Shizuku+ additions)
        public static final String KEY_LP_OPEN_APP = "lp_open_app";
        public static final String KEY_LP_APP_INFO = "lp_app_info";
        public static final String KEY_LP_TOGGLE_PERMISSION = "lp_toggle_permission";
        public static final String KEY_LP_HIDE_FROM_LIST = "lp_hide_from_list";

        // Swipe action preferences (Shizuku+ additions)
        public static final String KEY_SWIPE_RIGHT_ACTION = "swipe_right_action";
        public static final String KEY_SWIPE_LEFT_ACTION = "swipe_left_action";

        public static final String KEY_EXPRESSIVE_SHAPES = "expressive_shapes";
        public static final String KEY_EXPRESSIVE_ANIMATIONS = "expressive_animations";
        public static final String KEY_ICON_STYLE = "icon_style";
        public static final String KEY_SHAPE_STYLE = "shape_style";
        public static final String KEY_ANIMATION_INTENSITY = "animation_intensity";

        // Migration (Shizuku+ additions)
        public static final String KEY_MIGRATION_OFFERED = "migration_offered";

        // Auto Update (Shizuku+ additions)
        public static final String KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled";
        public static final String KEY_AUTO_INSTALL_ENABLED = "auto_install_enabled";
        public static final String KEY_LAST_UPDATE_CHECK = "last_update_check_time";
        public static final String KEY_LAST_CHECK_FAILED = "last_check_failed";
        public static final String KEY_UPDATE_CHANNEL = "update_channel"; // "stable" or "dev"
    }

    private static SharedPreferences sPreferences;

    public static boolean isExpressiveShapesEnabled() {
        return getPreferences().getBoolean(Keys.KEY_EXPRESSIVE_SHAPES, true);
    }

    public static boolean isExpressiveAnimationsEnabled() {
        return getPreferences().getBoolean(Keys.KEY_EXPRESSIVE_ANIMATIONS, true);
    }

    public static String getIconStyle() {
        return getPreferences().getString(Keys.KEY_ICON_STYLE, "standard");
    }

    public static String getShapeStyle() {
        return getPreferences().getString(Keys.KEY_SHAPE_STYLE, "zen");
    }

    public static int getAnimationIntensity() {
        try {
            return Integer.parseInt(getPreferences().getString(Keys.KEY_ANIMATION_INTENSITY, "2"));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

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

    public static int getActivityLogRetention() {
        SharedPreferences p = getPreferences();
        return p == null ? 100 : p.getInt(Keys.KEY_ACTIVITY_LOG_RETENTION, 100);
    }

    public static void setActivityLogRetention(int retention) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putInt(Keys.KEY_ACTIVITY_LOG_RETENTION, retention).apply();
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

    public static boolean hasMigrationBeenOffered() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_MIGRATION_OFFERED, false);
    }

    public static void setMigrationOffered() {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_MIGRATION_OFFERED, true).apply();
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

    public static boolean isOverlayManagerPlusEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_OVERLAY_MANAGER_PLUS_ENABLED, true);
    }

    public static boolean isNetworkGovernorPlusEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_NETWORK_GOVERNOR_PLUS_ENABLED, true);
    }

    public static boolean isActivityManagerPlusEnabled() {
        SharedPreferences p = getPreferences();
        return p == null || p.getBoolean(Keys.KEY_ACTIVITY_MANAGER_PLUS_ENABLED, true);
    }

    public static boolean isAdbProxyEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ADB_PROXY_ENABLED, false);
    }

    public static void setAdbProxyEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_ADB_PROXY_ENABLED, enable).apply();
    }

    public static boolean isOnDeviceAdbTcpEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ON_DEVICE_ADB_TCP, false);
    }

    public static void setOnDeviceAdbTcpEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_ON_DEVICE_ADB_TCP, enable).apply();
    }

    public static boolean isForceStartWadbEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_FORCE_START_WADB, false);
    }

    public static void setForceStartWadbEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_FORCE_START_WADB, enable).apply();
    }

    public static boolean isVectorEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_VECTOR_ENABLED, false);
    }

    public static void setVectorEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_VECTOR_ENABLED, enable).apply();
    }

    public static boolean isExperimentalRootCompatEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_EXPERIMENTAL_ROOT_COMPAT, false);
    }

    public static void setExperimentalRootCompatEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_EXPERIMENTAL_ROOT_COMPAT, enable).apply();
    }

    public static boolean isSpoofDeviceEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_SPOOF_DEVICE_ENABLED, false);
    }

    public static void setSpoofDeviceEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_SPOOF_DEVICE_ENABLED, enable).apply();
    }

    public static String getSpoofTarget() {
        SharedPreferences p = getPreferences();
        return p != null ? p.getString(Keys.KEY_SPOOF_TARGET, "pixel_8_pro") : "pixel_8_pro";
    }

    /**
     * Sets the spoof target device.
     *
     * @param target the spoof target to set
     * @throws IllegalArgumentException if the target is not in the whitelist of valid devices
     */
    public static void setSpoofTarget(String target) {
        if (!InputValidationUtils.isValidSpoofTarget(target)) {
            throw new IllegalArgumentException(
                "Invalid spoof target: " + target + ". Valid targets are: " +
                InputValidationUtils.getValidSpoofTargets()
            );
        }
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putString(Keys.KEY_SPOOF_TARGET, target).apply();
    }

    public static boolean isSuBridgeEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_SU_BRIDGE_ENABLED, true);
    }

    public static boolean isRootAdawayBridgeEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ROOT_ADAWAY_BRIDGE_ENABLED, false);
    }

    public static boolean isRootMagiskMockingEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ROOT_MAGISK_MOCKING_ENABLED, false);
    }

    public static boolean isRootAutoGrantEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ROOT_AUTO_GRANT_ENABLED, false);
    }

    public static boolean isRootFileInterceptorEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ROOT_FILE_INTERCEPTOR_ENABLED, false);
    }

    public static boolean isRootBusyboxMockingEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_ROOT_BUSYBOX_MOCKING_ENABLED, false);
    }

    public static boolean showStartAdbHome() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_SHOW_START_ADB_HOME, false);
    }

    @Nullable
    public static String getCardOrder() {
        SharedPreferences p = getPreferences();
        return p != null ? p.getString(Keys.KEY_CARD_ORDER, null) : null;
    }

    public static void setCardOrder(@Nullable String order) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putString(Keys.KEY_CARD_ORDER, order).apply();
    }

    public static java.util.Set<String> getHiddenHomeCards() {
        SharedPreferences p = getPreferences();
        return p != null ? p.getStringSet(Keys.KEY_HIDDEN_HOME_CARDS, new java.util.HashSet<>()) : new java.util.HashSet<>();
    }

    public static void setHiddenHomeCards(java.util.Set<String> hidden) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putStringSet(Keys.KEY_HIDDEN_HOME_CARDS, hidden).apply();
    }

    public static void addHiddenHomeCard(String cardId) {
        java.util.Set<String> hidden = new java.util.HashSet<>(getHiddenHomeCards());
        hidden.add(cardId);
        setHiddenHomeCards(hidden);
    }

    public static void removeHiddenHomeCard(String cardId) {
        java.util.Set<String> hidden = new java.util.HashSet<>(getHiddenHomeCards());
        hidden.remove(cardId);
        setHiddenHomeCards(hidden);
    }

    @Nullable
    public static String getExportDirUri() {
        SharedPreferences p = getPreferences();
        return p != null ? p.getString(Keys.KEY_EXPORT_DIR_URI, null) : null;
    }

    public static void setExportDirUri(@Nullable String uri) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putString(Keys.KEY_EXPORT_DIR_URI, uri).apply();
    }

    public static boolean isHideDisabledPlusFeaturesEnabled() {
        SharedPreferences p = getPreferences();
        return p != null && p.getBoolean(Keys.KEY_HIDE_DISABLED_PLUS_FEATURES, false);
    }

    public static void setHideDisabledPlusFeaturesEnabled(boolean enable) {
        SharedPreferences p = getPreferences();
        if (p != null) p.edit().putBoolean(Keys.KEY_HIDE_DISABLED_PLUS_FEATURES, enable).apply();
    }

    public static void syncAllPlusFeaturesToServer() {
        if (!rikka.shizuku.Shizuku.pingBinder()) return;
        new Thread(() -> {
            try {
                android.os.IBinder binder = (android.os.IBinder) rikka.shizuku.Shizuku.getBinder();
                if (binder == null) return;
                af.shizuku.server.IShizukuService service = af.shizuku.server.IShizukuService.Stub.asInterface(binder);
                service.updatePlusFeatureEnabled("custom_api", isCustomApiEnabled());
                service.updatePlusFeatureEnabled("shell_interceptor", isShellInterceptorEnabled());
                service.updatePlusFeatureEnabled("avf_manager", isAvfManagerEnabled());
                service.updatePlusFeatureEnabled("storage_proxy", isStorageProxyEnabled());
                service.updatePlusFeatureEnabled("continuity_bridge", isContinuityBridgeEnabled());
                service.updatePlusFeatureEnabled("ai_core_plus", isAICorePlusEnabled());
                service.updatePlusFeatureEnabled("window_manager_plus", isWindowManagerPlusEnabled());
                service.updatePlusFeatureEnabled("overlay_manager_plus", isOverlayManagerPlusEnabled());
                service.updatePlusFeatureEnabled("network_governor_plus", isNetworkGovernorPlusEnabled());
                service.updatePlusFeatureEnabled("activity_manager_plus", isActivityManagerPlusEnabled());
                service.updatePlusFeatureEnabled("su_bridge", isSuBridgeEnabled());
                service.updatePlusFeatureEnabled("root_adaway_bridge", isRootAdawayBridgeEnabled());
                service.updatePlusFeatureEnabled("root_magisk_mocking", isRootMagiskMockingEnabled());
                service.updatePlusFeatureEnabled("root_auto_grant", isRootAutoGrantEnabled());
                service.updatePlusFeatureEnabled("root_file_interceptor", isRootFileInterceptorEnabled());
                service.updatePlusFeatureEnabled("root_busybox_mocking", isRootBusyboxMockingEnabled());
                service.updatePlusFeatureEnabled("vector", isVectorEnabled());
                service.updatePlusFeatureEnabled("experimental_root", isExperimentalRootCompatEnabled());
                service.updatePlusFeatureEnabled("spoof_device", isSpoofDeviceEnabled());
                service.setPlusSetting("spoof_target", getSpoofTarget());
                service.updatePlusFeatureEnabled("on_device_adb_tcp", isOnDeviceAdbTcpEnabled());
                service.updatePlusFeatureEnabled("force_start_wadb", isForceStartWadbEnabled());
                
                String suPathUri = getExportDirUri();
                if (suPathUri != null) {
                    try {
                        android.net.Uri uri = android.net.Uri.parse(suPathUri);
                        String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
                        String resolvedPath = null;
                        if (docId.startsWith("primary:")) {
                            String relative = docId.substring("primary:".length());
                            resolvedPath = "/storage/emulated/0/" + (relative.isEmpty() ? "" : relative + "/") + "su";
                        } else if (docId.contains(":")) {
                            String[] parts = docId.split(":");
                            if (parts.length > 1) {
                                resolvedPath = "/storage/" + parts[0] + "/" + parts[1] + "/su";
                            }
                        } else if (docId.startsWith("Download") || docId.startsWith("Documents") || docId.startsWith("Movies")) {
                            resolvedPath = "/storage/emulated/0/" + docId + "/su";
                        }
                        
                        if (resolvedPath != null) {
                            service.setPlusSetting("su_path", resolvedPath);
                        }
                    } catch (Exception e) {
                        Timber.tag("ShizukuSettings").e(e, "failed to update su_path");
                    }
                }
            } catch (Exception e) {
                Timber.tag("ShizukuSettings").e(e, "failed to process document result");
            }
        }).start();
    }

    // Auto Update Settings (Shizuku+ additions)
    public static boolean isAutoUpdateEnabled() {
        return getPreferences().getBoolean(Keys.KEY_AUTO_UPDATE_ENABLED, true);
    }

    public static void setAutoUpdateEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(Keys.KEY_AUTO_UPDATE_ENABLED, enabled).apply();
    }

    public static boolean isAutoInstallEnabled() {
        return getPreferences().getBoolean(Keys.KEY_AUTO_INSTALL_ENABLED, false);
    }

    public static void setAutoInstallEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(Keys.KEY_AUTO_INSTALL_ENABLED, enabled).apply();
    }

    public static long getLastUpdateCheckTime() {
        return getPreferences().getLong(Keys.KEY_LAST_UPDATE_CHECK, 0);
    }

    public static void setLastUpdateCheckTime(long time) {
        getPreferences().edit().putLong(Keys.KEY_LAST_UPDATE_CHECK, time).apply();
    }

    public static boolean wasLastUpdateCheckFailed() {
        return getPreferences().getBoolean(Keys.KEY_LAST_CHECK_FAILED, false);
    }

    public static void setLastUpdateCheckFailed(boolean failed) {
        getPreferences().edit().putBoolean(Keys.KEY_LAST_CHECK_FAILED, failed).apply();
    }

    /** "stable" (default) or "dev" */
    public static String getUpdateChannel() {
        return getPreferences().getString(Keys.KEY_UPDATE_CHANNEL, "stable");
    }

    public static void setUpdateChannel(String channel) {
        getPreferences().edit().putString(Keys.KEY_UPDATE_CHANNEL, channel).apply();
    }
}
