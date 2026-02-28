package moe.shizuku.manager.utils

object AppContextManager {
    private val appDescriptions = mapOf(
        "moe.shizuku.privileged.api" to "Shizuku itself",
        "rikka.sui" to "Sui: Modern Superuser interface for Android",
        "com.aistra.hail" to "Hail: Freeze and hide apps",
        "com.amarok" to "Amarok: Hide your files and apps with one click",
        "com.catchingnow.icebox" to "Ice Box: Freeze and hide apps you don't use often",
        "com.rosan.dhizuku" to "Dhizuku: Share Device Owner with other apps",
        "com.termux" to "Termux: Terminal emulator and Linux environment",
        "web.id.go_id.id.go_id.id.go_id.id.go_id" to "Digital ID: Indonesian identity card application"
    )

    fun getDescription(packageName: String): String? {
        return appDescriptions[packageName]
    }
}
