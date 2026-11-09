package af.shizuku.manager.backup

import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.ActivityAppBackupBinding
import af.shizuku.manager.ktx.overrideAppCloseTransition
import af.shizuku.manager.utils.HapticUtils
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppBackupActivity : AppBarActivity() {
    enum class BackupFilter { ALL, USER, SYSTEM, FROZEN }

    enum class BackupSortOrder { NAME, PACKAGE, FROZEN_FIRST }

    private val viewModel: BackupViewModel by viewModels()
    private lateinit var binding: ActivityAppBackupBinding
    private lateinit var adapter: BackupAdapter
    private var includeSystem = true
    private var backupAllItem: MenuItem? = null

    private var currentFilter = BackupFilter.ALL
    private var sortOrder = BackupSortOrder.NAME
    private var sortReversed = false
    private var searchQuery = ""
    private var rawApps: List<BackupViewModel.AppEntry> = emptyList()
    private var sortArrow: android.graphics.drawable.RotateDrawable? = null

    private var pendingRestoreEntry: BackupViewModel.AppEntry? = null
    private val restoreFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val entry = pendingRestoreEntry ?: return@registerForActivityResult
            pendingRestoreEntry = null
            if (uri != null) viewModel.restoreExternalData(entry, uri)
        }

    override fun finish() {
        super.finish()
        overrideAppCloseTransition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAppBackupBinding.inflate(layoutInflater, rootView, false)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.home_backup_title)
        }

        adapter = BackupAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.clipToPadding = false
        val navClearance = (160 * resources.displayMetrics.density + 0.5f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            binding.recyclerView.setPadding(
                binding.recyclerView.paddingLeft,
                binding.recyclerView.paddingTop,
                binding.recyclerView.paddingRight,
                systemBars.bottom + navClearance,
            )
            insets
        }

        adapter.onBackupClick = { entry ->
            val safUri = getSafUri()
            if (safUri != null) {
                viewModel.backupAppData(entry, safTreeUri = safUri)
            } else {
                viewModel.backupAppData(entry, outputDir = getExternalFilesDir(null) ?: filesDir)
            }
        }
        adapter.onRestoreClick = { entry ->
            pendingRestoreEntry = entry
            restoreFilePicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        adapter.onFreezeClick = { entry ->
            viewModel.toggleFreeze(entry)
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
                            delay(150)
                            searchQuery = s?.toString()?.trim() ?: ""
                            applyFilterAndSort()
                        }
                }
            },
        )

        binding.filterChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            HapticUtils.segmentTick(group)
            currentFilter =
                when (checkedIds.first()) {
                    R.id.chip_filter_all -> BackupFilter.ALL
                    R.id.chip_filter_user -> BackupFilter.USER
                    R.id.chip_filter_system -> BackupFilter.SYSTEM
                    R.id.chip_filter_frozen -> BackupFilter.FROZEN
                    else -> BackupFilter.ALL
                }
            applyFilterAndSort()
        }

        setUpSortChip(binding.chipSort)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is BackupViewModel.UiState.Loading -> showLoading()
                        is BackupViewModel.UiState.Loaded -> {
                            showContent()
                            rawApps = state.apps
                            applyFilterAndSort()
                        }
                        is BackupViewModel.UiState.Error -> showError(state.msg)
                        is BackupViewModel.UiState.ServiceNotRunning ->
                            showError(
                                getString(R.string.home_status_service_not_running, getString(R.string.app_name)),
                            )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.busyPackages.collect { busy ->
                    adapter.setBusy(busy)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is BackupViewModel.BackupEvent.BackupComplete ->
                            Snackbar
                                .make(
                                    rootView,
                                    getString(R.string.backup_app_complete, event.pkg, event.path),
                                    Snackbar.LENGTH_LONG,
                                ).show()
                        is BackupViewModel.BackupEvent.BatchComplete ->
                            Snackbar
                                .make(
                                    rootView,
                                    getString(R.string.backup_batch_complete, event.succeeded, event.failed, event.path),
                                    Snackbar.LENGTH_LONG,
                                ).show()
                        is BackupViewModel.BackupEvent.RestoreComplete ->
                            Snackbar
                                .make(
                                    rootView,
                                    getString(R.string.backup_restore_complete, event.pkg),
                                    Snackbar.LENGTH_LONG,
                                ).show()
                        is BackupViewModel.BackupEvent.FreezeChanged -> {
                            val msg = if (event.nowFrozen) R.string.backup_freeze_success else R.string.backup_unfreeze_success
                            Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
                        }
                        is BackupViewModel.BackupEvent.Failure ->
                            Snackbar.make(rootView, event.msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.batchRunning.collect { running ->
                    backupAllItem?.isEnabled = !running
                }
            }
        }

        viewModel.loadApps(includeSystem)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_backup_menu, menu)
        menu.findItem(R.id.action_show_system)?.isChecked = includeSystem
        backupAllItem = menu.findItem(R.id.action_backup_all)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setQuery(newText.orEmpty())
                    return true
                }
            },
        )
        searchItem?.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem) = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    viewModel.setQuery("")
                    return true
                }
            },
        )

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_backup_all -> {
                val safUri = getSafUri()
                if (safUri != null) {
                    viewModel.backupAll(safTreeUri = safUri)
                } else {
                    viewModel.backupAll(outputDir = getExternalFilesDir(null) ?: filesDir)
                }
                true
            }
            R.id.action_show_system -> {
                includeSystem = !includeSystem
                item.isChecked = includeSystem
                viewModel.loadApps(includeSystem)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun getSafUri(): Uri? =
        ShizukuSettings.getExportDirUri()?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            val hasWritePermission =
                contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isWritePermission
                }
            if (hasWritePermission) uri else null
        }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.errorText.visibility = View.GONE
    }

    private fun showContent() {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE
    }

    private fun setUpSortChip(chip: com.google.android.material.chip.Chip) {
        val arrow =
            androidx.core.content.ContextCompat
                .getDrawable(this, R.drawable.ic_outline_arrow_downward_24)
                ?.mutate()
        val rotateDrawable =
            if (arrow != null) {
                android.graphics.drawable.RotateDrawable().apply {
                    drawable = arrow
                    fromDegrees = 0f
                    toDegrees = 360f
                    level = if (sortReversed) 5000 else 0
                }
            } else {
                null
            }
        sortArrow = rotateDrawable
        chip.chipIcon = rotateDrawable

        fun updateSortUi() {
            chip.text =
                when (sortOrder) {
                    BackupSortOrder.NAME -> getString(R.string.sort_order_name)
                    BackupSortOrder.PACKAGE -> getString(R.string.sort_order_package)
                    BackupSortOrder.FROZEN_FIRST -> getString(R.string.sort_order_frozen)
                }
            androidx.appcompat.widget.TooltipCompat
                .setTooltipText(chip, chip.text)
            rotateDrawable?.let { rd ->
                val targetLevel = if (sortReversed) 5000 else 0
                val anim = android.animation.ObjectAnimator.ofInt(rd, "level", rd.level, targetLevel)
                anim.duration = 200L
                anim.interpolator =
                    androidx.interpolator.view.animation
                        .FastOutSlowInInterpolator()
                anim.start()
            }
        }

        updateSortUi()

        chip.setOnClickListener {
            HapticUtils.tick(it)
            val popup = androidx.appcompat.widget.PopupMenu(this, chip)
            popup.menu.add(0, 1, 0, getString(R.string.sort_order_name)).apply {
                isCheckable = true
                isChecked = sortOrder == BackupSortOrder.NAME
            }
            popup.menu.add(0, 2, 1, getString(R.string.sort_order_package)).apply {
                isCheckable = true
                isChecked = sortOrder == BackupSortOrder.PACKAGE
            }
            popup.menu.add(0, 3, 2, getString(R.string.sort_order_frozen)).apply {
                isCheckable = true
                isChecked = sortOrder == BackupSortOrder.FROZEN_FIRST
            }

            popup.setOnMenuItemClickListener { item ->
                val chosenOrder =
                    when (item.itemId) {
                        1 -> BackupSortOrder.NAME
                        2 -> BackupSortOrder.PACKAGE
                        3 -> BackupSortOrder.FROZEN_FIRST
                        else -> BackupSortOrder.NAME
                    }
                if (chosenOrder == sortOrder) {
                    sortReversed = !sortReversed
                } else {
                    sortOrder = chosenOrder
                    sortReversed = false
                }
                updateSortUi()
                applyFilterAndSort()
                true
            }
            popup.show()
        }
    }

    private fun applyFilterAndSort() {
        var list = rawApps
        if (searchQuery.isNotEmpty()) {
            val q = searchQuery.lowercase()
            list = list.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        list =
            when (currentFilter) {
                BackupFilter.ALL -> list
                BackupFilter.USER -> list.filter { !it.isSystem }
                BackupFilter.SYSTEM -> list.filter { it.isSystem }
                BackupFilter.FROZEN -> list.filter { it.isFrozen }
            }

        val comparator =
            when (sortOrder) {
                BackupSortOrder.NAME -> compareBy<BackupViewModel.AppEntry> { it.label.lowercase() }
                BackupSortOrder.PACKAGE -> compareBy { it.packageName.lowercase() }
                BackupSortOrder.FROZEN_FIRST -> compareBy({ !it.isFrozen }, { it.label.lowercase() })
            }

        list = if (sortReversed) list.sortedWith(comparator.reversed()) else list.sortedWith(comparator)
        adapter.submitList(list)
        binding.errorText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) binding.errorText.text = getString(R.string.app_management_empty)
    }

    private fun showError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = msg
    }
}
