package af.shizuku.manager.activitylog
import af.shizuku.core.ui.EmptyStateView
import af.shizuku.manager.R
import af.shizuku.manager.database.ActivityEventType
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.ActivityLogRecord
import af.shizuku.manager.databinding.ItemActivityLogBinding
import af.shizuku.manager.utils.AppIconCache
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

class ActivityLogFragment : Fragment() {
    private val adapter = LogAdapter()
    private lateinit var emptyStateView: EmptyStateView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding =
            af.shizuku.core.ui.databinding.AppsActivityBinding
                .inflate(inflater, container, false)

        emptyStateView = binding.emptyStateView
        // ic_empty_log_24 is a generic document glyph unrelated to "activity log" as a concept;
        // ic_history_24 is the same icon already used to represent this feature elsewhere
        // (Settings > Advanced, Settings > About), so the empty state now matches.
        emptyStateView.setIcon(R.drawable.ic_history_24)
        emptyStateView.setTitle(getString(R.string.empty_state_title_activity_log_empty))
        emptyStateView.setDescription(getString(R.string.empty_state_description_activity_log_empty))
        emptyStateView.hideActionButton()

        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            val navBarClearancePx = (72 * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left, view.paddingTop, bars.right, bars.bottom + navBarClearancePx)
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

    internal class LogAdapter : ListAdapter<ActivityLogRecord, LogViewHolder>(DIFF) {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ) = LogViewHolder.create(parent)

        override fun onBindViewHolder(
            holder: LogViewHolder,
            position: Int,
        ) = holder.bind(getItem(position))

        companion object {
            private val DIFF =
                object : DiffUtil.ItemCallback<ActivityLogRecord>() {
                    override fun areItemsTheSame(
                        a: ActivityLogRecord,
                        b: ActivityLogRecord,
                    ) =
                        a.timestamp == b.timestamp && a.packageName == b.packageName

                    override fun areContentsTheSame(
                        a: ActivityLogRecord,
                        b: ActivityLogRecord,
                    ) = a == b
                }
        }
    }

    internal class LogViewHolder(
        private val binding: ItemActivityLogBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        companion object {
            // Include the date, not just the time: logs persist across days and a bare "HH:mm:ss"
            // makes yesterday's entry indistinguishable from today's. MEDIUM date keeps a 4-digit
            // year; SHORT time keeps the row compact. Locale-aware.
            private val dateFormat =
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT,
                    Locale.getDefault(),
                )

            fun create(parent: ViewGroup) = LogViewHolder(ItemActivityLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            private fun resolveAttrColor(view: View, attr: Int): Int {
                val tv = android.util.TypedValue()
                view.context.theme.resolveAttribute(attr, tv, true)
                return tv.data
            }
        }

        private var lookupJob: Job? = null

        fun bind(record: ActivityLogRecord) {
            val context = binding.root.context
            val pm = context.packageManager
            val capturedPackage = record.packageName

            binding.packageName.text = record.packageName
            binding.action.text = record.action
            binding.timestamp.text = dateFormat.format(Date(record.timestamp))

            // Fallback shown immediately; replaced once the (cached, off-main-thread) lookup below
            // resolves. pm.getApplicationInfo() is a real IPC and loadIcon() a synchronous decode -
            // doing both inline on bind (as this used to) stalls the UI thread on every scroll, and
            // handing an already-decoded Drawable to Coil defeats its own async/caching pipeline.
            // AppIconCache (used elsewhere for the same lookup) avoids both problems.
            binding.appName.text = record.appName.ifEmpty { record.packageName }
            binding.icon.setTag(R.id.tag_app_icon_package, null)
            binding.icon.load(R.drawable.ic_system_icon)

            applyEventType(record.eventType)
            binding.root.contentDescription =
                "${binding.appName.text}, ${binding.eventChip.text}, ${record.action}, ${binding.timestamp.text}"

            lookupJob?.cancel()
            lookupJob =
                CoroutineScope(Dispatchers.IO).launch {
                    val ai =
                        try {
                            pm.getApplicationInfo(capturedPackage, 0)
                        } catch (_: Exception) {
                            null
                        } ?: return@launch
                    val label = AppIconCache.getLabel(context, ai)
                    withContext(Dispatchers.Main) {
                        if (binding.packageName.text == capturedPackage) {
                            binding.appName.text = label
                            AppIconCache.loadIconBitmapAsync(context, ai, ai.uid / 100000, binding.icon)
                        }
                    }
                }
        }

        private fun applyEventType(eventType: ActivityEventType) {
            val ctx = binding.root.context
            val (labelRes, colorAttr) = when (eventType) {
                ActivityEventType.SERVICE_START -> R.string.activity_log_type_start to androidx.appcompat.R.attr.colorPrimary
                ActivityEventType.SERVICE_STOP -> R.string.activity_log_type_stop to androidx.appcompat.R.attr.colorError
                ActivityEventType.PERMISSION -> R.string.activity_log_type_permission to com.google.android.material.R.attr.colorTertiary
                ActivityEventType.APP_MANAGEMENT -> R.string.activity_log_type_app_mgmt to com.google.android.material.R.attr.colorSecondary
                ActivityEventType.WATCHDOG -> R.string.activity_log_type_watchdog to com.google.android.material.R.attr.colorTertiary
                ActivityEventType.SYSTEM -> R.string.activity_log_type_system to com.google.android.material.R.attr.colorOutline
                ActivityEventType.OTHER -> R.string.activity_log_type_other to com.google.android.material.R.attr.colorOutline
            }
            val accentColor = resolveAttrColor(binding.root, colorAttr)
            binding.eventChip.setText(labelRes)
            binding.eventChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(accentColor, 0x28)
            )
            binding.eventChip.setTextColor(accentColor)
            binding.eventIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
    }
}
