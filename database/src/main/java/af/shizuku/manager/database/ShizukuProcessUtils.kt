package af.shizuku.manager.database

import java.io.OutputStream
import rikka.shizuku.Shizuku
import timber.log.Timber

/** Result of a privileged command run through [ShizukuProcessUtils.runPrivilegedCapture]. */
data class ShizukuCaptureResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Shared helper for running commands through `Shizuku.newProcess` and capturing their output.
 * Extracted from the copy-pasted drain logic that used to live separately in
 * `RootCompatHelper` (executePrivileged/streamToPrivilegedFile/runPrivilegedCapture) and
 * `ScriptSnippetManager.run`.
 */
object ShizukuProcessUtils {

    /**
     * Runs [cmd] via Shizuku's privileged process API, draining stdout/stderr on background
     * threads so a full pipe buffer can't hang the child. If [writeStdin] is given it's invoked
     * with the process's stdin before waiting (the lambda must close it, e.g. via `.use { }`, so
     * the child sees EOF); otherwise stdin is closed immediately. [joinTimeoutMs] bounds how long
     * we wait for the drain threads once the process exits.
     */
    fun runPrivilegedCapture(
        cmd: Array<String>,
        joinTimeoutMs: Long = 1500,
        writeStdin: ((OutputStream) -> Unit)? = null
    ): ShizukuCaptureResult {
        if (!Shizuku.pingBinder()) {
            return ShizukuCaptureResult(-1, "", "Shizuku binder not available")
        }
        return try {
            val process = Shizuku.newProcess(cmd, null, null)
            val out = StringBuilder()
            val err = StringBuilder()
            val outT = Thread {
                try {
                    process.inputStream.bufferedReader().use { out.append(it.readText()) }
                } catch (e: Exception) {
                    Timber.v(e, "runPrivilegedCapture: stdout drain failed")
                }
            }
            val errT = Thread {
                try {
                    process.errorStream.bufferedReader().use { err.append(it.readText()) }
                } catch (e: Exception) {
                    Timber.v(e, "runPrivilegedCapture: stderr drain failed")
                }
            }
            outT.start(); errT.start()
            if (writeStdin != null) {
                writeStdin(process.outputStream)
            } else {
                try { process.outputStream.close() } catch (_: Exception) {}
            }
            val exitCode = process.waitFor()
            outT.join(joinTimeoutMs); errT.join(joinTimeoutMs)
            if (outT.isAlive || errT.isAlive) {
                Timber.w("runPrivilegedCapture: drain thread still running after ${joinTimeoutMs}ms join timeout; output may be truncated")
            }
            ShizukuCaptureResult(exitCode, out.toString(), err.toString())
        } catch (e: Exception) {
            Timber.w(e, "runPrivilegedCapture failed")
            ShizukuCaptureResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }
}
