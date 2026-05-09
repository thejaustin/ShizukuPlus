package af.shizuku.manager.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.utils.HapticUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppPickerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private var cachedApps: List<AppItem>? = null
        private val cacheMutex = Mutex()

        suspend fun getApps(context: Context, forceRefresh: Boolean = false): List<AppItem> = cacheMutex.withLock {
            if (forceRefresh || cachedApps == null) {
                val pm = context.applicationContext.packageManager
                cachedApps = withContext(Dispatchers.IO) {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .map { 
                            AppItem(
                                it.loadLabel(pm).toString(),
                                it.packageName,
                                it.loadIcon(pm),
                                false,
                                (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                }
            }
            cachedApps!!
        }

        fun invalidateCache() {
            cachedApps = null
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
        val icon: Drawable,
        var isChecked: Boolean,
        val isSystem: Boolean
    )

    private class AppAdapter(
        private val allItems: List<AppItem>,
        private val onItemChecked: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var filteredItems = allItems
        private var showSystem = false
        private var currentQuery = ""

        fun setFilters(query: String, showSystem: Boolean) {
            this.currentQuery = query
            this.showSystem = showSystem
            applyFilters()
        }

        private fun applyFilters() {
            filteredItems = allItems.filter { item ->
                val matchesSearch = currentQuery.isEmpty() || 
                    item.label.contains(currentQuery, ignoreCase = true) || 
                    item.packageName.contains(currentQuery, ignoreCase = true)
                
                val matchesSystem = showSystem || !item.isSystem
                
                matchesSearch && matchesSystem
            }
            notifyDataSetChanged()
        }

        fun isEmpty() = filteredItems.isEmpty()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.dialog_app_picker_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = filteredItems[position]
            holder.title.text = item.label
            holder.summary.text = item.packageName
            holder.icon.setImageDrawable(item.icon)
            holder.checkbox.isChecked = item.isChecked

            holder.itemView.setOnClickListener {
                item.isChecked = !item.isChecked
                holder.checkbox.isChecked = item.isChecked
                HapticUtils.tap(it)
                onItemChecked(item.packageName, item.isChecked)
            }
        }

        override fun getItemCount(): Int = filteredItems.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val summary: TextView = view.findViewById(R.id.summary)
            val icon: ImageView = view.findViewById(R.id.icon)
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
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
        val btnFilterSystem = dialogView.findViewById<MaterialButton>(R.id.btn_filter_system)
        
        titleView.text = title
        searchView.isIconified = false
        searchView.queryHint = context.getString(R.string.app_management_search_hint)

        recyclerView.layoutManager = LinearLayoutManager(context)
        
        scope.launch {
            val currentPackages = getPersistedString("").split(",").filter { it.isNotBlank() }.toSet()
            val selectedPackages = currentPackages.toMutableSet()
            var showSystemApps = false

            val updateCounter = {
                counterView.text = context.resources.getQuantityString(
                    R.plurals.settings_shadow_binder_hidden_packages_count,
                    selectedPackages.size,
                    selectedPackages.size
                )
                btnClearAll.visibility = if (selectedPackages.isNotEmpty()) View.VISIBLE else View.GONE
            }
            updateCounter()

            if (cachedApps == null) progressBar.visibility = View.VISIBLE
            val apps = getApps(context)
            apps.forEach { it.isChecked = selectedPackages.contains(it.packageName) }

            progressBar.visibility = View.GONE
            val adapter = AppAdapter(apps) { pkg, isChecked ->
                if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                updateCounter()
            }
            recyclerView.adapter = adapter
            adapter.setFilters("", false)

            btnClearAll.setOnClickListener {
                HapticUtils.success(it)
                selectedPackages.clear()
                apps.forEach { it.isChecked = false }
                adapter.notifyDataSetChanged()
                updateCounter()
            }

            btnFilterSystem.setOnClickListener {
                showSystemApps = !showSystemApps
                HapticUtils.tick(it)
                btnFilterSystem.setIconTintResource(if (showSystemApps) R.color.color_primary else R.color.color_on_surface_variant)
                adapter.setFilters(searchView.query.toString(), showSystemApps)
            }

            var searchJob: Job? = null
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    searchJob?.cancel()
                    searchJob = scope.launch {
                        delay(200)
                        adapter.setFilters(newText ?: "", showSystemApps)
                        emptyView.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
                    }
                    return true
                }
            })

            MaterialAlertDialogBuilder(context)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val result = selectedPackages.joinToString(",")
                    if (callChangeListener(result)) {
                        persistString(result)
                        updateSummary(result)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateSummary(value: String) {
        val packages = value.split(",").filter { it.isNotBlank() }
        summary = if (packages.isEmpty()) {
            context.getString(R.string.settings_shadow_binder_hidden_packages_summary)
        } else {
            val scope = CoroutineScope(Dispatchers.Main)
            scope.launch {
                val apps = getApps(context)
                val selectedNames = packages.mapNotNull { pkg ->
                    apps.find { it.packageName == pkg }?.label
                }
                
                val limit = 3
                val displayNames = selectedNames.take(limit).joinToString(", ")
                val remaining = selectedNames.size - limit
                
                summary = if (remaining > 0) {
                    "$displayNames +$remaining more"
                } else {
                    displayNames
                }
            }
            // Temporary summary while loading names
            context.resources.getQuantityString(R.plurals.settings_shadow_binder_hidden_packages_count, packages.size, packages.size)
        }
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
