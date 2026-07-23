package af.shizuku.manager.plugin

/** In-memory registry of [PlusFeatureModule]s, populated at app startup. */
object PlusFeatureRegistry {
    private val modules = mutableListOf<PlusFeatureModule>()

    @Synchronized
    fun register(module: PlusFeatureModule) {
        if (modules.none { it.id == module.id }) modules.add(module)
    }

    @Synchronized
    fun all(): List<PlusFeatureModule> = modules.toList()

    @Synchronized
    fun find(id: String): PlusFeatureModule? = modules.find { it.id == id }
}
