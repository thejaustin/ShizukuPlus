package rikka.shizuku.server

import android.os.Process
import moe.shizuku.server.IActivityManagerPlus
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
}
