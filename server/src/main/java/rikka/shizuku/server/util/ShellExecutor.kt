package rikka.shizuku.server.util

import android.util.Log

object ShellExecutor {
    private const val TAG = "ShellExecutor"

    fun exec(vararg args: String): String = try {
        val proc = Runtime.getRuntime().exec(args)
        try {
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out
        } finally { proc.destroy() }
    } catch (e: Exception) { Log.w(TAG, "exec failed: ${args.joinToString(" ")}", e); "" }

    fun execBool(vararg args: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(args)
        try { proc.waitFor() == 0 } finally { proc.destroy() }
    } catch (e: Exception) { Log.w(TAG, "execBool failed: ${args.joinToString(" ")}", e); false }

    fun execCode(vararg args: String): Int = try {
        val proc = Runtime.getRuntime().exec(args)
        try { proc.waitFor() } finally { proc.destroy() }
    } catch (e: Exception) { Log.w(TAG, "execCode failed: ${args.joinToString(" ")}", e); -1 }
}
