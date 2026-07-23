package af.shizuku.manager.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import timber.log.Timber

/**
 * Room database for saved script snippets (Scripting & Snippets, #11).
 */
@Database(
    entities = [ScriptSnippetRoom::class],
    version = 1,
    exportSchema = false
)
abstract class ScriptSnippetDatabase : RoomDatabase() {

    abstract fun scriptSnippetDao(): ScriptSnippetDao

    companion object {
        private const val DATABASE_NAME = "shizuku_script_snippets.db"

        @Volatile
        private var instance: ScriptSnippetDatabase? = null

        private val lock = ReentrantLock()

        fun getInstance(context: Context): ScriptSnippetDatabase {
            return instance ?: lock.withLock {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): ScriptSnippetDatabase {
            // Same device-protected-storage-first strategy as ActivityLogDatabase, so snippets
            // survive direct boot; falls back to regular app storage if that context isn't usable.
            val candidates = buildList {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    add(context.createDeviceProtectedStorageContext())
                }
                add(context.applicationContext)
            }

            for (ctx in candidates) {
                val dbFile = ctx.getDatabasePath(DATABASE_NAME)
                val parent = dbFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                if (parent == null || parent.exists()) {
                    return Room.databaseBuilder(ctx, ScriptSnippetDatabase::class.java, DATABASE_NAME)
                        .fallbackToDestructiveMigration()
                        .build()
                }
                Timber.tag("ScriptSnippetDatabase").w("mkdirs failed for ${parent.absolutePath}, trying next context")
            }

            Timber.tag("ScriptSnippetDatabase").e("All storage contexts failed; falling back to in-memory database")
            return Room.inMemoryDatabaseBuilder(context.applicationContext, ScriptSnippetDatabase::class.java)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
