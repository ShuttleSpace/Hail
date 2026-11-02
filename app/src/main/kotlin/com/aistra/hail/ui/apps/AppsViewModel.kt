package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    private val appsFlow = MutableStateFlow<List<ApplicationInfo>>(emptyList())
    private val queryFlow = MutableStateFlow("")
    private val revisionFlow = MutableStateFlow(0)

    /**
     * The list the user actually sees: installed apps filtered by the frozen /
     * user / system switches and the current search query, then sorted.
     * */
    val displayApps: StateFlow<List<ApplicationInfo>> =
        combine(appsFlow, queryFlow, revisionFlow) { apps, query, _ -> apps to query }
            .mapLatest { (apps, query) -> filterList(apps, query) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        updateAppList()
    }

    fun updateAppList() {
        viewModelScope.launch {
            appsFlow.value = withContext(Dispatchers.IO) { HPackages.getInstalledApplications() }
        }
    }

    fun postQuery(text: String, delayTime: Long = 300L) {
        viewModelScope.launch {
            if (delayTime > 0) kotlinx.coroutines.delay(delayTime)
            queryFlow.value = text
        }
    }

    /** Re-applies the sort/filter switches after they changed. */
    fun updateDisplayAppList() {
        revisionFlow.value++
    }

    private val ApplicationInfo.isSystemApp: Boolean
        get() = flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM
    private val ApplicationInfo.isAppFrozen get() = AppManager.isAppFrozen(packageName)

    private suspend fun filterList(
        appList: List<ApplicationInfo>,
        query: String?
    ): List<ApplicationInfo> {
        val pm = (getApplication() as HailApp).packageManager
        return withContext(Dispatchers.Default) {
            appList.filter {
                ((HailData.filterUserApps && !it.isSystemApp)
                        || (HailData.filterSystemApps && it.isSystemApp))

                        && ((HailData.filterFrozenApps && it.isAppFrozen)
                        || (HailData.filterUnfrozenApps && !it.isAppFrozen))
                        // Search apps
                        && ((HailData.nineKeySearch
                        && (NineKeySearch.search(query, it.packageName, it.loadLabel(pm).toString())))
                        || FuzzySearch.search(it.packageName, query)
                        || FuzzySearch.search(it.loadLabel(pm).toString(), query)
                        || PinyinSearch.searchPinyinAll(it.loadLabel(pm).toString(), query))
            }.run {
                when (HailData.sortBy) {
                    HailData.SORT_INSTALL -> sortedBy {
                        HPackages.getUnhiddenPackageInfoOrNull(it.packageName)
                            ?.firstInstallTime ?: 0
                    }

                    HailData.SORT_UPDATE -> sortedByDescending {
                        HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.lastUpdateTime ?: 0
                    }

                    else -> sortedWith(NameComparator)
                }
            }
        }
    }
}
