package af.shizuku.manager.automation.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.automation.locale.LocalePluginContract.ACTION_START
import af.shizuku.manager.automation.locale.LocalePluginContract.ACTION_STOP
import af.shizuku.manager.automation.locale.LocalePluginContract.BUNDLE_KEY_ACTION
import af.shizuku.manager.automation.locale.LocalePluginContract.EXTRA_BUNDLE
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * com.twofortyfouram.locale.intent.action.FIRE_SETTING handler: invoked by Tasker/Locale-
 * compatible apps when a plugin instance configured via LocaleActionEditActivity actually fires.
 */
class LocaleActionFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra(EXTRA_BUNDLE) ?: return

        when (bundle.getString(BUNDLE_KEY_ACTION)) {
            ACTION_START -> ShizukuReceiverStarter.start(context)
            ACTION_STOP -> {
                if (ShizukuStateMachine.isRunning()) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                    runCatching { Shizuku.exit() }
                        .onFailure { Timber.tag("LocaleActionFireReceiver").w(it, "Shizuku.exit failed") }
                }
            }
            else -> Timber.tag("LocaleActionFireReceiver").w("Fired with unknown/missing action")
        }
    }
}
