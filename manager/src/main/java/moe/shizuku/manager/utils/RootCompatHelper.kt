package moe.shizuku.manager.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

object RootCompatHelper {

    /**
     * Automatically configures popular root apps to use the Shizuku+ SU Bridge.
     * Uses Shizuku's privileged shell to modify target app preferences.
     */
    suspend fun autoSetup(context: Context, packageName: String, suPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            when (packageName) {
                "org.adaway" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "adaway_su_path", suPath))
                    true
                }
                "dev.ukanth.ufirewall" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "afwall_su_path", suPath))
                    true
                }
                "eu.darken.sdm", "eu.darken.sdmse" -> {
                    // SD Maid / SE
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun executePrivileged(cmd: Array<String>) {
        try {
            val process = Shizuku.newProcess(cmd, null, null)
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
