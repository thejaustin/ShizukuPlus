package af.shizuku.manager.model

data class ServiceStatus(
        val uid: Int = -1,
        val apiVersion: Int = -1,
        val patchVersion: Int = -1,
        val seContext: String? = null,
        val permission: Boolean = false,
        // Set to true by HomeViewModel.loadStatus when the binder is reachable (pingBinder). This is
        // the authoritative "is the service running" signal: it needs no attachment, unlike getUid()
        // which enforceCallingPermission-gates on the caller being an attached client and can return
        // -1 for a window right after start (or when the manager's own attach races). Basing
        // isRunning on getUid made a healthy, reachable server show as "not running".
        val running: Boolean = false
) {
    val isRunning: Boolean
        get() = running
}
