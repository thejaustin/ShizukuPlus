package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.utils.HapticUtils
import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.RotateDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.preference.Preference
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppPickerPreference(
    context: Context,
    attrs: AttributeSet?,
) : Preference(context, attrs) {
    enum class PickerFilter { ALL, SELECTED, USER, SYSTEM }

    enum class PickerSortOrder { NAME, SELECTED_FIRST }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private var cachedApps: List<AppItem>? = null
        private val cacheMutex = Mutex()

        suspend fun getApps(
            context: Context,
            forceRefresh: Boolean = false,
        ): List<AppItem> =
            cacheMutex.withLock {
                if (forceRefresh || cachedApps == null) {
                    val pm = context.applicationContext.packageManager
                    cachedApps =
                        withContext(Dispatchers.IO) {
                            pm
                                .getInstalledApplications(PackageManager.GET_META_DATA)
                                .map {
                                    AppItem(
                                        af.shizuku.manager.utils.AppIconCache
                                            .getLabel(context, it),
                                        it.packageName,
                                        it,
                                        false,
                                        (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                                    )
                                }.sortedBy { it.label.lowercase() }
                        }
                }
                cachedApps ?: emptyList()
            }
    }

    init {
        isPersistent = true
    }

    override fun onClick() {
        showAppPicker()
    }

    data class AppItem(
        val label: String,
        val packageName: String,
        val appInfo: ApplicationInfo,
        var isChecked: Boolean,
        val isSystem: Boolean,
    )

    private class AppAdapter(
        private val allItems: List<AppItem>,
        private val onItemChecked: (String, Boolean) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        private var filteredItems = allItems
        private var currentFilter = PickerFilter.ALL
        private var sortOrder = PickerSortOrder.NAME
        private var sortReversed = false
        private var currentQuery = ""

        fun setFilters(
            query: String,
            filter: PickerFilter = currentFilter,
            sort: PickerSortOrder = sortOrder,
            reversed: Boolean = sortReversed,
        ) {
            this.currentQuery = query
            this.currentFilter = filter
            this.sortOrder = sort
            this.sortReversed = reversed
            applyFilters()
        }

        private fun applyFilters() {
            var newItems =
                allItems.filter { item ->
                    val matchesSearch =
                        currentQuery.isEmpty() ||
                            item.label.contains(currentQuery, ignoreCase = true) ||
                            item.packageName.contains(currentQuery, ignoreCase = true)

                    val matchesFilter =
                        when (currentFilter) {
                            PickerFilter.ALL -> true
                            PickerFilter.SELECTED -> item.isChecked
                            PickerFilter.USER -> !item.isSystem
                            PickerFilter.SYSTEM -> item.isSystem
                        }

                    matchesSearch && matchesFilter
                }

            val comparator =
                when (sortOrder) {
                    PickerSortOrder.NAME -> compareBy<AppItem> { it.label.lowercase() }
                    PickerSortOrder.SELECTED_FIRST -> compareBy({ !it.isChecked }, { it.label.lowercase() })
                }
            newItems = if (sortReversed) newItems.sortedWith(comparator.reversed()) else newItems.sortedWith(comparator)

            val oldItems = filteredItems
            val diff =
                DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize() = oldItems.size

                        override fun getNewListSize() = newItems.size

                        override fun areItemsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int,
                        ) =
                            oldItems[oldItemPosition].packageName == newItems[newItemPosition].packageName

                        override fun areContentsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int,
                        ) =
                            oldItems[oldItemPosition].isChecked == newItems[newItemPosition].isChecked &&
                                oldItems[oldItemPosition].label == newItems[newItemPosition].label
                    },
                )
            filteredItems = newItems
            diff.dispatchUpdatesTo(this)
        }

        fun isEmpty() = filteredItems.isEmpty()

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.dialog_app_picker_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val item = filteredItems[position]
            holder.title.text = item.label
            holder.summary.text = item.packageName

            holder.iconJob?.cancel()
            holder.icon.setImageDrawable(null)
            holder.iconJob =
                af.shizuku.manager.utils.AppIconCache.loadIconBitmapAsync(
                    holder.itemView.context,
                    item.appInfo,
                    item.appInfo.uid / 100000,
                    holder.icon,
                )

            holder.checkbox.isChecked = item.isChecked

            holder.itemView.setOnClickListener {
                item.isChecked = !item.isChecked
                holder.checkbox.isChecked = item.isChecked
                HapticUtils.tap(it)
                onItemChecked(item.packageName, item.isChecked)
            }
        }

        override fun getItemCount(): Int = filteredItems.size

        class ViewHolder(
            view: View,
        ) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val summary: TextView = view.findViewById(R.id.summary)
            val icon: ImageView = view.findViewById(R.id.icon)
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            var iconJob: Job? = null
        }
    }

    private fun showAppPicker() {
        val layoutInflater = LayoutInflater.from(context)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.list)
        val searchView = dialogView.findViewById<SearchView>(R.id.search_view)
        val progressBar = dialogView.findViewById<View>(R.id.progress)
        val titleView = dialogView.findViewById<TextView>(R.id.dialog_title)
        val counterView = dialogView.findViewById<TextView>(R.id.selection_count)
        val emptyView = dialogView.findViewById<View>(R.id.empty_text)
        val btnClearAll = dialogView.findViewById<MaterialButton>(R.id.btn_clear_all)
        val chipGroup = dialogView.findViewById<ChipGroup>(R.id.picker_chip_group)
        val chipSort = dialogView.findViewById<Chip>(R.id.chip_sort)

        titleView.text = title
        searchView.isIconified = false
        searchView.queryHint = context.getString(R.string.app_management_search_hint)

        recyclerView.layoutManager = LinearLayoutManager(context)

        scope.launch {
            val currentPackages = getPersistedString("").split(",").filter { it.isNotBlank() }.toSet()
            val selectedPackages = currentPackages.toMutableSet()
            var currentFilter = PickerFilter.ALL
            var sortOrder = PickerSortOrder.NAME
            var sortReversed = false

            val updateCounter = {
                counterView.text =
                    context.resources.getQuantityString(
                        R.plurals.settings_shadow_binder_hidden_packages_count,
                        selectedPackages.size,
                        selectedPackages.size,
                    )
                btnClearAll.visibility = if (selectedPackages.isNotEmpty()) View.VISIBLE else View.GONE
            }
            updateCounter()

            if (cachedApps == null) progressBar.visibility = View.VISIBLE
            val apps = getApps(context)
            apps.forEach { it.isChecked = selectedPackages.contains(it.packageName) }

            progressBar.visibility = View.GONE
            val adapter =
                AppAdapter(apps) { pkg, isChecked ->
                    if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                    updateCounter()
                }
            recyclerView.adapter = adapter
            adapter.setFilters("", currentFilter, sortOrder, sortReversed)

            btnClearAll.setOnClickListener {
                HapticUtils.success(it)
                selectedPackages.clear()
                apps.forEach { it.isChecked = false }
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                updateCounter()
            }

            chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
                if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                HapticUtils.segmentTick(group)
                currentFilter =
                    when (checkedIds.first()) {
                        R.id.chip_filter_all -> PickerFilter.ALL
                        R.id.chip_filter_selected -> PickerFilter.SELECTED
                        R.id.chip_filter_user -> PickerFilter.USER
                        R.id.chip_filter_system -> PickerFilter.SYSTEM
                        else -> PickerFilter.ALL
                    }
                adapter.setFilters(searchView.query.toString(), currentFilter, sortOrder, sortReversed)
                emptyView.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
            }

            // Static sort chip
            val arrow = ContextCompat.getDrawable(context, R.drawable.ic_outline_arrow_downward_24)?.mutate()
            val rotateDrawable =
                if (arrow != null) {
                    RotateDrawable().apply {
                        drawable = arrow
                        fromDegrees = 0f
                        toDegrees = 360f
                        level = if (sortReversed) 5000 else 0
                    }
                } else {
                    null
                }
            chipSort.chipIcon = rotateDrawable

            fun updateSortUi() {
                chipSort.text =
                    when (sortOrder) {
                        PickerSortOrder.NAME -> context.getString(R.string.sort_order_name)
                        PickerSortOrder.SELECTED_FIRST -> context.getString(R.string.sort_order_selected)
                    }
                androidx.appcompat.widget.TooltipCompat
                    .setTooltipText(chipSort, chipSort.text)
                rotateDrawable?.let { rd ->
                    val targetLevel = if (sortReversed) 5000 else 0
                    val anim = ObjectAnimator.ofInt(rd, "level", rd.level, targetLevel)
                    anim.duration = 200L
                    anim.interpolator = FastOutSlowInInterpolator()
                    anim.start()
                }
            }
            updateSortUi()

            chipSort.setOnClickListener {
                HapticUtils.tick(it)
                val popup = PopupMenu(context, chipSort)
                popup.menu.add(0, 1, 0, context.getString(R.string.sort_order_name)).apply {
                    isCheckable = true
                    isChecked = sortOrder == PickerSortOrder.NAME
                }
                popup.menu.add(0, 2, 1, context.getString(R.string.sort_order_selected)).apply {
                    isCheckable = true
                    isChecked = sortOrder == PickerSortOrder.SELECTED_FIRST
                }
                popup.setOnMenuItemClickListener { item ->
                    val chosen = if (item.itemId == 2) PickerSortOrder.SELECTED_FIRST else PickerSortOrder.NAME
                    if (chosen == sortOrder) {
                        sortReversed = !sortReversed
                    } else {
                        sortOrder = chosen
                        sortReversed = false
                    }
                    updateSortUi()
                    adapter.setFilters(searchView.query.toString(), currentFilter, sortOrder, sortReversed)
                    emptyView.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
                    true
                }
                popup.show()
            }

            var searchJob: Job? = null
            searchView.setOnQueryTextListener(
                object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean = false

                    override fun onQueryTextChange(newText: String?): Boolean {
                        searchJob?.cancel()
                        searchJob =
                            scope.launch {
                                delay(200)
                                adapter.setFilters(newText ?: "", currentFilter, sortOrder, sortReversed)
                                emptyView.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
                            }
                        return true
                    }
                },
            )

            MaterialAlertDialogBuilder(context)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val result = selectedPackages.joinToString(",")
                    if (callChangeListener(result)) {
                        persistString(result)
                        updateSummary(result)
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateSummary(value: String) {
        val packages = value.split(",").filter { it.isNotBlank() }
        setCustomSummary(
            if (packages.isEmpty()) {
                context.getString(R.string.settings_shadow_binder_hidden_packages_summary)
            } else {
                // The preference's own scope, cancelled when it's detached.
                scope.launch {
                    val apps = getApps(context)
                    val selectedNames =
                        packages.mapNotNull { pkg ->
                            apps.find { it.packageName == pkg }?.label
                        }

                    val limit = 3
                    val displayNames = selectedNames.take(limit).joinToString(", ")
                    val remaining = selectedNames.size - limit

                    setCustomSummary(
                        if (remaining > 0) {
                            context.getString(R.string.app_picker_names_and_more, displayNames, remaining)
                        } else {
                            displayNames
                        },
                    )
                }
                // Temporary summary while loading names
                context.resources.getQuantityString(R.plurals.settings_shadow_binder_hidden_packages_count, packages.size, packages.size)
            },
        )
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val value = getPersistedString(defaultValue as? String ?: "")
        updateSummary(value)
    }

    override fun onDetached() {
        super.onDetached()
        scope.cancel()
    }
}
