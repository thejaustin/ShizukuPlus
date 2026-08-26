package af.shizuku.manager.settings

import android.app.Dialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.databinding.BugReportDialogBinding
import af.shizuku.manager.ktx.asLink
import af.shizuku.manager.ktx.applyTemplateArgs
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.ProjectLinks
import af.shizuku.manager.worker.AdbStartWorker

class BugReportDialog : DialogFragment() {

    private lateinit var binding: BugReportDialogBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = BugReportDialogBinding.inflate(layoutInflater)

        val updateLink = getString(R.string.bug_report_dialog_link_update)
            .asLink(ProjectLinks.LATEST_RELEASE)

        val wikiLink = getString(R.string.bug_report_dialog_link_wiki)
            .asLink(ProjectLinks.KNOWLEDGEBASE)

        val issuesLink = getString(R.string.bug_report_dialog_link_issues)
            .asLink(ProjectLinks.ISSUES)

        binding.apply {
            updateText.applyTemplateArgs(updateLink)
            wikiText.applyTemplateArgs(wikiLink)
            issuesText.applyTemplateArgs(issuesLink)
            methodText.applyTemplateArgs("GitHub")
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_report_bug)
            .setView(binding.root)
            .setPositiveButton(R.string.action_open_github) { _, _ ->
                CustomTabsHelper.launchUrlOrCopy(context, ProjectLinks.NEW_ISSUE)
            }
            .setNegativeButton(R.string.bug_report_dialog_button_email) { _, _ ->
                val plainBody = getString(
                    R.string.bug_report_email_body,
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                    Build.VERSION.RELEASE,
                    BuildConfig.VERSION_NAME
                )

                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(
                    "mailto:" + context.getString(R.string.support_email) +
                    "?subject=" + Uri.encode(getString(R.string.bug_report_email_subject)) +
                    "&body=" + Uri.encode(plainBody)
                ))
                try {
                    context.startActivity(intent)
                    dismiss()
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, context.getString(R.string.toast_no_email_app), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbStartWorker.NOTIFICATION_ID)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity is BugReportDialogActivity) activity?.finish()
    }

}
