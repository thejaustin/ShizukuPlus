package moe.shizuku.manager.utils

import moe.shizuku.manager.ShizukuSettings
import org.json.JSONObject

data class AppEnhancement(
    val key: String,
    val title: String,
    val description: String
)

object AppContextManager {
    
    data class AppMetadata(
        val description: String,
        val potentialEnhancements: List<AppEnhancement> = emptyList(),
        val isVerified: Boolean = false
    )

    private val ENH_SHELL = AppEnhancement("shell_interceptor", "Shell Acceleration", "Intercepts pm/am commands for native speed.")
    private val ENH_STORAGE = AppEnhancement("storage_proxy", "Storage Bridge", "Bypasses Android 16/17 storage restrictions.")
    private val ENH_DPM = AppEnhancement("dpm_plus", "Enhanced DPM", "Direct DevicePolicyManager access for better freezing.")
    private val ENH_NPU = AppEnhancement("npu_plus", "NPU Accelerator", "Prioritized Neural Processing Unit scheduling.")
    private val ENH_VM = AppEnhancement("vm_plus", "AVF Linux VM", "Spawns an isolated Microdroid VM for this task.")
    private val ENH_WIN = AppEnhancement("win_plus", "Window Tuner", "Forces free-form and advanced window control.")

    private val dynamicDatabase = mutableMapOf<String, AppMetadata>()

