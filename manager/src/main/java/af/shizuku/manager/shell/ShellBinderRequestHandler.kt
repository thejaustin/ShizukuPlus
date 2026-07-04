package af.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import timber.log.Timber
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import af.shizuku.manager.ShizukuSettings

object ShellBinderRequestHandler {

    fun handleRequest(context: Context, intent: Intent, requireAuth: Boolean = false): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return false
        }

        if (requireAuth) {
            val rawToken = intent.getStringExtra("auth")
            val authToken = if (rawToken != null) af.shizuku.manager.utils.IntentCrypto.decrypt(rawToken) else null
            val expectedToken = ShizukuSettings.getAuthToken()
            if (authToken != expectedToken) {
                return false
            }
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        val shizukuBinder = try {
            Shizuku.getBinder()
        } catch (e: Exception) {
            LOGGER.e(e, "getBinder failed")
            return false
        }
        if (shizukuBinder == null) {
            LOGGER.e("shizuku binder is null")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("rikka.shizuku.IShizukuService")
            data.writeStrongBinder(shizukuBinder)
            binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY)
            return true
        } catch (e: Exception) {
            LOGGER.e(e, "transact")
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
