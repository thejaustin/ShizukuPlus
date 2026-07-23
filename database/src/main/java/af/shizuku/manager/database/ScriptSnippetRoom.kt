package af.shizuku.manager.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a saved privileged shell snippet (Scripting & Snippets, #11).
 *
 * @property id Auto-generated unique identifier.
 * @property title User-facing name for the snippet.
 * @property script The shell command(s) to run, passed to `sh -c` as-is.
 * @property createdAt Unix timestamp in milliseconds when the snippet was created.
 * @property updatedAt Unix timestamp in milliseconds when the snippet was last edited.
 */
@Entity(
    tableName = "script_snippets",
    indices = [Index(value = ["title"])]
)
data class ScriptSnippetRoom(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val script: String,
    val createdAt: Long,
    val updatedAt: Long
)
