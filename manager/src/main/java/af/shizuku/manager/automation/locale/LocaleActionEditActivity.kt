package af.shizuku.manager.automation.locale

import android.content.Intent
import android.os.Bundle
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.automation.locale.LocalePluginContract.ACTION_START
import af.shizuku.manager.automation.locale.LocalePluginContract.ACTION_STOP
import af.shizuku.manager.automation.locale.LocalePluginContract.BUNDLE_KEY_ACTION
import af.shizuku.manager.automation.locale.LocalePluginContract.EXTRA_BUNDLE
import af.shizuku.manager.automation.locale.LocalePluginContract.EXTRA_STRING_BLURB
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * com.twofortyfouram.locale.intent.action.EDIT_SETTING handler: lets Tasker/Locale-compatible
 * apps configure which action (start or stop the Shizuku+ service) their plugin instance fires.
 * Extends AppActivity (not plain AppCompatActivity) so onApplyUserThemeResource/
 * computeUserThemeKey actually run - see AdbPairingDialogActivity for the full explanation.
 */
class LocaleActionEditActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val actions = arrayOf(ACTION_START, ACTION_STOP)
        val labels = arrayOf(
            getString(R.string.automation_plugin_action_start),
            getString(R.string.automation_plugin_action_stop)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.automation_plugin_action_edit_title)
            .setItems(labels) { _, which -> finishWithResult(actions[which]) }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }

    private fun finishWithResult(action: String) {
        val blurb = getString(
            if (action == ACTION_START) R.string.automation_plugin_blurb_start
            else R.string.automation_plugin_blurb_stop
        )

        val resultBundle = Bundle().apply { putString(BUNDLE_KEY_ACTION, action) }
        val resultIntent = Intent().apply {
            putExtra(EXTRA_BUNDLE, resultBundle)
            putExtra(EXTRA_STRING_BLURB, blurb)
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
