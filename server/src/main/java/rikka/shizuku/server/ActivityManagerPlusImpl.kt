package rikka.shizuku.server

import android.os.Process
import af.shizuku.server.IActivityManagerPlus
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.server.util.UserHandleCompat

class ActivityManagerPlusImpl : IActivityManagerPlus.Stub() {
    override fun deepForceStop(packageName: String?): Boolean {
        if (packageName == null) return false
        try {
            ActivityManagerApis.forceStopPackageNoThrow(packageName, UserHandleCompat.getUserId(Process.myUid()))
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun setAppStandbyBucket(packageName: String?, bucket: Int): Boolean {
        if (packageName == null) return false
        return try {
            val bucketStr = when (bucket) {
                10 -> "active"
                20 -> "working_set"
                30 -> "frequent"
                40 -> "rare"
                45 -> "restricted"
                50 -> "restricted"
                else -> bucket.toString()
            }
            val process = Runtime.getRuntime().exec(arrayOf("am", "set-standby-bucket", packageName, bucketStr))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun killAllBackgroundProcesses(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "kill-all"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun freezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            // Use pm disable-user which works for shell on user 0
            val process = Runtime.getRuntime().exec(arrayOf("pm", "disable-user", "--user", "0", packageName))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("pm", "enable", "--user", "0", packageName))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun isAppFrozen(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("pm", "list", "packages", "-d", packageName))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    override fun setAppProcessLimit(limit: Int) {
        try {
            // This setting is internal to ActivityManagerService but can be set via 'am' on some builds
            // Fallback to global setting if command fails
            val process = Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "max_phantom_processes", limit.toString()))
            process.waitFor()
            Runtime.getRuntime().exec(arrayOf("am", "set-process-limit", limit.toString())).waitFor()
        } catch (e: Exception) {
            // Log warning or ignore
        }
    }

    override fun getRunningProcesses(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "stack", "list")) // Dummy check for am access
            process.waitFor()
            
            // Use 'ps' which is more reliable for raw listing via shell
            val psProcess = Runtime.getRuntime().exec(arrayOf("ps", "-A", "-o", "NAME,RSS,PID"))
            psProcess.inputStream.bufferedReader().use { it.readLines() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun clearAppCache(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            // pm trim-caches trims to a target free space, but we use pm clear for absolute clearing
            // Note: pm clear wipes data AND cache. For just cache, we can use pm trim-caches with a huge value.
            val process = Runtime.getRuntime().exec(arrayOf("pm", "trim-caches", "4096G"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun clearAppData(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("pm", "clear", "--user", "0", packageName))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
