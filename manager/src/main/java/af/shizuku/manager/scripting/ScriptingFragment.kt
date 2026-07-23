package af.shizuku.manager.scripting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import af.shizuku.core.ui.EmptyStateView
import af.shizuku.manager.R
import af.shizuku.manager.databinding.ItemScriptSnippetBinding
import af.shizuku.manager.database.ScriptSnippetManager
import af.shizuku.manager.database.ScriptSnippetRoom

class ScriptingFragment : Fragment() {

    private val adapter = SnippetAdapter(
        onRun = { runSnippet(it) },
        onEdit = { showEditDialog(it) },
        onLongPress = { showDeleteConfirmation(it) }
    )
    private lateinit var emptyStateView: EmptyStateView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = af.shizuku.core.ui.databinding.AppsActivityBinding.inflate(inflater, container, false)

        emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_code_24)
        emptyStateView.setTitle(getString(R.string.scripting_empty_title))
        emptyStateView.setDescription(getString(R.string.scripting_empty_description))
        emptyStateView.setActionText(R.string.scripting_add)
        emptyStateView.showActionButton()
        emptyStateView.setActionClickListener { showEditDialog(null) }

        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        binding.list.adapter = adapter

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_scripting, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_add_snippet) {
                    showEditDialog(null)
                    return true
                }
                return false
            }
        }, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScriptSnippetManager.getAll().collectLatest { snippets ->
                    adapter.submitList(snippets)
                    val isEmpty = snippets.isEmpty()
                    emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.list.visibility = if (isEmpty) View.GONE else View.VISIBLE
                }
            }
        }
        return binding.root
    }

    private fun runSnippet(snippet: ScriptSnippetRoom) {
        val ctx = context ?: return
        Toast.makeText(ctx, getString(R.string.scripting_running, snippet.title), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = ScriptSnippetManager.run(snippet.script)
            if (!isAdded) return@launch
            val output = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout.trim())
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("stderr:\n").append(result.stderr.trim())
                }
                if (isEmpty()) append(getString(R.string.scripting_no_output))
            }
            val heading = getString(R.string.scripting_exit_code, result.exitCode)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(snippet.title)
                .setMessage("$heading\n\n$output")
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.scripting_copy_output) { _, _ ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(snippet.title, output))
                    Toast.makeText(ctx, R.string.scripting_output_copied, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun showEditDialog(existing: ScriptSnippetRoom?) {
        val ctx = context ?: return
        val dp16 = (16 * resources.displayMetrics.density).toInt()

        val titleInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.scripting_title_hint)
            existing?.let { setText(it.title) }
        }
        val scriptInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = android.graphics.Typeface.MONOSPACE
            hint = getString(R.string.scripting_script_hint)
            minLines = 4
            existing?.let { setText(it.script) }
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16 / 2, dp16, 0)
            addView(titleInput)
            addView(scriptInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp16 / 2 })
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.scripting_add else R.string.scripting_edit)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = titleInput.text.toString().trim()
                val script = scriptInput.text.toString().trim()
                if (title.isEmpty() || script.isEmpty()) {
                    Toast.makeText(ctx, R.string.scripting_title_and_script_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    ScriptSnippetManager.save(existing?.id, title, script)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmation(snippet: ScriptSnippetRoom) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.scripting_delete_title)
            .setMessage(getString(R.string.scripting_delete_message, snippet.title))
            .setPositiveButton(R.string.scripting_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    ScriptSnippetManager.delete(snippet)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal class SnippetAdapter(
        private val onRun: (ScriptSnippetRoom) -> Unit,
        private val onEdit: (ScriptSnippetRoom) -> Unit,
        private val onLongPress: (ScriptSnippetRoom) -> Unit
    ) : ListAdapter<ScriptSnippetRoom, SnippetViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SnippetViewHolder.create(parent, onRun, onEdit, onLongPress)
        override fun onBindViewHolder(holder: SnippetViewHolder, position: Int) = holder.bind(getItem(position))

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ScriptSnippetRoom>() {
                override fun areItemsTheSame(a: ScriptSnippetRoom, b: ScriptSnippetRoom) = a.id == b.id
                override fun areContentsTheSame(a: ScriptSnippetRoom, b: ScriptSnippetRoom) = a == b
            }
        }
    }

    internal class SnippetViewHolder(
        private val binding: ItemScriptSnippetBinding,
        private val onRun: (ScriptSnippetRoom) -> Unit,
        private val onEdit: (ScriptSnippetRoom) -> Unit,
        private val onLongPress: (ScriptSnippetRoom) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        companion object {
            fun create(
                parent: ViewGroup,
                onRun: (ScriptSnippetRoom) -> Unit,
                onEdit: (ScriptSnippetRoom) -> Unit,
                onLongPress: (ScriptSnippetRoom) -> Unit
            ) = SnippetViewHolder(
                ItemScriptSnippetBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onRun, onEdit, onLongPress
            )
        }

        fun bind(snippet: ScriptSnippetRoom) {
            binding.title.text = snippet.title
            binding.scriptPreview.text = snippet.script.lineSequence().firstOrNull().orEmpty()
            binding.runButton.setOnClickListener { onRun(snippet) }
            binding.root.setOnClickListener { onEdit(snippet) }
            binding.root.setOnLongClickListener { onLongPress(snippet); true }
        }
    }
}
