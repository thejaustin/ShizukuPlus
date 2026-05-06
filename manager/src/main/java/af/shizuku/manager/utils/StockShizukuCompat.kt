package af.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object StockShizukuCompat {

    const val PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launch(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
