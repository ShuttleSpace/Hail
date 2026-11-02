package com.aistra.hail.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.compose.*
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork
import kotlinx.coroutines.launch
import org.json.JSONArray

sealed interface HomeItem {
    data class Header(val title: CharSequence) : HomeItem
    data class App(val info: AppInfo, val frozen: Boolean) : HomeItem
}

/** Same key the list is grouped by, so A-Z letters line up with the section headers. */
private fun homeLetter(info: AppInfo): Char =
    if (HailData.groupApps && HailData.groupMode == HailData.GROUP_LETTER_FIRST_CHAR)
        AppNameIndex.firstChar(info.name)
    else AppNameIndex.firstLetter(info.name)

private fun List<HomeItem>.indexPositions(): Map<Char, Int> {
    val positions = linkedMapOf<Char, Int>()
    forEachIndexed { index, item ->
        if (item is HomeItem.App) positions.putIfAbsent(homeLetter(item.info), index)
    }
    return positions
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val tags = vm.tags
    val pagerState = rememberPagerState(pageCount = { tags.size })

    var showHomeSheet by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<AppInfo?>(null) }
    var tagTarget by remember { mutableStateOf<AppInfo?>(null) }
    var guideDialog by remember { mutableStateOf(false) }
    var multiSelectSheet by remember { mutableStateOf(false) }
    var tagEditIndex by remember { mutableStateOf<Int?>(null) }
    var addTagTarget by remember { mutableStateOf<List<AppInfo>?>(null) }
    var deferredTarget by remember { mutableStateOf<AppInfo?>(null) }

    fun toast(resId: Int, text: CharSequence? = null) {
        if (text == null) HUI.showToast(resId) else HUI.showToast(resId, text)
    }

    fun setFrozen(list: List<AppInfo>, frozen: Boolean, update: Boolean = true): Boolean {
        val mode = HailData.workingMode
        if (mode == HailData.MODE_DEFAULT) {
            guideDialog = true
            return false
        }
        if (mode == HailData.MODE_SHIZUKU_HIDE && !runCatching { HShizuku.isRoot }.getOrDefault(false)) {
            HUI.showToast(R.string.shizuku_hide_adb)
            return false
        }
        val filtered = list.filter { AppManager.isAppFrozen(it.packageName) != frozen }
        val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())
        if (result == null) {
            HUI.showToast(R.string.permission_denied)
            return false
        }
        toast(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result)
        if (update) vm.refresh()
        return true
    }

    fun removeFromHome(info: AppInfo) {
        HailData.removeCheckedApp(info.packageName)
        vm.selected.remove(info)
        vm.refresh()
    }

    /** Frozen apps are listed on Home without being saved; persist one before editing it. */
    fun persistToHome(info: AppInfo): AppInfo {
        HailData.checkedList.firstOrNull { it.packageName == info.packageName }?.let { return it }
        val created = AppInfo(
            info.packageName,
            pinned = info.pinned,
            whitelisted = info.whitelisted,
            tagIdList = mutableListOf(tags.getOrNull(pagerState.currentPage)?.second ?: 0),
        )
        HailData.checkedList.add(created)
        return created
    }

    fun launch(info: AppInfo) {
        val pkg = info.packageName
        if (AppManager.isAppFrozen(pkg)) {
            AppManager.setAppFrozen(pkg, false)
            vm.refresh()
        }
        if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) HIsland.ensureLaunchIntentExists(pkg)
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            HShortcuts.addDynamicShortcut(pkg)
            context.startActivity(it)
        } ?: HUI.showToast(R.string.activity_not_found)
    }

    fun onItemClick(info: AppInfo) {
        if (vm.multiselect) {
            vm.toggleSelect(info)
            return
        }
        if (info.applicationInfo == null) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.app_not_installed),
                    actionLabel = context.getString(R.string.action_remove_home),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) removeFromHome(info)
            }
            return
        }
        launch(info)
    }

    fun onItemLongClick(info: AppInfo) {
        if (info.applicationInfo == null && (!vm.multiselect || info !in vm.selected)) {
            exportToClipboard(listOf(info))
            return
        }
        if (info in vm.selected) {
            multiSelectSheet = true
            return
        }
        actionTarget = info
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            if (vm.searching) {
                TopAppBar(
                    title = {
                        TextField(
                            value = vm.query,
                            onValueChange = { vm.query = it },
                            placeholder = { Text(stringResource(R.string.action_search)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = if (HailData.nineKeySearch)
                                    androidx.compose.ui.text.input.KeyboardType.Phone
                                else androidx.compose.ui.text.input.KeyboardType.Text,
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.searching = false; vm.query = "" }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            if (vm.multiselect) stringResource(R.string.msg_selected, vm.selected.size.toString())
                            else stringResource(R.string.app_name)
                        )
                    },
                    actions = {
                        if (vm.multiselect) {
                            IconButton(onClick = {
                                vm.multiselect = false
                                vm.refresh()
                            }) { Icon(Icons.Filled.Check, stringResource(R.string.action_confirm)) }
                            IconButton(onClick = {
                                vm.invert(currentPageApps(vm, pagerState.currentPage))
                            }) {
                                Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.action_invert))
                            }
                            IconButton(onClick = {
                                vm.multiselect = false
                                vm.clearSelection()
                                vm.refresh()
                            }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
                        } else {
                            IconButton(onClick = { vm.searching = true }) {
                                Icon(Icons.Outlined.Search, stringResource(R.string.action_search))
                            }
                            IconButton(onClick = {
                                vm.enterMultiselect(tags.getOrNull(pagerState.currentPage)?.second ?: 0)
                                HUI.showToast(R.string.tap_to_select)
                            }) { Icon(Icons.Outlined.Checklist, stringResource(R.string.tap_to_select)) }
                            IconButton(onClick = { showHomeSheet = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val fabExpanded by remember { mutableStateOf(true) }
            ExtendedFloatingActionButton(
                onClick = {
                    if (vm.selected.isNotEmpty()) {
                        if (setFrozen(vm.selected.toList(), true, false)) {
                            vm.clearSelection()
                            vm.refresh()
                        }
                    } else {
                        setFrozen(currentPageApps(vm, pagerState.currentPage).filterNot { it.whitelisted }, true)
                    }
                },
                icon = { Icon(Icons.Outlined.AcUnit, stringResource(R.string.action_freeze)) },
                text = { Text(stringResource(R.string.action_freeze_current)) },
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { setFrozen(HailData.checkedList, true) },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tags.size > 1) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 0.dp,
                ) {
                    tags.forEachIndexed { index, tag ->
                        Tab(
                            selected = index == pagerState.currentPage,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(tag.first) },
                            modifier = Modifier.combinedClickable(
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                onLongClick = { tagEditIndex = index },
                            ),
                        )
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val tagId = tags.getOrNull(page)?.second ?: 0
                HomePage(
                    vm = vm,
                    tagId = tagId,
                    onItemClick = ::onItemClick,
                    onItemLongClick = ::onItemLongClick,
                )
            }
        }
    }

    if (showHomeSheet) {
        HomeMenuSheet(
            onDismiss = { showHomeSheet = false },
            onFreezeCurrent = { setFrozen(currentPageApps(vm, pagerState.currentPage).filterNot { it.whitelisted }, true) },
            onUnfreezeCurrent = { setFrozen(currentPageApps(vm, pagerState.currentPage), false) },
            onFreezeAll = { setFrozen(HailData.checkedList, true) },
            onUnfreezeAll = { setFrozen(HailData.checkedList, false) },
            onFreezeNonWhitelisted = { setFrozen(HailData.checkedList.filterNot { it.whitelisted }, true) },
            onImportClipboard = { importFromClipboard(vm, tags.getOrNull(pagerState.currentPage)?.second ?: 0) },
            onImportFrozen = {
                val tagId = tags.getOrNull(pagerState.currentPage)?.second ?: 0
                val size = HPackages.getInstalledApplications().map { it.packageName }
                    .filter { AppManager.isAppFrozen(it) && !HailData.isChecked(it) }
                    .onEach { HailData.addCheckedApp(it, tagId, false) }.size
                if (size > 0) {
                    HailData.saveApps(); vm.refresh()
                }
                HUI.showToast(app.getString(R.string.msg_imported, size.toString()))
            },
            onExportCurrent = { exportToClipboard(currentPageApps(vm, pagerState.currentPage)) },
            onExportAll = { exportToClipboard(HailData.checkedList) },
            onLayout = { vm.refresh() },
            onGroupMode = { vm.refresh() },
        )
    }

    actionTarget?.let { info ->
        AppActionSheet(
            info = info,
            hasMultipleTags = tags.size > 1,
            inHome = HailData.isChecked(info.packageName),
            tags = tags.map { it.first },
            onDismiss = { actionTarget = null },
            onLaunch = { actionTarget = null; launch(info) },
            onToggleFrozen = { frozen ->
                actionTarget = null
                setFrozen(listOf(info), frozen)
            },
            onDeferredTask = { actionTarget = null; deferredTarget = info },
            onTogglePin = {
                actionTarget = null
                val target = persistToHome(info)
                target.pinned = !target.pinned
                HailData.saveApps(); vm.refresh()
            },
            onToggleWhitelist = {
                actionTarget = null
                val target = persistToHome(info)
                target.whitelisted = !target.whitelisted
                HailData.saveApps(); vm.refresh()
            },
            onSetTag = { actionTarget = null; tagTarget = info },
            onAddShortcut = { tagName ->
                actionTarget = null
                val intent = HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, info.packageName)
                HShortcuts.addPinShortcut(info, info.packageName, info.name, intent)
            },
            onExport = { actionTarget = null; exportToClipboard(listOf(info)) },
            onRemoveHome = { actionTarget = null; removeFromHome(info) },
            onUnfreezeRemove = {
                actionTarget = null
                if (setFrozen(listOf(info), false, false) || !AppManager.isAppFrozen(info.packageName)) {
                    removeFromHome(info)
                }
            },
            onDetails = {
                actionTarget = null
                HUI.startActivity(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    HPackages.packageUri(info.packageName),
                )
            },
        )
    }

    deferredTarget?.let { info ->
        val context2 = LocalContext.current
        val minutes = remember { context2.resources.getIntArray(R.array.deferred_task_values) }
        ListBottomSheet(
            title = context2.getString(R.string.action_deferred_task),
            entries = minutes.map {
                context2.resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
            },
            current = "",
            onDismiss = { deferredTarget = null },
            onSelected = { entry ->
                deferredTarget = null
                val value = minutes.firstOrNull {
                    context2.resources.getQuantityString(R.plurals.deferred_task_entry, it, it) == entry
                } ?: return@ListBottomSheet
                val frozen = AppManager.isAppFrozen(info.packageName)
                HWork.setDeferredFrozen(info.packageName, !frozen, value.toLong())
                scope.launch {
                    val action = context2.getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
                    val message = context2.resources.getQuantityString(
                        R.plurals.msg_deferred_task, value, value, action, info.name
                    )
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = context2.getString(R.string.action_undo),
                        duration = SnackbarDuration.Indefinite,
                    )
                    if (result == SnackbarResult.ActionPerformed) HWork.cancelWork(info.packageName)
                }
            },
        )
    }

    if (multiSelectSheet) {
        MultiSelectSheet(
            vm = vm,
            onDismiss = { multiSelectSheet = false },
            onFreeze = {
                multiSelectSheet = false
                setFrozen(vm.selected.toList(), true, false)
                vm.clearSelection()
                vm.refresh()
            },
            onUnfreeze = {
                multiSelectSheet = false
                setFrozen(vm.selected.toList(), false, false)
                vm.clearSelection()
                vm.refresh()
            },
            onSetTag = { multiSelectSheet = false; addTagTarget = vm.selected.toList() },
            onExport = {
                multiSelectSheet = false
                exportToClipboard(vm.selected.toList())
                vm.clearSelection()
                vm.refresh()
            },
            onRemoveHome = {
                multiSelectSheet = false
                vm.selected.forEach { HailData.removeCheckedApp(it.packageName, false) }
                HailData.saveApps()
                vm.clearSelection()
                vm.refresh()
            },
            onUnfreezeRemove = {
                multiSelectSheet = false
                val unfrozen = setFrozen(vm.selected.toList(), false, false)
                vm.selected.forEach {
                    if (unfrozen || !AppManager.isAppFrozen(it.packageName)) {
                        HailData.removeCheckedApp(it.packageName, false)
                    }
                }
                HailData.saveApps()
                vm.clearSelection()
                vm.refresh()
            },
            onDeselect = {
                multiSelectSheet = false
                vm.clearSelection()
                vm.refresh()
            },
            onSelectAll = {
                multiSelectSheet = false
                vm.selectAll(currentPageApps(vm, pagerState.currentPage))
            },
        )
    }

    tagTarget?.let { info ->
        TagDialog(
            info = info,
            onDismiss = { tagTarget = null },
            onConfirm = { ids ->
                if (ids.isEmpty()) {
                    removeFromHome(info)
                } else {
                    val target = persistToHome(info)
                    target.tagIdList.clear()
                    target.tagIdList.addAll(ids)
                    HailData.saveApps(); vm.refresh()
                }
                tagTarget = null
            },
            onAddTag = { tagTarget = null; addTagTarget = listOf(info) },
        )
    }

    tagEditIndex?.let { index ->
        TagRenameDialog(
            tagName = tags.getOrNull(index)?.first.orEmpty(),
            removable = index != 0,
            onDismiss = { tagEditIndex = null },
            onConfirm = { newName ->
                tagEditIndex = null
                renameTag(index, newName)
            },
            onRemove = {
                tagEditIndex = null
                removeTag(index)
            },
        )
    }

    addTagTarget?.let { targets ->
        AddTagDialog(
            onDismiss = { addTagTarget = null },
            onConfirm = { name ->
                addTagTarget = null
                val id = name.hashCode()
                if (HailData.tags.none { it.first == name || it.second == id }) {
                    HailData.tags.add(name to id)
                    HailData.saveTags()
                    targets.forEach { info ->
                        if (id !in info.tagIdList) info.tagIdList.add(id)
                        if (info.tagIdList.isEmpty()) removeFromHome(info)
                    }
                    HailData.saveApps(); vm.refresh()
                }
            },
        )
    }

    if (guideDialog) {
        AlertDialog(
            onDismissRequest = { guideDialog = false },
            text = { Text(stringResource(R.string.msg_guide)) },
            confirmButton = { TextButton(onClick = { guideDialog = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

private fun currentPageApps(vm: HomeViewModel, page: Int): List<AppInfo> {
    val tagId = vm.tags.getOrNull(page)?.second ?: 0
    return vm.visibleApps(tagId)
}

private fun renameTag(index: Int, newName: String) {
    val tags = HailData.tags
    val tag = tags.getOrNull(index) ?: return
    if (newName.isBlank() || tags.any { it.first == newName }) return
    val isDefault = index == 0
    val newId = if (isDefault) 0 else newName.hashCode()
    val oldId = tag.second
    tags[index] = newName to newId
    if (!isDefault) {
        HailData.checkedList.forEach { info ->
            val pos = info.tagIdList.indexOf(oldId)
            if (pos != -1) info.tagIdList[pos] = newId
        }
        HailData.saveApps()
    }
    HailData.saveTags()
}

private fun removeTag(index: Int) {
    val tags = HailData.tags
    val tag = tags.getOrNull(index) ?: return
    HailData.checkedList.forEach { info ->
        if (info.tagIdList.remove(tag.second) && info.tagIdList.isEmpty()) {
            HailData.removeCheckedApp(info.packageName, false)
        }
    }
    tags.removeAt(index)
    HailData.saveApps()
    HailData.saveTags()
}

@Composable
private fun HomePage(
    vm: HomeViewModel,
    tagId: Int,
    onItemClick: (AppInfo) -> Unit,
    onItemLongClick: (AppInfo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items = remember(vm.revision, vm.query, tagId) { vm.items(tagId) }
    val positions = remember(items) { items.indexPositions() }
    val letters = remember(positions) { positions.keys.sortedWith(compareBy({ it == '#' }, { it })) }
    val showIndex = vm.query.isEmpty() && letters.size > 1
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val isGrid = HailData.homeLayout != HailData.LAYOUT_LIST
    val columns = HailData.gridColumns
    // Membership in a set: `selected` is a list and a linear scan per row is O(n²) per frame.
    val selectedKeys = vm.selected.mapTo(HashSet()) { it.packageName }
    val currentLetter by remember(items, isGrid) {
        derivedStateOf {
            val start = if (isGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
            var index = start
            var letter: Char? = null
            while (index < items.size) {
                val item = items[index]
                if (item is HomeItem.App) {
                    letter = homeLetter(item.info)
                    break
                }
                index++
            }
            letter
        }
    }

    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                Text(
                    text = stringResource(R.string.nothing_here),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else if (isGrid) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
            ) {
                itemsIndexed(items, span = { _, item ->
                    if (item is HomeItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                }, key = { _, item ->
                    when (item) {
                        is HomeItem.Header -> "h-${item.title}"
                        is HomeItem.App -> item.info.packageName
                    }
                }) { _, item ->
                    when (item) {
                        is HomeItem.Header -> SectionHeader(item.title)
                        is HomeItem.App -> HomeGridItem(
                            info = item.info,
                            frozen = item.frozen,
                            multiselect = vm.multiselect,
                            selected = item.info.packageName in selectedKeys,
                            fontSize = HailData.homeFontSize.toInt(),
                            onClick = { onItemClick(item.info) },
                            onLongClick = { onItemLongClick(item.info) },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) {
                itemsIndexed(items, key = { _, item ->
                    when (item) {
                        is HomeItem.Header -> "h-${item.title}"
                        is HomeItem.App -> item.info.packageName
                    }
                }) { _, item ->
                    when (item) {
                        is HomeItem.Header -> SectionHeader(item.title)
                        is HomeItem.App -> HomeListItem(
                            info = item.info,
                            frozen = item.frozen,
                            multiselect = vm.multiselect,
                            selected = item.info.packageName in selectedKeys,
                            fontSize = HailData.homeFontSize.toInt(),
                            onClick = { onItemClick(item.info) },
                            onLongClick = { onItemLongClick(item.info) },
                        )
                    }
                }
            }
        }
        if (showIndex) {
            LetterIndex(
                letters = letters,
                current = currentLetter,
                onSelect = { letter ->
                    scope.launch {
                        positions[letter]?.let { index ->
                            if (isGrid) gridState.scrollToItem(index)
                            else listState.scrollToItem(index)
                        }
                    }
                },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeGridItem(
    info: AppInfo,
    frozen: Boolean,
    multiselect: Boolean,
    selected: Boolean,
    fontSize: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Box {
            AppIcon(
                info = info.applicationInfo,
                size = 56.dp,
                grayscale = frozen,
            )
            if (multiselect) {
                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
            } else if (frozen) {
                FrozenBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        AppLabel(
            name = info.name,
            frozen = frozen,
            whitelisted = info.whitelisted,
            selected = selected,
            notFound = info.applicationInfo == null,
            fontSize = fontSize,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeListItem(
    info: AppInfo,
    frozen: Boolean,
    multiselect: Boolean,
    selected: Boolean,
    fontSize: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (multiselect) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(44.dp)) {
            AppIcon(info = info.applicationInfo, size = 44.dp, grayscale = frozen)
            if (!multiselect && frozen) {
                FrozenBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Spacer(Modifier.width(12.dp))
        AppLabel(
            name = info.name,
            frozen = frozen,
            whitelisted = info.whitelisted,
            selected = selected,
            notFound = info.applicationInfo == null,
            fontSize = fontSize,
        )
    }
}

private fun exportToClipboard(list: List<AppInfo>) {
    if (list.isEmpty()) return
    HUI.copyText(
        if (list.size > 1) JSONArray().run { list.forEach { put(it.packageName) }; toString() }
        else list[0].packageName
    )
    HUI.showToast(app.getString(R.string.msg_exported, if (list.size > 1) list.size.toString() else list[0].name.toString()))
}

private fun importFromClipboard(vm: HomeViewModel, tagId: Int) {
    runCatching {
        val str = HUI.pasteText() ?: throw IllegalArgumentException()
        val json = if (str.contains('[')) JSONArray(str.substring(str.indexOf('[')..str.indexOf(']', str.indexOf('['))))
        else JSONArray().put(str)
        var i = 0
        for (index in 0 until json.length()) {
            val pkg = json.getString(index)
            if (HPackages.getApplicationInfoOrNull(pkg) != null && !HailData.isChecked(pkg)) {
                HailData.addCheckedApp(pkg, tagId, false)
                i++
            }
        }
        if (i > 0) {
            HailData.saveApps(); vm.refresh()
        }
        HUI.showToast(app.getString(R.string.msg_imported, i.toString()))
    }
}

@Composable
private fun HomeMenuSheet(
    onDismiss: () -> Unit,
    onFreezeCurrent: () -> Unit,
    onUnfreezeCurrent: () -> Unit,
    onFreezeAll: () -> Unit,
    onUnfreezeAll: () -> Unit,
    onFreezeNonWhitelisted: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFrozen: () -> Unit,
    onExportCurrent: () -> Unit,
    onExportAll: () -> Unit,
    onLayout: () -> Unit,
    onGroupMode: () -> Unit,
) {
    val context = LocalContext.current
    val entries = remember(HailData.uiRevision) {
        buildList {
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze_current), keepOpen = true) { onFreezeCurrent() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze_current), keepOpen = true) { onUnfreezeCurrent() })
            add(SheetEntry.Divider)
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze_all), keepOpen = true) { onFreezeAll() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze_all), keepOpen = true) { onUnfreezeAll() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze_non_whitelisted), keepOpen = true) { onFreezeNonWhitelisted() })
            add(SheetEntry.Divider)
            add(SheetEntry.Action(Icons.Outlined.ContentPaste, context.getString(R.string.action_import_clipboard), keepOpen = true) { onImportClipboard() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_import_frozen), keepOpen = true) { onImportFrozen() })
            add(SheetEntry.Action(Icons.Outlined.ContentCopy, context.getString(R.string.action_export_current), keepOpen = true) { onExportCurrent() })
            add(SheetEntry.Action(Icons.Outlined.ContentCopy, context.getString(R.string.action_export_all), keepOpen = true) { onExportAll() })
            add(SheetEntry.Divider)
            add(SheetEntry.Action(Icons.Outlined.GridView, context.getString(R.string.layout_grid), HailData.homeLayout == HailData.LAYOUT_GRID) {
                HailData.homeLayout = HailData.LAYOUT_GRID; onLayout()
            })
            add(SheetEntry.Action(Icons.Outlined.ViewList, context.getString(R.string.layout_list), HailData.homeLayout == HailData.LAYOUT_LIST) {
                HailData.homeLayout = HailData.LAYOUT_LIST; onLayout()
            })
            add(SheetEntry.Stepper(Icons.Outlined.GridView, context.getString(R.string.grid_columns), HailData.gridColumns, 1..5) {
                HailData.gridColumns = it; onLayout()
            })
            add(SheetEntry.Divider)
            add(SheetEntry.Action(Icons.Outlined.Group, context.getString(R.string.group_apps), HailData.groupApps) {
                HailData.groupApps = !HailData.groupApps; onGroupMode()
            })
            add(SheetEntry.Header(context.getString(R.string.group_mode)))
            add(SheetEntry.Action(Icons.Outlined.Group, context.getString(R.string.group_user_system), HailData.groupMode == HailData.GROUP_USER_SYSTEM, radio = true) {
                HailData.groupMode = HailData.GROUP_USER_SYSTEM; HailData.groupApps = true; onGroupMode()
            })
            add(SheetEntry.Action(Icons.Outlined.Group, context.getString(R.string.group_letter_pinyin), HailData.groupMode == HailData.GROUP_LETTER_PINYIN, radio = true) {
                HailData.groupMode = HailData.GROUP_LETTER_PINYIN; HailData.groupApps = true; onGroupMode()
            })
            add(SheetEntry.Action(Icons.Outlined.Group, context.getString(R.string.group_letter_first_char), HailData.groupMode == HailData.GROUP_LETTER_FIRST_CHAR, radio = true) {
                HailData.groupMode = HailData.GROUP_LETTER_FIRST_CHAR; HailData.groupApps = true; onGroupMode()
            })
        }
    }
    MenuSheet(onDismiss = onDismiss, entries = entries)
}

@Composable
private fun AppActionSheet(
    info: AppInfo,
    hasMultipleTags: Boolean,
    inHome: Boolean,
    tags: List<String>,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onToggleFrozen: (Boolean) -> Unit,
    onDeferredTask: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleWhitelist: () -> Unit,
    onSetTag: () -> Unit,
    onAddShortcut: (String?) -> Unit,
    onExport: () -> Unit,
    onRemoveHome: () -> Unit,
    onUnfreezeRemove: () -> Unit,
    onDetails: () -> Unit,
) {
    val context = LocalContext.current
    val frozen = AppManager.isAppFrozen(info.packageName)
    var showDeferred by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    if (showDeferred) {
        ListBottomSheet(
            title = context.getString(R.string.action_deferred_task),
            entries = context.resources.getIntArray(R.array.deferred_task_values).map {
                context.resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
            },
            current = "",
            onDismiss = { showDeferred = false },
            onSelected = { entry ->
                showDeferred = false
                onDeferredTask()
            },
        )
        return
    }
    if (showTagPicker) {
        ListBottomSheet(
            title = context.getString(R.string.action_add_pin_shortcut),
            entries = tags,
            current = "",
            onDismiss = { showTagPicker = false },
            onSelected = { tag -> showTagPicker = false; onAddShortcut(tag) },
        )
        return
    }
    MenuSheet(
        onDismiss = onDismiss,
        entries = buildList {
            add(SheetEntry.Action(Icons.Outlined.Launch, context.getString(R.string.action_launch)) { onLaunch() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)) { onToggleFrozen(!frozen) })
            add(SheetEntry.Action(Icons.Outlined.Schedule, context.getString(R.string.action_deferred_task)) { showDeferred = true })
            add(SheetEntry.Action(Icons.Outlined.PushPin, context.getString(if (info.pinned) R.string.action_unpin else R.string.action_pin)) { onTogglePin() })
            add(SheetEntry.Action(Icons.Outlined.Lock, context.getString(if (info.whitelisted) R.string.action_remove_whitelist else R.string.action_whitelist)) { onToggleWhitelist() })
            add(SheetEntry.Action(Icons.Outlined.Label, context.getString(R.string.action_tag_set)) { onSetTag() })
            add(SheetEntry.Action(Icons.Outlined.AddToHomeScreen, context.getString(R.string.action_add_pin_shortcut)) {
                if (hasMultipleTags) showTagPicker = true else onAddShortcut(null)
            })
            add(SheetEntry.Action(Icons.Outlined.ContentCopy, context.getString(R.string.action_export_clipboard)) { onExport() })
            add(SheetEntry.Divider)
            if (inHome) {
                add(SheetEntry.Action(Icons.Outlined.Delete, context.getString(R.string.action_remove_home)) { onRemoveHome() })
                if (frozen) add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze_remove_home)) { onUnfreezeRemove() })
                add(SheetEntry.Divider)
            }
            add(SheetEntry.Action(Icons.Outlined.Info, context.getString(R.string.action_details)) { onDetails() })
        },
    )
}

@Composable
private fun MultiSelectSheet(
    vm: HomeViewModel,
    onDismiss: () -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onSetTag: () -> Unit,
    onExport: () -> Unit,
    onRemoveHome: () -> Unit,
    onUnfreezeRemove: () -> Unit,
    onDeselect: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val context = LocalContext.current
    MenuSheet(
        onDismiss = onDismiss,
        entries = buildList {
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze)) { onFreeze() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze)) { onUnfreeze() })
            add(SheetEntry.Action(Icons.Outlined.Label, context.getString(R.string.action_tag_set)) { onSetTag() })
            add(SheetEntry.Action(Icons.Outlined.ContentCopy, context.getString(R.string.action_export_clipboard)) { onExport() })
            add(SheetEntry.Action(Icons.Outlined.Delete, context.getString(R.string.action_remove_home)) { onRemoveHome() })
            add(SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze_remove_home)) { onUnfreezeRemove() })
            add(SheetEntry.Divider)
            add(SheetEntry.Action(Icons.Outlined.Checklist, context.getString(R.string.action_select_all)) { onSelectAll() })
            add(SheetEntry.Action(Icons.Outlined.Close, context.getString(R.string.action_deselect)) { onDeselect() })
        },
    )
}

@Composable
private fun TagDialog(
    info: AppInfo,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
    onAddTag: () -> Unit,
) {
    val tags = remember { HailData.tags.toList() }
    val checked = remember { mutableStateListOf<Int>().apply { addAll(info.tagIdList) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_tag_set)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                tags.forEach { tag ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                if (tag.second in checked) checked.remove(tag.second) else checked.add(tag.second)
                            },
                            onLongClick = {},
                        ),
                    ) {
                        Checkbox(checked = tag.second in checked, onCheckedChange = null)
                        Text(tag.first)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onAddTag) {
                        Text(stringResource(R.string.action_tag_add))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked.toList()) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun TagRenameDialog(
    tagName: String,
    removable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember { mutableStateOf(tagName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_tag_set)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tag)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (removable) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onRemove) {
                            Text(stringResource(R.string.action_tag_remove))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun AddTagDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_tag_add)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tag)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
