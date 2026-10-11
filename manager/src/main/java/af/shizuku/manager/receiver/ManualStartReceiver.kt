package af.shizuku.manager.receiver

import af.shizuku.manager.BuildConfig
import android.content.Context
import android.content.Intent

class ManualStartReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(
        context: Context,
        intent: Intent,
    ) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "$applicationId.START") return

        // Token-authenticated, so this is an explicit start: it may raise one new dialog even if
        // a previous one went unanswered.
        af.shizuku.manager.adb.AdbAuthWait.clearUnanswered()
        ShizukuReceiverStarter.start(context)
    }
}
