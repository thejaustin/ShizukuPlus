package af.shizuku.manager.utils

import af.shizuku.manager.R
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * After any settings restore: says plainly what a backup never brings back, so nobody wonders
 * why an app asks for access again or an API key is empty. [onDismissed] runs next (the held-back
 * privileged settings prompt), so the two dialogs never stack.
 */
fun showRestoreSummary(
    context: Context,
    onDismissed: () -> Unit,
) {
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.restore_summary_title)
        .setMessage(R.string.restore_summary_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> onDismissed() }
        .setCancelable(false)
        .show()
}
