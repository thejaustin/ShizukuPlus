package af.shizuku.manager.adb

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.text.method.TextKeyListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Extends AppActivity (not plain AppCompatActivity) so onApplyUserThemeResource/
// computeUserThemeKey actually run - without it, the manifest's Theme.App.DialogHost
// (declared with a hardcoded Dark parent, same as GrantPermissions/RequestPermissionActivity)
// renders literally dark instead of being rebased to the user's actual theme preference.
class AdbPairingDialogActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val portFromIntent = intent.getIntExtra("port_number", -1)

        val margin = (24 * resources.displayMetrics.density).toInt()
        val portEditText =
            if (portFromIntent == -1) {
                EditText(this).apply {
                    hint = getString(R.string.dialog_adb_port)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    keyListener = DigitsKeyListener.getInstance("0123456789")
                    filters = arrayOf(InputFilter.LengthFilter(5))
                    layoutParams =
                        ViewGroup
                            .MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { setMargins(margin, 0, margin, margin / 2) }
                }
            } else {
                null
            }

        val isNumerical = ShizukuSettings.isNumericalPairingKeyboardEnabled()
        val codeEditText =
            EditText(this).apply {
                hint = getString(R.string.dialog_adb_pairing_paring_code)
                inputType = if (isNumerical) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                keyListener =
                    if (isNumerical) {
                        DigitsKeyListener.getInstance("0123456789")
                    } else {
                        TextKeyListener.getInstance()
                    }
                filters = arrayOf(InputFilter.LengthFilter(6))
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
                layoutParams =
                    ViewGroup
                        .MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { setMargins(margin, 0, margin, 0) }
            }

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, margin / 2, 0, 0)
                if (portEditText != null) addView(portEditText)
                addView(codeEditText)
            }

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (portFromIntent == -1) {
                    R.string.dialog_adb_pairing_title
                } else {
                    R.string.notification_adb_pairing_service_found_title
                },
            ).setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetPort =
                    portFromIntent.takeIf { it != -1 }
                        ?: portEditText?.text?.toString()?.toIntOrNull()
                        ?: -1
                val code = codeEditText.text?.toString()?.trim() ?: ""
                if (targetPort in 1..65535 && code.isNotEmpty()) {
                    startForegroundService(AdbPairingService.dialogReplyIntent(this, targetPort, code))
                }
                finish()
            }.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
