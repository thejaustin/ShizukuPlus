package af.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import af.shizuku.manager.ktx.loge
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import af.shizuku.manager.ShizukuSettings

object ShellBinderRequestHandler {

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") {
            return false
        }

        val authToken = intent.getStringExtra("auth")
        val expectedToken = ShizukuSettings.getAuthToken()
        if (authToken.isNullOrEmpty() || authToken != expectedToken) {
            LOGGER.w("Invalid or missing auth token in REQUEST_BINDER intent")
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        val shizukuBinder = Shizuku.getBinder()
        if (shizukuBinder == null) {
            LOGGER.w("Binder not received or Shizuku service not running")
        }

        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Throwable) {
            loge("transact binder failed", e)
            false
        } finally {
            data.recycle()
        }
    }
}
