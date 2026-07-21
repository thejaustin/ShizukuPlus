package af.shizuku.manager.home

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import timber.log.Timber

/**
 * Shows what changed in the version the user just updated to. [newInstance] takes the release's
 * raw GitHub release-notes body (fetched by the caller via
 * [af.shizuku.manager.update.UpdateChecker.fetchReleaseNotesForTag]) — this fragment only
 * formats and displays it, so it stays usable even if notes couldn't be fetched (offline).
 */
class ChangelogDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ChangelogDialogFragment"
        private const val ARG_NOTES = "notes"
        private const val ARG_TAG_NAME = "tag_name"

        fun newInstance(notes: String?, tagName: String): ChangelogDialogFragment =
            ChangelogDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTES, notes)
                    putString(ARG_TAG_NAME, tagName)
                }
            }

        /**
         * GitHub release notes are Markdown meant for a web page. For a short dialog, drop the
         * "Recent Releases" rollup table/links (useful on GitHub, noisy here) and strip heading
         * markers so "### 💥 Crash & Stability Fixes" reads as plain text instead of raw Markdown.
         */
        private fun formatForDialog(rawNotes: String): String =
            rawNotes.substringBefore("## 📦 Recent Releases")
                .trim()
                .lineSequence()
                .joinToString("\n") { it.replace(Regex("^#+\\s*"), "") }
                .trim()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val rawNotes = arguments?.getString(ARG_NOTES)
        val tagName = arguments?.getString(ARG_TAG_NAME) ?: ""

        val message = try {
            rawNotes?.let { formatForDialog(it) }?.takeIf { it.isNotBlank() }
                ?: getString(R.string.changelog_fallback_message)
        } catch (e: Exception) {
            Timber.w(e, "Failed to format release notes for dialog")
            getString(R.string.changelog_fallback_message)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog_title)
            .setMessage(message)
            .setPositiveButton(R.string.changelog_close, null)
            .setNeutralButton(R.string.changelog_view_on_github) { _, _ ->
                try {
                    val url = "https://github.com/thejaustin/ShizukuPlus/releases/tag/$tagName"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to open release page for $tagName")
                }
            }
            .create()
    }
}
