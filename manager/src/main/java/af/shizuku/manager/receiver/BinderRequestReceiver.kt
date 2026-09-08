package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.utils.IntentCrypto
import java.security.MessageDigest

class BinderRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return
        }

        val rawToken = intent.getStringExtra("auth")
            ?: intent.getStringExtra("token")
            ?: intent.getStringExtra("auth_token")

        val tokenCandidate = rawToken?.trim()?.removeSurrounding("\"")?.let {
            if (it.startsWith("auth:")) it.substring(5).trim() else it
        }

        val expectedToken = ShizukuSettings.getAuthToken()
        val decryptedToken = if (!tokenCandidate.isNullOrEmpty()) {
            IntentCrypto.decrypt(tokenCandidate)
        } else null

        // Constant-time compare: this gates handing out the live Shizuku binder.
        val authValid = when {
            tokenCandidate.isNullOrEmpty() -> false
            decryptedToken != null && MessageDigest.isEqual(decryptedToken.toByteArray(), expectedToken.toByteArray()) -> true
            MessageDigest.isEqual(tokenCandidate.toByteArray(), expectedToken.toByteArray()) -> true
            else -> false
        }

        if (authValid) {
            // deliverBinder() may Thread.sleep() up to 2.3 s on freeze-retry — move off main thread.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ShellBinderRequestHandler.handleRequest(context, intent, requireAuth = true)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // No/invalid auth token - the path every rish/shell client takes today, since
        // IntentCrypto's AndroidKeyStore key is scoped to this app's own UID and a shell
        // process can never produce a token that decrypts correctly (GH #368/#372/#374).
        //
        // SECURITY: this used to gate binder delivery on a consent notification here, keyed off
        // intent-supplied callingPackage/callingUid extras to decide "already authorized, skip
        // the prompt" (added for #398, then removed once that was found spoofable). Broadcast
        // Intent extras carry NO verified sender identity - Binder.getCallingUid() is not
        // meaningful in onReceive() for a plain sendBroadcast(), and this action
        // (rikka.shizuku.intent.action.REQUEST_BINDER) is the public, unauthenticated part of
        // Shizuku's client API that any app on the device can send. Trusting those extras to
        // decide "skip the prompt" let ANY installed app claim callingPackage="<any
        // already-authorized package>", resolve that package's real (public) UID via
        // PackageManager, and receive the live Shizuku binder with zero interaction - a silent
        // privilege escalation. Removing that fast path (keeping the notification-per-request
        // behavior) fixed the vulnerability but regressed the UX: "Allow always" stopped actually
        // meaning "always" - every rish invocation re-prompted forever (#420, #416).
        //
        // Fix: deliver the binder unconditionally, with no consent decision made here at all.
        // Receiving a live binder reference is not the actual privilege boundary - every
        // privileged AIDL method is separately gated by enforceCallingPermission(), which reads
        // Binder.getCallingUid() from the REAL transaction the caller makes when it attaches and
        // invokes something, not from these spoofable broadcast extras. The one-time consent
        // prompt (and its "remember this uid forever" grant) now happens entirely in that
        // uid-verified path: attachApplication() -> checkSelfPermission() -> requestPermission()
        // -> showPermissionConfirmation() -> RequestPermissionActivity, which already correctly
        // skips prompting once ClientRecord.allowed/the persisted config flag is set for that
        // (real, kernel-verified) uid. That activity previously bailed silently when
        // PackageManager couldn't resolve the caller (same #391 PM-lookup gap this shell path hit)
        // - fixed alongside this change so it degrades to a package-name/uid label instead of
        // dropping the request.
        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder")
        if (callbackBinder != null) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ShellBinderRequestHandler.deliverBinder(context, callbackBinder)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
