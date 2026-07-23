package af.shizuku.manager.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/** Result of running a saved snippet through the privileged shell channel. */
data class ScriptRunResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Manager for saved script snippets (Scripting & Snippets, #11) — persistence plus running a
 * snippet through the same privileged shell channel the rest of the app uses
 * (`Shizuku.newProcess`), following the capture pattern already established in
 * [RootCompatHelper.runPrivilegedCapture].
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
     * stderr, and the exit code. Output is drained on background threads with a join timeout so a
     * snippet that fills its pipe buffer can't hang the caller indefinitely.
     */
    suspend fun run(script: String): ScriptRunResult = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) {
            return@withContext ScriptRunResult(-1, "", "Shizuku binder not available")
        }
        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
            val out = StringBuilder()
            val err = StringBuilder()
            val outT = Thread { try { process.inputStream.bufferedReader().use { out.append(it.readText()) } } catch (_: Exception) {} }
            val errT = Thread { try { process.errorStream.bufferedReader().use { err.append(it.readText()) } } catch (_: Exception) {} }
            outT.start(); errT.start()
            try { process.outputStream.close() } catch (_: Exception) {}
            val exitCode = process.waitFor()
            outT.join(5000); errT.join(5000)
            ScriptRunResult(exitCode, out.toString(), err.toString())
        } catch (e: Exception) {
            Timber.tag("ScriptSnippetManager").w(e, "run failed")
            ScriptRunResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }
}
