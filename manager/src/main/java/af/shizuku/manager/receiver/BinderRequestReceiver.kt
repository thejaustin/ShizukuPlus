package af.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import af.shizuku.manager.shell.ShellBinderRequestHandler

class BinderRequestReceiver : AuthenticatedReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return
        }

        // Legacy clients (pre-v11) broadcast REQUEST_BINDER without an auth token.
        // The modern BinderSender mechanism handles binder delivery automatically via ContentProvider,
        // so just silently skip unauthenticated broadcasts instead of showing a confusing notification.
        if (intent.getStringExtra("auth") == null) {
            return
        }

        super.onReceive(context, intent)
    }

    override fun onAuthenticated(context: Context, intent: Intent) {
        ShellBinderRequestHandler.handleRequest(context, intent, true)
    }
}
