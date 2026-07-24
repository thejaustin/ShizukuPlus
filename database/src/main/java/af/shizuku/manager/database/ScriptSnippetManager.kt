package af.shizuku.manager.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Result of running a saved snippet through the privileged shell channel. */
data class ScriptRunResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Manager for saved script snippets (Scripting & Snippets, #11) — persistence plus running a
 * snippet through the same privileged shell channel the rest of the app uses, via the shared
 * [ShizukuProcessUtils.runPrivilegedCapture].
 */
object ScriptSnippetManager {

    @Volatile
    private var dao: ScriptSnippetDao? = null

    fun initialize(context: Context) {
        if (dao != null) return
        dao = ScriptSnippetDatabase.getInstance(context.applicationContext).scriptSnippetDao()
    }

    fun getAll(): Flow<List<ScriptSnippetRoom>> =
        requireNotNull(dao) { "ScriptSnippetManager not initialized" }.getAll()

    suspend fun save(id: Long?, title: String, script: String): Long = withContext(Dispatchers.IO) {
        val d = requireNotNull(dao) { "ScriptSnippetManager not initialized" }
        val now = System.currentTimeMillis()
        if (id == null) {
            d.insert(ScriptSnippetRoom(title = title, script = script, createdAt = now, updatedAt = now))
        } else {
            val existing = d.getById(id)
            d.update(
                ScriptSnippetRoom(
                    id = id,
                    title = title,
                    script = script,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
            id
        }
    }

    suspend fun delete(snippet: ScriptSnippetRoom) = withContext(Dispatchers.IO) {
        requireNotNull(dao) { "ScriptSnippetManager not initialized" }.delete(snippet)
    }

    /**
     * Runs [script] via `sh -c` through Shizuku's privileged process API and captures stdout,
     * stderr, and the exit code, via the shared [ShizukuProcessUtils.runPrivilegedCapture].
     */
    suspend fun run(script: String): ScriptRunResult = withContext(Dispatchers.IO) {
        val result = ShizukuProcessUtils.runPrivilegedCapture(arrayOf("sh", "-c", script), joinTimeoutMs = 5000)
        ScriptRunResult(result.exitCode, result.stdout, result.stderr)
    }
}
