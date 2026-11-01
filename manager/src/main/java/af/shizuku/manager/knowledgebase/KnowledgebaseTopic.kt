package af.shizuku.manager.knowledgebase

import af.shizuku.manager.R

data class KnowledgebaseTopic(
    val id: String,
    val title: String,
    val subtitle: String,
    val assetFileName: String,
    val webFallbackUrl: String,
    val iconRes: Int = R.drawable.ic_server_24,
)

object KnowledgebaseRegistry {
    const val TOPIC_ROOT_COMPAT = "root_compat"
    const val TOPIC_WIRELESS_ADB = "wireless_adb"
    const val TOPIC_BATTERY_OEM = "battery_oem"
    const val TOPIC_TERMINAL_RISH = "terminal_rish"
    const val TOPIC_SECURITY_PRIVACY = "security_privacy"

    val TOPICS =
        mapOf(
            TOPIC_ROOT_COMPAT to
                KnowledgebaseTopic(
                    id = TOPIC_ROOT_COMPAT,
                    title = "Root Compatibility & SU Bridge",
                    subtitle = "Enable root-only apps (NeoBackup, AdAway) without permanent root",
                    assetFileName = "knowledgebase/root_compat_su_bridge.md",
                    webFallbackUrl = "https://github.com/thejaustin/ShizukuPlus/wiki/Root-Compatibility-Hub",
                    iconRes = R.drawable.ic_server_24,
                ),
            TOPIC_WIRELESS_ADB to
                KnowledgebaseTopic(
                    id = TOPIC_WIRELESS_ADB,
                    title = "Wireless Debugging & Connection",
                    subtitle = "Cable-free activation, automatic port discovery, and pairing",
                    assetFileName = "knowledgebase/wireless_debugging_setup.md",
                    webFallbackUrl = "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb",
                    iconRes = R.drawable.ic_server_24,
                ),
            TOPIC_BATTERY_OEM to
                KnowledgebaseTopic(
                    id = TOPIC_BATTERY_OEM,
                    title = "Keep-Alive & Battery Optimization",
                    subtitle = "Prevent Samsung, Xiaomi, and Pixel task killers from freezing Shizuku",
                    assetFileName = "knowledgebase/oem_battery_protection.md",
                    webFallbackUrl = "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference",
                    iconRes = R.drawable.ic_server_24,
                ),
            TOPIC_TERMINAL_RISH to
                KnowledgebaseTopic(
                    id = TOPIC_TERMINAL_RISH,
                    title = "Terminal & Rish Shell Setup",
                    subtitle = "Run privileged shell commands in Termux without cables or root",
                    assetFileName = "knowledgebase/terminal_rish_setup.md",
                    webFallbackUrl = "https://github.com/thejaustin/ShizukuPlus-API/tree/master/rish",
                    iconRes = R.drawable.ic_server_24,
                ),
            TOPIC_SECURITY_PRIVACY to
                KnowledgebaseTopic(
                    id = TOPIC_SECURITY_PRIVACY,
                    title = "Security & Sandboxing Architecture",
                    subtitle = "Why ShizukuPlus is safer than root and protects Play Integrity",
                    assetFileName = "knowledgebase/privacy_sandboxing.md",
                    webFallbackUrl = "https://github.com/thejaustin/ShizukuPlus/tree/master/README.md#developer-guide",
                    iconRes = R.drawable.ic_server_24,
                ),
        )

    fun getTopic(id: String): KnowledgebaseTopic? = TOPICS[id]

    /** Maps settings preference keys to specific knowledgebase topics. */
    fun topicForPrefKey(key: String?): String? =
        when (key) {
            "fake_su", "su_bridge", "su_bridge_enabled", "root_auto_grant", "root_auto_grant_enabled",
            "root_build_prop", "root_build_prop_redirect_enabled", "root_file_interceptor",
            "root_file_interceptor_enabled", "root_iptables", "root_iptables_mocking_enabled",
            "root_magisk", "root_magisk_mocking_enabled", "root_busybox", "root_busybox_mocking_enabled",
            "root_kernel_ghosting_enabled", "root_partition_ghosting_enabled", "root_power_ghosting_enabled",
            "overlay_fs_proxy_enabled", "samsung_system_uid_escalation_enabled", "software_keystore_fallback_enabled",
            "dhizuku", "dhizuku_mode", "dhizuku_disallow_usb", "dhizuku_disable_screencap", "dhizuku_suspended_packages",
            "custom_su_path", "root_compatibility_hub", "experimental_root_compat", "clear_device_owner",
            "category_su_bridge", "category_dhizuku_admin", "category_ghost_bridge",
            -> TOPIC_ROOT_COMPAT

            "auto_reconnect_mdns", "wireless_adb_card", "tcp_mode", "tcp_port", "randomize_tcp_port", "start_wireless_adb",
            "wireless_debugging", "network_governor_plus", "reconnect_wifi",
            -> TOPIC_WIRELESS_ADB

            "watchdog", "start_on_boot", "continuity_bridge", "rampart_enabled",
            "rampart_max_restrictions_enabled", "vendor_battery", "battery_optimization",
            "category_watchdog_keepalive",
            -> TOPIC_BATTERY_OEM

            "shell_interceptor", "terminal_entry", "pty_mode", "terminal_setup",
            "use_pty", "terminal_command",
            -> TOPIC_TERMINAL_RISH

            "stealth_mode", "binder_firewall", "shadow_binder", "shadow_binder_enabled",
            "shadow_binder_hidden_packages", "binder_logging", "spoof_device", "spoof_device_enabled",
            "spoof_target", "package_governor_plus", "category_security", "category_unrestricted_access",
            "unrestricted_app_data_enabled", "unrestricted_personal_data_enabled",
            -> TOPIC_SECURITY_PRIVACY

            else -> null
        }
}
