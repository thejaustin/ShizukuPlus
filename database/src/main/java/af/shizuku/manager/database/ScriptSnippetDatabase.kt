package af.shizuku.manager.database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

        private fun buildDatabase(context: Context): ScriptSnippetDatabase =
            buildRoomDatabaseWithStorageFallback(
                context, DATABASE_NAME, ScriptSnippetDatabase::class.java, "ScriptSnippetDatabase"
            )
    }
}
