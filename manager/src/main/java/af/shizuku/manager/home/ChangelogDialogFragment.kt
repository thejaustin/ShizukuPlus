package af.shizuku.manager.home

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

class ChangelogDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ChangelogDialogFragment"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val changelogText = try {
            requireContext().assets.open("changelog.txt").bufferedReader().use { reader ->
                val sb = StringBuilder()
                var lineCount = 0
                while (lineCount < 30) { // Get the top 30 lines (latest version)
                    val line = reader.readLine() ?: break
                    sb.append(line).append("\n")
                    lineCount++
                }
                sb.toString()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read changelog.txt")
            "No changelog available."
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog_title)
            .setMessage(changelogText)
            .setPositiveButton(R.string.changelog_close, null)
            .create()
    }
}
