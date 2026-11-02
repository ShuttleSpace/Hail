package com.aistra.hail.ui.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppNameIndex
import com.aistra.hail.utils.FuzzySearch
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.NameComparator
import com.aistra.hail.utils.NineKeySearch
import com.aistra.hail.utils.PinyinSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var multiselect by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    val selected = mutableStateListOf<AppInfo>()

    /** Frozen apps that were never added to a tag; they still belong on Home. */
    private var frozenExtras by mutableStateOf<List<AppInfo>>(emptyList())

    /** Pending seed: once the frozen list is current, pre-check frozen apps for this tag. */
    private var seedPending = false
    private var seedTagId = 0

    val tags get() = HailData.tags

    init {
        loadFrozenExtras()
    }

    fun refresh() {
        revision++
        loadFrozenExtras()
    }

    /**
     * Enter multiselect mode. The selection is seeded with the frozen apps of [tagId] once
     * the frozen list is current, so every app showing a snowflake is checked.
     */
    fun enterMultiselect(tagId: Int) {
        multiselect = true
        selected.clear()
        seedTagId = tagId
        seedPending = true
        loadFrozenExtras()
    }

    private fun loadFrozenExtras() {
        viewModelScope.launch {
            val extras = withContext(Dispatchers.IO) {
                HPackages.getInstalledApplications()
                    .map { it.packageName }
                    .filter { AppManager.isAppFrozen(it) && !HailData.isChecked(it) }
                    .map { AppInfo(it) }
            }
            frozenExtras = extras
            if (seedPending) {
                seedPending = false
                selected.addAll(visibleApps(seedTagId).filter { AppManager.isAppFrozen(it.packageName) })
            }
            revision++
        }
    }

    /** Apps of [tagId], searched across every tag when a query is present. */
    fun visibleApps(tagId: Int): List<AppInfo> {
        revision
        val q = query
        return (HailData.checkedList + frozenExtras).distinctBy { it.packageName }.filter {
            if (q.isEmpty()) it in frozenExtras || tagId in it.tagIdList
            else ((HailData.nineKeySearch && NineKeySearch.search(q, it.packageName, it.name.toString()))
                    || FuzzySearch.search(it.packageName, q)
                    || FuzzySearch.search(it.name.toString(), q)
                    || PinyinSearch.searchPinyinAll(it.name.toString(), q))
        }.sortedWith(NameComparator)
    }

    fun items(tagId: Int): List<HomeItem> {
        val apps = visibleApps(tagId)
        if (!HailData.groupApps) return apps.map { HomeItem.App(it, AppManager.isAppFrozen(it.packageName)) }
        return when (HailData.groupMode) {
            HailData.GROUP_LETTER_PINYIN -> letterGroups(apps) { AppNameIndex.firstLetter(it.name) }
            HailData.GROUP_LETTER_FIRST_CHAR -> letterGroups(apps) { AppNameIndex.firstChar(it.name) }
            else -> userSystemGroups(apps)
        }
    }

    private fun userSystemGroups(apps: List<AppInfo>): List<HomeItem> {
        val user = apps.filterNot { it.isSystemApp }
        val system = apps.filter { it.isSystemApp }
        return buildList {
            if (user.isNotEmpty()) {
                add(HomeItem.Header(HailApp.app.getString(R.string.group_user_apps)))
                user.forEach { add(HomeItem.App(it, AppManager.isAppFrozen(it.packageName))) }
            }
            if (system.isNotEmpty()) {
                add(HomeItem.Header(HailApp.app.getString(R.string.group_system_apps)))
                system.forEach { add(HomeItem.App(it, AppManager.isAppFrozen(it.packageName))) }
            }
        }
    }

    private fun letterGroups(apps: List<AppInfo>, letterOf: (AppInfo) -> Char): List<HomeItem> {
        val groups = linkedMapOf<Char, MutableList<AppInfo>>()
        apps.forEach { groups.getOrPut(letterOf(it)) { mutableListOf() }.add(it) }
        return buildList {
            groups.entries.sortedWith(compareBy({ it.key == '#' }, { it.key })).forEach { (letter, list) ->
                add(HomeItem.Header(letter.toString()))
                list.forEach { add(HomeItem.App(it, AppManager.isAppFrozen(it.packageName))) }
            }
        }
    }

    fun toggleSelect(info: AppInfo) {
        if (info in selected) selected.remove(info) else selected.add(info)
    }

    /** Replace the whole selection; cheaper and more consistent than toggling app by app. */
    fun selectAll(apps: List<AppInfo>) {
        selected.clear()
        selected.addAll(apps)
    }

    fun invert(apps: List<AppInfo>) {
        val inverted = apps.filterNot { it in selected } + selected.filterNot { it in apps }
        selected.clear()
        selected.addAll(inverted.sortedWith(NameComparator))
    }

    fun clearSelection() = selected.clear()
}