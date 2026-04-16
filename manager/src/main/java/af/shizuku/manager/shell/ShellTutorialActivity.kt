package af.shizuku.manager.shell

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import timber.log.Timber
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import kotlin.math.roundToInt
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.AppBarActivity
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CustomTabsHelper
import rikka.compatibility.DeviceCompatibility
import rikka.html.text.HtmlCompat
import rikka.insets.*

class ShellTutorialActivity : AppBarActivity() {

    companion object {

        private val TAG = ShellTutorialActivity::class.java.simpleName

        private val SH_NAME = "rish"
        private val DEX_NAME = "rish_shizuku.dex"
        private val PLUS_NAME = "plus"
        private val SU_NAME = "su"
    }

    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult

            val cr = contentResolver
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val child =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

            cr.query(
                child,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name == SH_NAME || name == DEX_NAME || name == PLUS_NAME || name == SU_NAME) {
                        DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }

            fun writeToDocument(name: String): Boolean {
                val documentUri = DocumentsContract.createDocument(contentResolver, doc, "application/octet-stream", name)
                if (documentUri == null) {
                    Timber.tag(TAG).e("Failed to create document for $name")
                    return false
                }

                return try {
                    cr.openOutputStream(documentUri)?.use { outputStream ->
                        assets.open(name).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    true
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to write $name to document")
                    try {
                        DocumentsContract.deleteDocument(contentResolver, documentUri)
                    } catch (deleteEx: Exception) {
                        Timber.tag(TAG).e(deleteEx, "Failed to delete incomplete document for $name")
                    }
                    false
                }
            }

            val results = listOf(SH_NAME, DEX_NAME, PLUS_NAME, SU_NAME).map { writeToDocument(it) }
            val successCount = results.count { it }
            val totalCount = results.size

            ShizukuSettings.setExportDirUri(tree.toString())

            val toastMsg = if (successCount == totalCount) {
                getString(R.string.shell_export_success)
            } else if (successCount == 0) {
                getString(R.string.shell_export_failed)
            } else {
                getString(R.string.shell_export_partial, successCount, totalCount)
            }
            android.widget.Toast.makeText(this@ShellTutorialActivity, toastMsg,
                if (successCount == totalCount) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG
            ).show()
        }

    override fun getLayoutId() = R.layout.terminal_tutorial_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = TerminalTutorialActivityBinding.bind(rootView)

        binding.content.apply {
            setInitialPadding(
                initialPaddingLeft,
                initialPaddingTop + (resources.displayMetrics.density * 8).roundToInt(),
                initialPaddingRight,
                initialPaddingBottom
            )
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.apply {
            if (DeviceCompatibility.isMiui()) {
                miui.isVisible = true
            }

            val shName = "<font face=\"monospace\">$SH_NAME</font>"
            val dexName = "<font face=\"monospace\">$DEX_NAME</font>"
            val plusName = "<font face=\"monospace\">$PLUS_NAME</font>"
            val suName = "<font face=\"monospace\">$SU_NAME</font>"

            summary.text = getString(R.string.rish_description, shName)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)

            text1.text = getString(R.string.terminal_tutorial_1)
            summary1.text = "Export the files ($shName, $dexName, $plusName, and $suName) to a new folder."
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)

            text2.text = getString(R.string.terminal_tutorial_2, shName).toHtml()
            command2.text = "cp /sdcard/chosen-folder/* /data/data/terminal.package.name/files"
            summary2.text = "This copies the exported files to your terminal app\'s private storage. You can also add aliases for $shName, $plusName, and $suName in your <font face=\"monospace\">.bashrc</font> or <font face=\"monospace\">.zshrc</font>."
                .toHtml()

            text3.text = getString(R.string.terminal_tutorial_3)
            command3.text = "sh /path/to/$SH_NAME"

            button1.setOnClickListener {
                try {
                    openDocumentsTree.launch(null)
                } catch (e: android.content.ActivityNotFoundException) {
                    Timber.tag(TAG).w("No file picker available on this device: ${e.message}")
                    android.widget.Toast.makeText(this@ShellTutorialActivity, R.string.no_file_picker, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            button2.setOnClickListener { v: View -> CustomTabsHelper.launchUrlOrCopy(v.context, Helps.RISH.get()) }
        }
    }
}
