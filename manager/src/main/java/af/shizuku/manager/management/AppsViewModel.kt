package af.shizuku.manager.management

import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.lifecycle.Resource
import java.util.concurrent.atomic.AtomicInteger

/** List order; NAME_ASC keeps its old name so saved choices still load. */
enum class SortOrder { NAME_ASC, LAST_INSTALLED, LAST_UPDATED, GRANTED_FIRST }

enum class FilterState { ALL, GRANTED, DENIED, HIDDEN }

class AppsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>().applicationContext
    private val prefs = appContext.getSharedPreferences("app_management_prefs", Context.MODE_PRIVATE)

    private var rawPackages: List<PackageInfo> = emptyList()

    // Monotonic token so overlapping load() passes don't clobber each other (see load()).
    private val loadGeneration = AtomicInteger(0)
    val hiddenPackages: MutableSet<String> =
        (prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toMutableSet()

    var sortOrder: SortOrder =
        runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null) ?: SortOrder.NAME_ASC.name) }
            .getOrDefault(SortOrder.NAME_ASC)
        private set

    /** The chosen order runs the other way (Z–A, oldest first, not granted first). */
    var sortReversed: Boolean = prefs.getBoolean(KEY_SORT_REVERSED, false)
        private set

    var filterState: FilterState = FilterState.ALL
        private set

    var searchQuery: String = ""
        private set

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount =
        MutableLiveData<Resource<Int>>().apply {
            val cached =
                af.shizuku.manager.ShizukuSettings
                    .getLastGrantedAppCount()
            if (cached >= 0) {
                value = Resource.success(cached)
            }
        }
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    private val _hiddenCount = MutableLiveData<Int>(hiddenPackages.size)

    /** Picks [order]; picking the current order again reverses it. */
    fun setSortOrder(order: SortOrder) {
        if (order == sortOrder) {
            sortReversed = !sortReversed
        } else {
            sortOrder = order
            sortReversed = false
        }
        prefs
            .edit()
            .putString(KEY_SORT, sortOrder.name)
            .putBoolean(KEY_SORT_REVERSED, sortReversed)
            .apply()
        applyFiltersAndSort()
    }

    fun setSortReversed(reversed: Boolean) {
        if (reversed == sortReversed) return
        sortReversed = reversed
        prefs.edit().putBoolean(KEY_SORT_REVERSED, reversed).apply()
        applyFiltersAndSort()
    }

    fun setFilter(state: FilterState) {
        filterState = state
        applyFiltersAndSort()
    }

    fun setSearch(query: String) {
        searchQuery = query
        applyFiltersAndSort()
    }

    fun hidePackage(packageName: String) {
        hiddenPackages.add(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenPackages.toSet()).apply()
        _hiddenCount.value = hiddenPackages.size
        applyFiltersAndSort()
    }

    fun unhidePackage(packageName: String) {
        hiddenPackages.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenPackages.toSet()).apply()
        _hiddenCount.value = hiddenPackages.size
        applyFiltersAndSort()
    }

    fun refresh() {
        load()
    }

    // Set while a load is parked until the binder arrives (see load()).
    private val waitingForBinder =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @Volatile
    private var parkedOnlyCount = true

    private val binderArrived: rikka.shizuku.Shizuku.OnBinderReceivedListener =
        object : rikka.shizuku.Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                rikka.shizuku.Shizuku.removeBinderReceivedListener(this)
                waitingForBinder.set(false)
                load(parkedOnlyCount)
            }
        }

    override fun onCleared() {
        rikka.shizuku.Shizuku.removeBinderReceivedListener(binderArrived)
        super.onCleared()
    }

    fun load(onlyCount: Boolean = false) {
        val myGeneration = loadGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // On a cold start the state machine already says RUNNING (restored from the last
                // run) before the binder has arrived. Counting now makes every granted() check fail
                // and shows - and caches - "0 authorized apps" until the next load. Count once the
                // binder is here instead; the cached count stays on screen meanwhile.
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    parkedOnlyCount = parkedOnlyCount && onlyCount
                    if (waitingForBinder.compareAndSet(false, true)) {
                        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderArrived)
                    }
                    return@launch
                }
                parkedOnlyCount = true
                val allPackages = AuthorizationManager.getPackages()

                // getPackages() silently swallows IPC exceptions and returns [] — if the list is
                // empty while Shizuku is running AND we previously had a non-zero count, treat
                // it as a transient IPC failure and skip posting rather than overwriting a valid
                // count with 0 (#424). Fresh installs (previous count null or 0) post normally.
                if (allPackages.isEmpty() &&
                    ShizukuStateMachine.isRunning() &&
                    (_grantedCount.value?.data ?: 0) > 0
                ) {
                    return@launch
                }

                var granted = 0
                for (pi in allPackages) {
                    val appInfo = pi.applicationInfo ?: continue
                    if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) granted++
                }

                // A newer load() started after this pass began, so its data is fresher — don't let
                // this (possibly stale) pass overwrite it. Overlapping loads are routine here: the
                // RUNNING state listener and onResume() both call load(), so without this guard an
                // earlier pass that computed 0 (server not ready yet, or a just-applied grant not
                // yet propagated) could post AFTER the correct count and wrongly show "0 authorized
                // apps" on the home screen until the list is reopened (issue #317).
                if (myGeneration != loadGeneration.get()) return@launch
                // The binder dropped mid-count: the zeros are lookup failures, not revoked grants.
                if (!rikka.shizuku.Shizuku.pingBinder()) return@launch

                rawPackages = allPackages
                if (!onlyCount || filterState != FilterState.ALL) {
                    applyFiltersAndSort()
                }
                af.shizuku.manager.ShizukuSettings
                    .setLastGrantedAppCount(granted)
                _grantedCount.postValue(Resource.success(granted))
                _hiddenCount.postValue(hiddenPackages.size)
            } catch (_: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                _packages.postValue(Resource.error(e, null))
                _grantedCount.postValue(Resource.error(e, _grantedCount.value?.data))
            }
        }
    }

    private fun applyFiltersAndSort() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = appContext.packageManager
                // Looked up once per pass: loadLabel and the binder grant check were repeated for
                // every comparison of the sort.
                val labels = HashMap<String, String>(rawPackages.size)

                fun label(pi: PackageInfo): String =
                    labels.getOrPut(pi.packageName) {
                        pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
                    }
                val grants = HashMap<String, Boolean>()

                fun granted(pi: PackageInfo): Boolean =
                    grants.getOrPut(pi.packageName) {
                        val appInfo = pi.applicationInfo ?: return@getOrPut false
                        runCatching { AuthorizationManager.granted(pi.packageName, appInfo.uid) }.getOrDefault(false)
                    }
                val filtered =
                    rawPackages.filter { pi ->
                        if (filterState == FilterState.HIDDEN) {
                            if (pi.packageName !in hiddenPackages) return@filter false
                        } else {
                            if (pi.packageName in hiddenPackages) return@filter false
                        }
                        val matchesSearch =
                            searchQuery.isBlank() ||
                                label(pi).contains(searchQuery, ignoreCase = true) ||
                                pi.packageName.contains(searchQuery, ignoreCase = true)
                        val matchesFilter =
                            when (filterState) {
                                FilterState.ALL, FilterState.HIDDEN -> true
                                FilterState.GRANTED -> granted(pi)
                                // Not granted counts as denied/pending in this view.
                                FilterState.DENIED -> !granted(pi)
                            }
                        matchesSearch && matchesFilter
                    }
                val byName = compareBy<PackageInfo> { label(it).lowercase() }
                val comparator =
                    when (sortOrder) {
                        SortOrder.NAME_ASC -> byName
                        SortOrder.LAST_INSTALLED -> compareByDescending<PackageInfo> { it.firstInstallTime }
                        SortOrder.LAST_UPDATED -> compareByDescending<PackageInfo> { it.lastUpdateTime }
                        // Granted apps first, alphabetical within each group.
                        SortOrder.GRANTED_FIRST -> compareByDescending<PackageInfo> { granted(it) }.then(byName)
                    }
                val list = filtered.sortedWith(if (sortReversed) comparator.reversed() else comparator)
                _packages.postValue(Resource.success(list))
            } catch (e: Throwable) {
                _packages.postValue(Resource.error(e, null))
            }
        }
    }

    companion object {
        private const val KEY_HIDDEN = "hidden_packages"
        private const val KEY_SORT = "app_list_sort_order"
        private const val KEY_SORT_REVERSED = "app_list_sort_reversed"
    }
}
