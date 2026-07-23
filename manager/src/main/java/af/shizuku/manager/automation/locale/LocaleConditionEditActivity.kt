package af.shizuku.manager.automation.locale

import android.content.Intent
import android.os.Bundle
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.automation.locale.LocalePluginContract.EXTRA_BUNDLE
import af.shizuku.manager.automation.locale.LocalePluginContract.EXTRA_STRING_BLURB
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * com.twofortyfouram.locale.intent.action.EDIT_CONDITION handler. Only one condition exists
 * ("Shizuku+ service is running"), so this is just a confirmation dialog rather than a picker.
 */
class LocaleConditionEditActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.automation_plugin_condition_title)
            .setMessage(R.string.automation_plugin_condition_description)
            .setPositiveButton(android.R.string.ok) { _, _ -> finishWithResult() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            // No configuration options, so the bundle only needs to be non-null/present.
            putExtra(EXTRA_BUNDLE, Bundle())
            putExtra(EXTRA_STRING_BLURB, getString(R.string.automation_plugin_condition_blurb))
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
