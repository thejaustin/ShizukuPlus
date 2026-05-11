package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.shell.ShellBinderRequestHandler

class BinderRequestReceiver : AuthenticatedReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") {
            return
        }

        super.onReceive(context, intent)
    }

    override fun onAuthenticated(context: Context, intent: Intent) {
        ShellBinderRequestHandler.handleRequest(context, intent, true)
    }
}
