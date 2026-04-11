package af.shizuku.manager.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.app.AppBarActivity
import af.shizuku.manager.databinding.ActivityLogItemBinding
import af.shizuku.manager.databinding.AppsActivityBinding
import af.shizuku.manager.databinding.AppsAppbarActivityBinding
import af.shizuku.manager.utils.ActivityLogManager
import af.shizuku.manager.utils.ActivityLogRecord
import af.shizuku.manager.utils.EmptyStateView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogActivity : AppBarActivity() {

    private val adapter = LogAdapter()
    private lateinit var emptyStateView: EmptyStateView

    override fun getLayoutId() = R.layout.apps_appbar_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appsBinding = AppsActivityBinding.inflate(layoutInflater, rootView, false)
        setContentView(appsBinding.root)
        
        val appbarBinding = AppsAppbarActivityBinding.bind(rootView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings_activity_log)

        // Hide search/filter
        appbarBinding.searchLayout.visibility = View.GONE
        appbarBinding.filterChipGroup.parent.let {
            if (it is View) it.visibility = View.GONE
        }

        // Setup empty state view
        emptyStateView = appsBinding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_log_24)
        emptyStateView.setTitle(R.string.empty_state_title_activity_log_empty)
        emptyStateView.setDescription(R.string.empty_state_description_activity_log_empty)
        emptyStateView.hideActionButton()

        ViewCompat.setOnApplyWindowInsetsListener(appsBinding.list) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        appsBinding.list.adapter = adapter
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ActivityLogManager.logs.collectLatest { records ->
                    adapter.update(records)
                    val isEmpty = records.isEmpty()
                    emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    appsBinding.list.visibility = if (isEmpty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.settings_activity_log_clear).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setIcon(R.drawable.ic_delete_24dp)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        } else if (item.itemId == 1) {
            ActivityLogManager.clear()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private class LogAdapter : RecyclerView.Adapter<LogViewHolder>() {
        private var items = emptyList<ActivityLogRecord>()

        fun update(newItems: List<ActivityLogRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LogViewHolder.create(parent)
        override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(items[position])
    }

    private class LogViewHolder(private val binding: ActivityLogItemBinding) : RecyclerView.ViewHolder(binding.root) {
        companion object {
            private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            fun create(parent: ViewGroup) = LogViewHolder(ActivityLogItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        fun bind(record: ActivityLogRecord) {
            val context = binding.root.context
            val pm = context.packageManager
            
            try {
                val ai = pm.getApplicationInfo(record.packageName, 0)
                binding.appName.text = ai.loadLabel(pm)
                binding.icon.load(ai.loadIcon(pm)) {
                    crossfade(true)
                }
            } catch (e: Exception) {
                binding.appName.text = record.appName.ifEmpty { record.packageName }
                binding.icon.load(R.drawable.ic_system_icon)
            }
            
            binding.packageName.text = record.packageName
            binding.action.text = record.action
            binding.timestamp.text = dateFormat.format(Date(record.timestamp))
        }
    }
}
