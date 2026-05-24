package af.shizuku.manager.activitylog
import af.shizuku.manager.R

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import af.shizuku.core.ui.EmptyStateView
import af.shizuku.manager.databinding.ItemActivityLogBinding
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.ActivityLogRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogFragment : Fragment() {

    private val adapter = LogAdapter()
    private lateinit var emptyStateView: EmptyStateView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = af.shizuku.core.ui.databinding.AppsActivityBinding.inflate(inflater, container, false)
        
        emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_log_24)
        emptyStateView.setTitle(R.string.empty_state_title_activity_log_empty)
        emptyStateView.setDescription(R.string.empty_state_description_activity_log_empty)
        emptyStateView.hideActionButton()

        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        binding.list.adapter = adapter
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ActivityLogManager.logs.collectLatest { records ->
                    adapter.submitList(records)
                    val isEmpty = records.isEmpty()
                    emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.list.visibility = if (isEmpty) View.GONE else View.VISIBLE
                }
            }
        }
        return binding.root
    }

    // Retain original LogAdapter/LogViewHolder implementation ...
    class LogAdapter : ListAdapter<ActivityLogRecord, LogViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LogViewHolder.create(parent)
        override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(getItem(position))

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ActivityLogRecord>() {
                override fun areItemsTheSame(a: ActivityLogRecord, b: ActivityLogRecord) =
                    a.timestamp == b.timestamp && a.packageName == b.packageName
                override fun areContentsTheSame(a: ActivityLogRecord, b: ActivityLogRecord) = a == b
            }
        }
    }

    internal class LogViewHolder(private val binding: ItemActivityLogBinding) : RecyclerView.ViewHolder(binding.root) {
        companion object {
            private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            fun create(parent: ViewGroup) = LogViewHolder(ItemActivityLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
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
