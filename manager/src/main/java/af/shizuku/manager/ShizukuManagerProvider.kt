package af.shizuku.manager

import af.shizuku.api.BinderContainer
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuStateMachine
import android.os.Bundle
import androidx.core.os.bundleOf
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider

class ShizukuManagerProvider : ShizukuProvider() {
    companion object {
        private const val EXTRA_BINDER = "af.shizuku.plus.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    private fun waitForBinderGracefully(onlyDuringColdStart: Boolean = false) {
        if (onlyDuringColdStart) {
            // Client getBinder: only while this process is cold-starting and the server's binder
            // delivery may still be in flight. Otherwise a stopped service would stall every
            // client's getBinder call by 1.5 s.
            val processAgeMs = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
            if (processAgeMs !in 0L until 5_000L) return
        }
        if (!Shizuku.pingBinder()) {
            // Cold-start grace period: wait up to 1500ms for binder delivery from server daemon
            val deadline = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                if (Shizuku.pingBinder()) {
                    break
                }
            }
        }
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (extras == null) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder =
                    extras.getBinder("binder")
                        ?: extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder
                        ?: extras.getParcelable<moe.shizuku.api.BinderContainer>("moe.shizuku.privileged.api.intent.extra.BINDER")?.binder
                        ?: extras.getParcelable<rikka.shizuku.BinderContainer>("rikka.shizuku.intent.extra.BINDER")?.binder
                        ?: return null

                waitForBinderGracefully()
                ShizukuStateMachine.update()

                if (!ShizukuStateMachine.isRunning()) {
                    LOGGER.w("sendUserService called when not running")
                    return null
                }

                try {
                    Shizuku.attachUserService(binder, bundleOf(USER_SERVICE_ARG_TOKEN to token))
                    val serviceBinder = Shizuku.getBinder() ?: return null
                    val reply = Bundle()
                    reply.putParcelable(EXTRA_BINDER, BinderContainer(serviceBinder))
                    reply.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", moe.shizuku.api.BinderContainer(serviceBinder))
                    reply.putParcelable("rikka.shizuku.intent.extra.BINDER", rikka.shizuku.BinderContainer(serviceBinder))
                    reply.putBinder("binder", serviceBinder)
                    reply
                } catch (e: Throwable) {
                    // A user service whose token the server no longer knows (it restarted, or the
                    // app's service was already torn down) is routine (SHIZUKUPLUS-9B).
                    if (e is IllegalArgumentException && e.message?.contains("unable to find token") == true) {
                        LOGGER.w(e, "attachUserService $token")
                    } else {
                        LOGGER.e(e, "attachUserService $token")
                    }
                    null
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            if (method == METHOD_GET_BINDER) {
                waitForBinderGracefully(onlyDuringColdStart = true)
            }
            super.call(method, arg, extras)
        }
    }
}