    private val staticDatabase = mutableMapOf<String, AppMetadata>().apply {
        // --- thejaustin's Apps ---
        put("thejaustin.hexodus", AppMetadata("Hexodus: Spiritual successor to Hex Installer for OneUI 8.", listOf(ENH_SHELL, ENH_WIN), true))
        put("thejaustin.afdroid", AppMetadata("afdroid: Expressive F-Droid client.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("thejaustin.pearity", AppMetadata("Pearity: iOS parity settings for OneUI.", listOf(ENH_SHELL), true))
        put("thejaustin.termux_ai", AppMetadata("Termux AI: AI-powered terminal with NPU integration.", listOf(ENH_NPU, ENH_VM, ENH_SHELL), true))
        put("thejaustin.appmanager", AppMetadata("AppManager: Advanced package manager with archiving.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("thejaustin.simweather", AppMetadata("SimWeather: Material You weather tool.", listOf(ENH_SHELL), true))
        put("thejaustin.contactsplus", AppMetadata("ContactsPlus: Fossify Contacts fork with M3 design.", listOf(ENH_SHELL), true))
        put("thejaustin.obtainiumplus", AppMetadata("ObtainiumPlus: AI-assisted Obtainium fork.", listOf(ENH_SHELL), true))
        put("thejaustin.snapback", AppMetadata("SnapBack: Secure Snapchat Backup Viewer.", listOf(ENH_STORAGE), true))

        // --- Software Management & Freezers ---
        put("com.aistra.hail", AppMetadata("Hail: Modern app freezer.", listOf(ENH_SHELL, ENH_DPM), true))
        put("samolego.canta", AppMetadata("Canta: Powerful system app debloater.", listOf(ENH_SHELL), true))
        put("rikka.appops", AppMetadata("App Ops: Manage hidden app permissions.", listOf(ENH_SHELL), true))
        put("com.catchingnow.icebox", AppMetadata("Ice Box: Freeze apps to save battery.", listOf(ENH_SHELL, ENH_DPM), true))
        put("com.zacharee.installwithoptions", AppMetadata("InstallWithOptions: Advanced APK installer.", listOf(ENH_SHELL), true))
        put("cf.playhi.freezeyou", AppMetadata("FreezeYou: Battery and speed optimizer.", listOf(ENH_SHELL, ENH_DPM), true))
        put("com.oasisfeng.island", AppMetadata("Island: App isolation and cloning.", listOf(ENH_DPM, ENH_SHELL), true))
        put("com.oasisfeng.island.fdroid", AppMetadata("Insular: Island fork for F-Droid.", listOf(ENH_DPM, ENH_SHELL), true))

        // --- File Management ---
        put("bin.mt.plus", AppMetadata("MT Manager: Sophisticated file manager.", listOf(ENH_STORAGE), true))
        put("pl.solidexplorer2", AppMetadata("Solid Explorer: Powerful file manager.", listOf(ENH_STORAGE), true))
        put("com.ghisler.android.TotalCommander", AppMetadata("Total Commander: Desktop-class file explorer.", listOf(ENH_STORAGE), true))
        put("com.lonelycatgames.Xplore", AppMetadata("X-Plore: Dual-pane file manager.", listOf(ENH_STORAGE), true))
        put("ru.zdevs.zarchiver", AppMetadata("ZArchiver: Comprehensive archive manager.", listOf(ENH_STORAGE), true))
        put("com.alphainventor.filemanager", AppMetadata("File Manager Plus: Cloud and local explorer.", listOf(ENH_STORAGE), true))

        // --- Automation ---
        put("net.dinglisch.android.taskerm", AppMetadata("Tasker: Advanced Android automation.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("com.arlosoft.macrodroid", AppMetadata("MacroDroid: User-friendly automation.", listOf(ENH_SHELL), true))
        put("henrichg.phoneprofilesplus", AppMetadata("PhoneProfilesPlus: Contextual device config.", listOf(ENH_SHELL), true))
        put("eu.toneiv.ubktouch", AppMetadata("UbikiTouch: Global swipe gestures.", listOf(ENH_SHELL, ENH_WIN), true))

        // --- Customization ---
        put("com.kieronquinn.ambientmusicmod", AppMetadata("Ambient Music Mod: Now Playing for everyone.", listOf(ENH_SHELL), true))
        put("com.kieronquinn.darq", AppMetadata("DarQ: Per-app force dark mode.", listOf(ENH_SHELL), true))
        put("com.zacharee.tweaker", AppMetadata("System UI Tuner: Hidden system settings.", listOf(ENH_SHELL), true))
        put("dev.lexip.hecate", AppMetadata("Adaptive-Theme: Smart dark mode.", listOf(ENH_SHELL), true))
        put("mahmud0808.colorblendr", AppMetadata("ColorBlendr: Material You color editor.", listOf(ENH_SHELL), true))
        put("com.kieronquinn.smartspacer", AppMetadata("Smartspacer: Enhanced 'At a Glance' widget.", listOf(ENH_SHELL, ENH_WIN), true))

        // --- Network & Privacy ---
        put("com.ysy.app.firewall", AppMetadata("NetWall: Rootless app firewall.", listOf(ENH_SHELL), true))
        put("com.deltazefiro.amarokhider", AppMetadata("Amarok: Hide private files and apps.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("ahmetcanarslan.shizuwall", AppMetadata("ShizuWall: Open-source app firewall.", listOf(ENH_SHELL), true))
        put("tk.zwander.wifilist", AppMetadata("WiFiList: View saved WiFi passwords.", listOf(ENH_SHELL), true))

        // --- Tools & Terminals ---
        put("p.shashank.ashellyou", AppMetadata("aShell You: Material local ADB shell.", listOf(ENH_SHELL), true))
        put("rohitkushvaha01.reterminal", AppMetadata("ReTerminal: Material 3 terminal emulator.", listOf(ENH_SHELL, ENH_VM), true))
        put("com.imranr98.obtainium", AppMetadata("Obtainium: App updates from source.", listOf(ENH_SHELL), true))
        put("com.aurora.store", AppMetadata("Aurora Store: Privacy Play Store client.", listOf(ENH_SHELL), true))
        put("com.looker.droidify", AppMetadata("Droid-ify: Material F-Droid client.", listOf(ENH_SHELL), true))
        put("eu.darken.sdmse", AppMetadata("SD Maid SE: System cleaning tool.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("org.swiftapps.swiftbackup", AppMetadata("Swift Backup: App and data backups.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("com.mihonapp.mihon", AppMetadata("Mihon: Manga reader and extension manager.", listOf(ENH_SHELL), true))
    }

    fun getMetadata(packageName: String): AppMetadata? {
        if (dynamicDatabase.isEmpty()) loadFromCache()
        return dynamicDatabase[packageName] ?: staticDatabase[packageName]
    }
    
    fun getDescription(packageName: String): String? = getMetadata(packageName)?.description

    private fun loadFromCache() {
        val json = ShizukuSettings.getRemoteDbJson() ?: return
        try {
            val root = JSONObject(json)
            val apps = root.optJSONObject("apps") ?: return
            apps.keys().forEach { pkg ->
                val obj = apps.getJSONObject(pkg)
                val enhancements = mutableListOf<AppEnhancement>()
                val enhKeys = obj.optJSONArray("enhancements")
                if (enhKeys != null) {
                    for (i in 0 until enhKeys.length()) {
                        val key = enhKeys.getString(i)
                        when(key) {
                            "shell_interceptor" -> enhancements.add(ENH_SHELL)
                            "storage_proxy" -> enhancements.add(ENH_STORAGE)
                            "dpm_plus" -> enhancements.add(ENH_DPM)
                            "npu_plus" -> enhancements.add(ENH_NPU)
                            "vm_plus" -> enhancements.add(ENH_VM)
                            "win_plus" -> enhancements.add(ENH_WIN)
                        }
                    }
                }
                dynamicDatabase[pkg] = AppMetadata(
                    description = obj.optString("description", ""),
                    potentialEnhancements = enhancements,
                    isVerified = obj.optBoolean("verified", false)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateDatabase(json: String) {
        ShizukuSettings.setRemoteDbJson(json)
        ShizukuSettings.setLastDbUpdate(System.currentTimeMillis())
        dynamicDatabase.clear()
        loadFromCache()
    }
}
