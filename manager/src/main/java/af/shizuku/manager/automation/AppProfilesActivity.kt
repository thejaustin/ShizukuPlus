package af.shizuku.manager.automation

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.ActivityAppProfilesBinding
import af.shizuku.manager.ktx.overrideAppCloseTransition
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.HapticUtils
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Per-app Binder Firewall automation profiles.
 *
 * Each app can be set to one of three states:
 *  - DEFAULT: no override — the user's global Binder Firewall setting applies
 *  - BLOCK:   binder_firewall=true when this app is in the foreground (restricts Shizuku access)
 *  - ALLOW:   binder_firewall=false when this app is in the foreground (permits Shizuku access)
 *
 * Profiles are stored in the JSON format that [AppSpecificProfileRule] reads, e.g.
 *   {"com.pkg": {"binder_firewall": true}}
 * Apps with DEFAULT state have no entry in the JSON.
 */
class AppProfilesActivity : AppCompatActivity() {
    enum class ProfileState { DEFAULT, BLOCK, ALLOW }

    enum class ProfileFilter { ALL, CONFIGURED, BLOCKED, ALLOWED }

    enum class ProfileSortOrder { CONFIGURED_FIRST, NAME, PACKAGE }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val appInfo: android.content.pm.ApplicationInfo,
        var state: ProfileState,
    )

    private lateinit var binding: ActivityAppProfilesBinding
    private val profiles = mutableMapOf<String, ProfileState>()
    private var allApps: List<AppEntry> = emptyList()
    private var filteredApps: List<AppEntry> = emptyList()
    private var adapter: ProfileAdapter? = null
    private var searchQuery = ""
    private var currentFilter = ProfileFilter.ALL
    private var sortOrder = ProfileSortOrder.CONFIGURED_FIRST
    private var sortReversed = false
    private var sortArrow: android.graphics.drawable.RotateDrawable? = null

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overrideAppCloseTransition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.app_profiles_title)
        }

        profiles.putAll(loadProfilesFromJson())

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.clipToPadding = false
        val navClearance = (160 * resources.displayMetrics.density + 0.5f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            binding.appBar.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                systemBars.bottom + navClearance,
            )
            insets
        }

        // Search bar: M3 Expressive shape morphing (28dp pill -> 16dp squircle) with tick feedback
        val pillRadius = 28f * resources.displayMetrics.density
        val squircleRadius = 16f * resources.displayMetrics.density
        var cornerAnimator: android.animation.ValueAnimator? = null

        fun animateSearchCorners(toRadius: Float) {
            cornerAnimator?.cancel()
            val currentRadius = binding.searchLayout.boxCornerRadiusTopStart
            if (kotlin.math.abs(currentRadius - toRadius) < 0.5f) return
            cornerAnimator =
                android.animation.ValueAnimator.ofFloat(currentRadius, toRadius).apply {
                    duration = 250L
                    interpolator =
                        androidx.interpolator.view.animation
                            .FastOutSlowInInterpolator()
                    addUpdateListener { animator ->
                        val r = animator.animatedValue as Float
                        binding.searchLayout.setBoxCornerRadii(r, r, r, r)
                    }
                    start()
                }
        }

        binding.searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                HapticUtils.tick(binding.searchEditText)
                animateSearchCorners(squircleRadius)
            } else if (binding.searchEditText.text.isNullOrEmpty()) {
                animateSearchCorners(pillRadius)
            }
        }

        // Filter chips setup
        binding.filterChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            HapticUtils.segmentTick(group)
            currentFilter =
                when (checkedIds.first()) {
                    R.id.chip_all -> ProfileFilter.ALL
                    R.id.chip_configured -> ProfileFilter.CONFIGURED
                    R.id.chip_blocked -> ProfileFilter.BLOCKED
                    R.id.chip_allowed -> ProfileFilter.ALLOWED
                    else -> ProfileFilter.ALL
                }
            applyFilterAndSort()
        }

        // Static right-pinned Sort chip setup
        setUpSortChip(binding.chipSort)

        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            allApps = withContext(Dispatchers.IO) { loadApps() }
            binding.progress.visibility = View.GONE
            adapter = ProfileAdapter()
            binding.recycler.adapter = adapter
            applyFilterAndSort()
        }

        var debounceJob: Job? = null
        binding.searchEditText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    debounceJob?.cancel()
                    debounceJob =
                        lifecycleScope.launch {
                            delay(200)
                            searchQuery = s?.toString() ?: ""
                            applyFilterAndSort()
                        }
                }
            },
        )
    }

    private fun setUpSortChip(chip: Chip) {
        sortArrow =
            android.graphics.drawable.RotateDrawable().apply {
                drawable =
                    androidx.core.content.ContextCompat
                        .getDrawable(this@AppProfilesActivity, R.drawable.ic_outline_arrow_downward_24)
                fromDegrees = 0f
                toDegrees = 180f
                level = if (sortReversed) 10000 else 0
            }
        chip.chipIcon = sortArrow
        with(af.shizuku.manager.utils.MotionUtils) { chip.applySpringTouch() }
        val open = { _: View ->
            HapticUtils.tap(chip)
            showSortMenu(chip)
        }
        chip.setOnClickListener(open)
        chip.setOnCloseIconClickListener(open)
        updateSortChip(chip, animate = false)
    }

    private fun showSortMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        val orders =
            listOf(
                ProfileSortOrder.CONFIGURED_FIRST to getString(R.string.app_profile_sort_configured),
                ProfileSortOrder.NAME to getString(R.string.app_profile_sort_name),
                ProfileSortOrder.PACKAGE to getString(R.string.app_profile_sort_package),
            )
        orders.forEachIndexed { i, (order, label) ->
            popup.menu
                .add(1, i, i, label)
                .setCheckable(true)
                .isChecked = order == sortOrder
        }
        popup.menu.setGroupCheckable(1, true, true)
        val reverseItemId = 100
        popup.menu
            .add(2, reverseItemId, orders.size, R.string.app_management_sort_reverse)
            .setCheckable(true)
            .isChecked = sortReversed

        popup.setOnMenuItemClickListener { item ->
            HapticUtils.segmentTick(anchor)
            if (item.itemId == reverseItemId) {
                sortReversed = !sortReversed
                item.isChecked = sortReversed
                updateSortChip(binding.chipSort, animate = true)
                applyFilterAndSort()
                true
            } else if (item.itemId in orders.indices) {
                sortOrder = orders[item.itemId].first
                updateSortChip(binding.chipSort, animate = false)
                applyFilterAndSort()
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun updateSortChip(
        chip: Chip,
        animate: Boolean,
    ) {
        val label =
            when (sortOrder) {
                ProfileSortOrder.CONFIGURED_FIRST -> getString(R.string.app_profile_sort_configured)
                ProfileSortOrder.NAME -> getString(R.string.app_profile_sort_name)
                ProfileSortOrder.PACKAGE -> getString(R.string.app_profile_sort_package)
            }
        chip.text = label
        androidx.appcompat.widget.TooltipCompat
            .setTooltipText(chip, label)

        val targetLevel = if (sortReversed) 10000 else 0
        val arrow = sortArrow ?: return
        if (!animate || arrow.level == targetLevel) {
            arrow.level = targetLevel
            return
        }
        android.animation.ValueAnimator.ofInt(arrow.level, targetLevel).apply {
            duration = 200L
            interpolator =
                androidx.interpolator.view.animation
                    .FastOutSlowInInterpolator()
            addUpdateListener { animator ->
                arrow.level = animator.animatedValue as Int
            }
            start()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        saveProfilesToJson()
        // Auto-start/stop AutomationService based on current rules.
        if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
            AutomationService.startIfNeeded(this)
        } else {
            AutomationService.stopIfRunning(this)
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val self = packageName
        return pm
            .getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .map { info ->
                val label = pm.getApplicationLabel(info).toString()
                val state = profiles[info.packageName] ?: ProfileState.DEFAULT
                AppEntry(label, info.packageName, info, state)
            }.sortedWith(
                compareBy(
                    { it.state == ProfileState.DEFAULT }, // configured apps float to top
                    { it.label.lowercase() },
                ),
            ).toList()
    }

    private fun applyFilterAndSort() {
        var list = allApps

        // 1. Filter by search query
        if (searchQuery.isNotEmpty()) {
            list =
                list.filter {
                    it.label.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                }
        }

        // 2. Filter by chip state
        list =
            when (currentFilter) {
                ProfileFilter.ALL -> list
                ProfileFilter.CONFIGURED -> list.filter { it.state != ProfileState.DEFAULT }
                ProfileFilter.BLOCKED -> list.filter { it.state == ProfileState.BLOCK }
                ProfileFilter.ALLOWED -> list.filter { it.state == ProfileState.ALLOW }
            }

        // 3. Sort
        val comparator =
            when (sortOrder) {
                ProfileSortOrder.CONFIGURED_FIRST ->
                    compareBy<AppEntry>(
                        { it.state == ProfileState.DEFAULT },
                        { it.label.lowercase() },
                    )
                ProfileSortOrder.NAME ->
                    compareBy { it.label.lowercase() }
                ProfileSortOrder.PACKAGE ->
                    compareBy { it.packageName.lowercase() }
            }

        list =
            if (sortReversed) {
                list.sortedWith(comparator.reversed())
            } else {
                list.sortedWith(comparator)
            }

        val old = filteredApps
        filteredApps = list
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter?.let { a ->
            val diff =
                DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize() = old.size

                        override fun getNewListSize() = list.size

                        override fun areItemsTheSame(
                            op: Int,
                            np: Int,
                        ) = old[op].packageName == list[np].packageName

                        override fun areContentsTheSame(
                            op: Int,
                            np: Int,
                        ) = old[op].state == list[np].state && old[op].label == list[np].label
                    },
                )
            diff.dispatchUpdatesTo(a)
        }
    }

    private fun loadProfilesFromJson(): Map<String, ProfileState> {
        val result = mutableMapOf<String, ProfileState>()
        try {
            val json = JSONObject(ShizukuSettings.getAutomationAppProfilesJson())
            json.keys().forEach { pkg ->
                val obj = json.optJSONObject(pkg) ?: return@forEach
                val fw = obj.optBoolean("binder_firewall", false)
                result[pkg] = if (fw) ProfileState.BLOCK else ProfileState.ALLOW
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun saveProfilesToJson() {
        val json = JSONObject()
        profiles.forEach { (pkg, state) ->
            if (state != ProfileState.DEFAULT) {
                json.put(
                    pkg,
                    JSONObject().apply {
                        put("binder_firewall", state == ProfileState.BLOCK)
                    },
                )
            }
        }
        ShizukuSettings.setAutomationAppProfilesJson(json.toString())
    }

    private fun chipColorsForState(state: ProfileState): Pair<Int, Int> =
        when (state) {
            ProfileState.DEFAULT ->
                Pair(
                    themeColor(com.google.android.material.R.attr.colorSurfaceVariant),
                    themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
            ProfileState.BLOCK ->
                Pair(
                    themeColor(com.google.android.material.R.attr.colorErrorContainer),
                    themeColor(com.google.android.material.R.attr.colorOnErrorContainer),
                )
            ProfileState.ALLOW ->
                Pair(
                    themeColor(com.google.android.material.R.attr.colorSecondaryContainer),
                    themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                )
        }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        inner class VH(
            view: View,
        ) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.app_name)
            val pkg: TextView = view.findViewById(R.id.package_name)
            val chip: Chip = view.findViewById(R.id.profile_chip)
            var iconJob: Job? = null
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): VH {
            val view =
                LayoutInflater
                    .from(parent.context)
                    .inflate(R.layout.item_app_profile, parent, false)
            return VH(view)
        }

        override fun getItemCount() = filteredApps.size

        override fun onBindViewHolder(
            holder: VH,
            position: Int,
        ) {
            val app = filteredApps[position]
            holder.name.text = app.label
            holder.pkg.text = app.packageName

            holder.iconJob?.cancel()
            holder.icon.setImageDrawable(null)
            holder.iconJob =
                af.shizuku.manager.utils.AppIconCache.loadIconBitmapAsync(
                    holder.itemView.context,
                    app.appInfo,
                    app.appInfo.uid / 100000,
                    holder.icon,
                )

            bindChip(holder.chip, app)
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            holder.iconJob?.cancel()
        }

        private fun bindChip(
            chip: Chip,
            app: AppEntry,
        ) {
            chip.text =
                when (app.state) {
                    ProfileState.DEFAULT -> chip.context.getString(R.string.app_profile_state_default)
                    ProfileState.BLOCK -> chip.context.getString(R.string.app_profile_state_block)
                    ProfileState.ALLOW -> chip.context.getString(R.string.app_profile_state_allow)
                }
            val (bg, fg) = chipColorsForState(app.state)
            chip.chipBackgroundColor = ColorStateList.valueOf(bg)
            chip.setTextColor(fg)
            val iconRes =
                when (app.state) {
                    ProfileState.DEFAULT -> R.drawable.ic_settings_suggest_24
                    ProfileState.BLOCK -> R.drawable.ic_close_24
                    ProfileState.ALLOW -> R.drawable.ic_server_ok_24
                }
            chip.setChipIconResource(iconRes)
            chip.chipIconTint = ColorStateList.valueOf(fg)
            chip.isChipIconVisible = true

            chip.setOnClickListener {
                val newState =
                    when (app.state) {
                        ProfileState.DEFAULT -> ProfileState.BLOCK
                        ProfileState.BLOCK -> ProfileState.ALLOW
                        ProfileState.ALLOW -> ProfileState.DEFAULT
                    }
                app.state = newState
                if (newState == ProfileState.DEFAULT) {
                    profiles.remove(app.packageName)
                } else {
                    profiles[app.packageName] = newState
                }
                bindChip(chip, app)
                HapticUtils.tick(chip)

                // Re-apply filter and sort
                applyFilterAndSort()
            }
        }
    }
}
