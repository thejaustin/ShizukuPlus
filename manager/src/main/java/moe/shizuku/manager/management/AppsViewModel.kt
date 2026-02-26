package moe.shizuku.manager.management

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
import moe.shizuku.manager.authorization.AuthorizationManager
import rikka.lifecycle.Resource

enum class SortOrder { NAME_ASC, LAST_INSTALLED, LAST_UPDATED }
enum class FilterState { ALL, GRANTED, DENIED }

class AppsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext
    private val prefs = appContext.getSharedPreferences("app_management_prefs", Context.MODE_PRIVATE)

    private var rawPackages: List<PackageInfo> = emptyList()
    private val hiddenPackages: MutableSet<String> =
        (prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toMutableSet()

    var sortOrder: SortOrder = SortOrder.valueOf(
        prefs.getString(KEY_SORT, SortOrder.NAME_ASC.name) ?: SortOrder.NAME_ASC.name
    )
        private set

    var filterState: FilterState = FilterState.ALL
        private set

    var searchQuery: String = ""
        private set

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>()
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    fun setSortOrder(order: SortOrder) {
        sortOrder = order
        prefs.edit().putString(KEY_SORT, order.name).apply()
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
        rawPackages = rawPackages.filter { it.packageName != packageName }
        applyFiltersAndSort()
    }

    fun unhidePackage(packageName: String) {
        hiddenPackages.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenPackages.toSet()).apply()
    }

    fun refresh() {
        applyFiltersAndSort()
    }

    fun load(onlyCount: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = mutableListOf<PackageInfo>()
                var count = 0
                for (pi in AuthorizationManager.getPackages()) {
                    if (pi.packageName !in hiddenPackages) list.add(pi)
                    val appInfo = pi.applicationInfo ?: continue
                    if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) count++
                }
                rawPackages = list
                if (!onlyCount) applyFiltersAndSort()
                _grantedCount.postValue(Resource.success(count))
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                _packages.postValue(Resource.error(e, null))
                _grantedCount.postValue(Resource.error(e, 0))
            }
        }
    }

    private fun applyFiltersAndSort() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = appContext.packageManager
                var list = rawPackages.filter { pi ->
                    val appInfo = pi.applicationInfo
                    val label = appInfo?.loadLabel(pm)?.toString() ?: ""
                    val matchesSearch = searchQuery.isBlank() ||
                        label.contains(searchQuery, ignoreCase = true) ||
                        pi.packageName.contains(searchQuery, ignoreCase = true)
                    val matchesFilter = when (filterState) {
                        FilterState.ALL -> true
                        FilterState.GRANTED -> appInfo != null && runCatching {
                            AuthorizationManager.granted(pi.packageName, appInfo.uid)
                        }.getOrDefault(false)
                        FilterState.DENIED -> appInfo == null || runCatching {
                            !AuthorizationManager.granted(pi.packageName, appInfo.uid)
                        }.getOrDefault(true)
                    }
                    matchesSearch && matchesFilter
                }
                list = when (sortOrder) {
                    SortOrder.NAME_ASC -> list.sortedBy {
                        it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: it.packageName
                    }
                    SortOrder.LAST_INSTALLED -> list.sortedByDescending { it.firstInstallTime }
                    SortOrder.LAST_UPDATED -> list.sortedByDescending { it.lastUpdateTime }
                }
                _packages.postValue(Resource.success(list))
            } catch (e: Throwable) {
                _packages.postValue(Resource.error(e, null))
            }
        }
    }

    companion object {
        private const val KEY_HIDDEN = "hidden_packages"
        private const val KEY_SORT = "app_list_sort_order"
    }
}
