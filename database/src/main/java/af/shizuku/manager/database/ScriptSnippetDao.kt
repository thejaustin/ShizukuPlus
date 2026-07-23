package af.shizuku.manager.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for saved script snippets.
 */
@Dao
interface ScriptSnippetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(snippet: ScriptSnippetRoom): Long

    @Update
    fun update(snippet: ScriptSnippetRoom)

    @Delete
    fun delete(snippet: ScriptSnippetRoom)

    @Query("SELECT * FROM script_snippets ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ScriptSnippetRoom>>

    @Query("SELECT * FROM script_snippets WHERE id = :id")
    fun getById(id: Long): ScriptSnippetRoom?
}
