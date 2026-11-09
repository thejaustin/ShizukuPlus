package af.shizuku.manager.activitylog
import af.shizuku.core.ui.EmptyStateView
import af.shizuku.manager.R
import af.shizuku.manager.database.ActivityEventType
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.ActivityLogRecord
import af.shizuku.manager.databinding.ItemActivityLogBinding
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.HapticUtils
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    private enum class Sort(
        val label: Int,
    ) {
        NEWEST(R.string.changelog_sort_newest),
        OLDEST(R.string.changelog_sort_oldest),
        BY_APP(R.string.activity_log_sort_by_app),
        BY_TYPE(R.string.activity_log_sort_by_type),
        MOST_ACTIVE(R.string.activity_log_sort_most_active),
    }

    data class LogDisplayGroup(
        val primaryRecord: ActivityLogRecord,
        val stackedRecords: List<ActivityLogRecord>,
        val isFirst: Boolean,
        val isLast: Boolean,
        val isExpanded: Boolean,
    )

    private val adapter = LogAdapter()
    private lateinit var emptyStateView: EmptyStateView
    private var allRecords: List<ActivityLogRecord> = emptyList()
    private val selectedTypes = mutableSetOf<ActivityEventType>()
    private var sort = Sort.NEWEST
    private var stackRepetitive = true
    private val expandedGroupIds = mutableSetOf<String>()
    private var listView: View? = null

    private fun getGroupKey(record: ActivityLogRecord): String =
        "${record.packageName}|${record.eventType}|${record.action}"

    private fun applyFilterSort() {
        val filtered = allRecords.filter { selectedTypes.isEmpty() || it.eventType in selectedTypes }

        val groups: List<LogDisplayGroup> =
            if (stackRepetitive) {
                // Group consecutive or identical events by app + eventType + action within close proximity (or overall)
                val groupedMap = linkedMapOf<String, MutableList<ActivityLogRecord>>()
                for (record in filtered) {
                    val key = getGroupKey(record)
                    groupedMap.getOrPut(key) { mutableListOf() }.add(record)
                }

                val groupList =
                    groupedMap.values.map { list ->
                        val primary = list.maxByOrNull { it.timestamp } ?: list.first()
                        val others = list.filter { it != primary }.sortedByDescending { it.timestamp }
                        val key = getGroupKey(primary)
                        LogDisplayGroup(
                            primaryRecord = primary,
                            stackedRecords = others,
                            isFirst = false,
                            isLast = false,
                            isExpanded = expandedGroupIds.contains(key),
                        )
                    }

                when (sort) {
                    Sort.NEWEST -> groupList.sortedByDescending { it.primaryRecord.timestamp }
                    Sort.OLDEST -> groupList.sortedBy { it.primaryRecord.timestamp }
                    Sort.BY_APP -> groupList.sortedWith(compareBy({ it.primaryRecord.appName.lowercase() }, { -it.primaryRecord.timestamp }))
                    Sort.BY_TYPE -> groupList.sortedWith(compareBy({ it.primaryRecord.eventType.ordinal }, { -it.primaryRecord.timestamp }))
                    Sort.MOST_ACTIVE -> groupList.sortedByDescending { it.stackedRecords.size + 1 }
                }
            } else {
                val sortedRecords =
                    when (sort) {
                        Sort.NEWEST -> filtered.sortedByDescending { it.timestamp }
                        Sort.OLDEST -> filtered.sortedBy { it.timestamp }
                        Sort.BY_APP -> filtered.sortedWith(compareBy({ it.appName.lowercase() }, { -it.timestamp }))
                        Sort.BY_TYPE -> filtered.sortedWith(compareBy({ it.eventType.ordinal }, { -it.timestamp }))
                        Sort.MOST_ACTIVE -> {
                            val counts = filtered.groupingBy { it.packageName }.eachCount()
                            filtered.sortedWith(compareBy({ -(counts[it.packageName] ?: 0) }, { -it.timestamp }))
                        }
                    }
                sortedRecords.map {
                    LogDisplayGroup(
                        primaryRecord = it,
                        stackedRecords = emptyList(),
                        isFirst = false,
                        isLast = false,
                        isExpanded = false,
                    )
                }
            }

        val positionedGroups =
            groups.mapIndexed { index, grp ->
                grp.copy(
                    isFirst = index == 0,
                    isLast = index == groups.size - 1,
                )
            }

        adapter.submitList(positionedGroups)
        val isEmpty = positionedGroups.isEmpty()
        emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        listView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun buildFilterBar(parent: android.widget.LinearLayout) {
        val ctx = parent.context
        val d = resources.displayMetrics.density

        val container =
            android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            }

        val group =
            com.google.android.material.chip.ChipGroup(ctx).apply {
                isSingleLine = true
                chipSpacingHorizontal = (8 * d).toInt()
            }

        val stackChip =
            com.google.android.material.chip.Chip(ctx).apply {
                setText(R.string.activity_log_stack_activities)
                isCheckable = true
                isChecked = stackRepetitive
                setOnClickListener {
                    HapticUtils.segmentTick(this)
                    stackRepetitive = isChecked
                    applyFilterSort()
                }
            }
        group.addView(stackChip)

        ActivityEventType.values().forEach { type ->
            group.addView(
                com.google.android.material.chip.Chip(ctx).apply {
                    setText(typeLabel(type))
                    isCheckable = true
                    setOnClickListener {
                        HapticUtils.segmentTick(this)
                        if (!selectedTypes.remove(type)) selectedTypes.add(type)
                        applyFilterSort()
                    }
                },
            )
        }

        val scroll =
            android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength((16 * d).toInt())
                layoutParams =
                    android.widget.LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                addView(group)
            }
        container.addView(scroll)

        val typedValue = android.util.TypedValue()
        val outlineColor =
            if (ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)) {
                typedValue.data
            } else {
                0x33888888
            }
        val divider =
            View(ctx).apply {
                setBackgroundColor(outlineColor)
                layoutParams =
                    android.widget.LinearLayout
                        .LayoutParams(
                            (1 * d).toInt().coerceAtLeast(1),
                            (24 * d).toInt(),
                        ).apply {
                            marginStart = (8 * d).toInt()
                            marginEnd = (8 * d).toInt()
                        }
            }
        container.addView(divider)

        val sortChip =
            com.google.android.material.chip.Chip(ctx).apply {
                setText(sort.label)
                chipIcon =
                    androidx.core.content.ContextCompat
                        .getDrawable(ctx, R.drawable.ic_outline_expand_more_24)
                isChipIconVisible = true
                layoutParams =
                    android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setOnClickListener { anchor ->
                    HapticUtils.tap(anchor)
                    androidx.appcompat.widget
                        .PopupMenu(ctx, anchor)
                        .apply {
                            Sort.values().forEachIndexed { i, s -> menu.add(0, i, i, s.label) }
                            setOnMenuItemClickListener { item ->
                                sort = Sort.values()[item.itemId]
                                (anchor as? com.google.android.material.chip.Chip)?.setText(sort.label)
                                HapticUtils.segmentTick(anchor)
                                applyFilterSort()
                                true
                            }
                        }.show()
                }
            }
        container.addView(sortChip)

        parent.addView(container, 0)
    }

    private fun typeLabel(type: ActivityEventType) =
        when (type) {
            ActivityEventType.SERVICE_START -> R.string.activity_log_type_start
            ActivityEventType.SERVICE_STOP -> R.string.activity_log_type_stop
            ActivityEventType.CONNECTION -> R.string.activity_log_type_connection
            ActivityEventType.PERMISSION -> R.string.activity_log_type_permission
            ActivityEventType.APP_MANAGEMENT -> R.string.activity_log_type_app_mgmt
            ActivityEventType.WATCHDOG -> R.string.activity_log_type_watchdog
            ActivityEventType.SYSTEM -> R.string.activity_log_type_system
            ActivityEventType.OTHER -> R.string.activity_log_type_other
        }

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
        listView = binding.list
        (binding.root as? android.widget.LinearLayout)?.let { buildFilterBar(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ActivityLogManager.logs.collectLatest { records ->
                    allRecords = records
                    applyFilterSort()
                }
            }
        }
        return binding.root
    }

    internal inner class LogAdapter : ListAdapter<LogDisplayGroup, LogViewHolder>(DIFF) {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ) = LogViewHolder(
            ItemActivityLogBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onToggleExpand = { key ->
                if (expandedGroupIds.contains(key)) {
                    expandedGroupIds.remove(key)
                } else {
                    expandedGroupIds.add(key)
                }
                applyFilterSort()
            },
        )

        override fun onBindViewHolder(
            holder: LogViewHolder,
            position: Int,
        ) = holder.bind(getItem(position))
    }

    companion object {
        private val DIFF =
            object : DiffUtil.ItemCallback<LogDisplayGroup>() {
                override fun areItemsTheSame(
                    a: LogDisplayGroup,
                    b: LogDisplayGroup,
                ) =
                    a.primaryRecord.timestamp == b.primaryRecord.timestamp &&
                        a.primaryRecord.packageName == b.primaryRecord.packageName &&
                        a.primaryRecord.action == b.primaryRecord.action &&
                        a.primaryRecord.eventType == b.primaryRecord.eventType

                override fun areContentsTheSame(
                    a: LogDisplayGroup,
                    b: LogDisplayGroup,
                ) = a == b
            }
    }

    internal class LogViewHolder(
        private val binding: ItemActivityLogBinding,
        private val onToggleExpand: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        companion object {
            private val dateFormat =
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT,
                    Locale.getDefault(),
                )

            private val timeOnlyFormat =
                java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.SHORT,
                    Locale.getDefault(),
                )

            private fun resolveAttrColor(
                view: View,
                attr: Int,
            ): Int {
                val tv = android.util.TypedValue()
                view.context.theme.resolveAttribute(attr, tv, true)
                return tv.data
            }
        }

        private var lookupJob: Job? = null

        fun bind(group: LogDisplayGroup) {
            val record = group.primaryRecord
            val context = binding.root.context
            val pm = context.packageManager
            val capturedPackage = record.packageName

            binding.packageName.text = record.packageName
            binding.action.text = ActivityLogText.localize(context, record.action)
            binding.timestamp.text = dateFormat.format(Date(record.timestamp))

            binding.appName.text = record.appName.ifEmpty { record.packageName }
            binding.icon.setTag(R.id.tag_app_icon_package, null)
            binding.icon.load(R.drawable.ic_system_icon)

            val accentColor = applyEventType(record.eventType)

            // Morphe-inspired timeline rail styling
            val outlineColor = resolveAttrColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
            val haloColor = ColorUtils.setAlphaComponent(accentColor, 0x30)
            binding.rail.setColors(outlineColor, accentColor, haloColor)
            binding.rail.isFirst = group.isFirst
            binding.rail.isLast = group.isLast
            binding.rail.filled = group.isFirst || group.stackedRecords.isNotEmpty()
            binding.rail.setPulsing(group.isFirst)

            // Repetitive Activity Stacking & Badge
            val groupKey = "${record.packageName}|${record.eventType}|${record.action}"
            val totalCount = group.stackedRecords.size + 1
            if (group.stackedRecords.isNotEmpty()) {
                binding.stackChip.visibility = View.VISIBLE
                binding.stackChip.text = context.getString(R.string.activity_log_stacked_count, totalCount)
                binding.stackChip.setOnClickListener {
                    HapticUtils.tap(it)
                    onToggleExpand(groupKey)
                }

                binding.root.setOnClickListener {
                    HapticUtils.tap(it)
                    onToggleExpand(groupKey)
                }

                if (group.isExpanded) {
                    binding.expandedStackContainer.visibility = View.VISIBLE
                    binding.expandedStackContainer.removeAllViews()
                    group.stackedRecords.forEach { stacked ->
                        val subRow =
                            TextView(context).apply {
                                text = "• ${timeOnlyFormat.format(Date(stacked.timestamp))} — ${ActivityLogText.localize(context, stacked.action)}"
                                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                                setTextColor(resolveAttrColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, (2 * resources.displayMetrics.density).toInt())
                            }
                        binding.expandedStackContainer.addView(subRow)
                    }
                } else {
                    binding.expandedStackContainer.visibility = View.GONE
                }
            } else {
                binding.stackChip.visibility = View.GONE
                binding.expandedStackContainer.visibility = View.GONE
                binding.root.setOnClickListener(null)
            }

            binding.root.contentDescription =
                "${binding.appName.text}, ${binding.eventChip.text}, ${binding.action.text}, ${binding.timestamp.text}"

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

        private fun applyEventType(eventType: ActivityEventType): Int {
            val (labelRes, colorAttr) =
                when (eventType) {
                    ActivityEventType.SERVICE_START -> R.string.activity_log_type_start to androidx.appcompat.R.attr.colorPrimary
                    ActivityEventType.SERVICE_STOP -> R.string.activity_log_type_stop to androidx.appcompat.R.attr.colorError
                    ActivityEventType.CONNECTION -> R.string.activity_log_type_connection to com.google.android.material.R.attr.colorSecondary
                    ActivityEventType.PERMISSION -> R.string.activity_log_type_permission to com.google.android.material.R.attr.colorTertiary
                    ActivityEventType.APP_MANAGEMENT -> R.string.activity_log_type_app_mgmt to com.google.android.material.R.attr.colorSecondary
                    ActivityEventType.WATCHDOG -> R.string.activity_log_type_watchdog to com.google.android.material.R.attr.colorTertiary
                    ActivityEventType.SYSTEM -> R.string.activity_log_type_system to com.google.android.material.R.attr.colorOutline
                    ActivityEventType.OTHER -> R.string.activity_log_type_other to com.google.android.material.R.attr.colorOutline
                }
            val accentColor = resolveAttrColor(binding.root, colorAttr)
            binding.eventChip.setText(labelRes)
            binding.eventChip.chipBackgroundColor =
                android.content.res.ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(accentColor, 0x28),
                )
            binding.eventChip.setTextColor(accentColor)
            binding.eventIndicator.backgroundTintList =
                android.content.res.ColorStateList
                    .valueOf(accentColor)
            return accentColor
        }
    }
}
