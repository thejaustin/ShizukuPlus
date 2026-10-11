package af.shizuku.manager.harness

import android.content.SharedPreferences

/**
 * SharedPreferences with the two layers a process death tells apart: [memory] (what this process
 * reads) and [disk] (what the next process starts from). Like the framework's implementation, a
 * commit() writes the whole in-memory map, so it also persists earlier apply()s.
 */
class FakePrefs : SharedPreferences {
    private val lock = Any()
    private val disk = HashMap<String, Any>()
    private val memory = HashMap<String, Any>()

    /** What commit() reports; false models a failed disk write. */
    @Volatile
    var commitResult = true

    /** Every read throws, as for storage that cannot be opened. Writes still land. */
    @Volatile
    var failOnRead = false

    /** The OS finished writing every apply() so far. */
    fun flush() = synchronized(lock) { disk.clear(); disk.putAll(memory) }

    /** The process died: whatever apply() had not reached disk is gone. */
    fun killProcess() = synchronized(lock) { memory.clear(); memory.putAll(disk) }

    fun inMemory(key: String): Boolean = synchronized(lock) { memory.containsKey(key) }

    fun onDisk(key: String): Boolean = synchronized(lock) { disk.containsKey(key) }

    private fun <T> read(block: () -> T): T {
        if (failOnRead) throw IllegalStateException("FakePrefs: storage unreadable")
        return synchronized(lock) { block() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(
        key: String?,
        defValue: T,
    ): T = if (key != null && memory.containsKey(key)) memory[key] as T else defValue

    override fun getAll(): MutableMap<String, *> = read { HashMap(memory) }

    override fun getString(key: String?, defValue: String?): String? = read { get(key, defValue) }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        read { get<Set<String>?>(key, defValues)?.toMutableSet() }

    override fun getInt(key: String?, defValue: Int): Int = read { get(key, defValue) }

    override fun getLong(key: String?, defValue: Long): Long = read { get(key, defValue) }

    override fun getFloat(key: String?, defValue: Float): Float = read { get(key, defValue) }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read { get(key, defValue) }

    override fun contains(key: String?): Boolean = read { key != null && memory.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clearAll = false

        private fun put(
            key: String?,
            value: Any?,
        ): SharedPreferences.Editor {
            puts[requireNotNull(key)] = value
            return this
        }

        override fun putString(
            key: String?,
            value: String?,
        ) = put(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ) = put(key, values?.toSet())

        override fun putInt(
            key: String?,
            value: Int,
        ) = put(key, value)

        override fun putLong(
            key: String?,
            value: Long,
        ) = put(key, value)

        override fun putFloat(
            key: String?,
            value: Float,
        ) = put(key, value)

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ) = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            removes += requireNotNull(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        private fun writeToMemory() {
            if (clearAll) memory.clear()
            removes.forEach { memory.remove(it) }
            puts.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
        }

        // The framework updates memory before the disk write, so a failed commit is still visible
        // to this process; only the next one loses it.
        override fun commit(): Boolean =
            synchronized(lock) {
                writeToMemory()
                if (commitResult) {
                    disk.clear()
                    disk.putAll(memory)
                }
                commitResult
            }

        override fun apply() = synchronized(lock) { writeToMemory() }
    }
}
