package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.aistra.hail.app.HailData
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.compose.*
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

sealed interface AppsItem {
    data class Header(val title: CharSequence) : AppsItem
    data class App(val info: ApplicationInfo, val frozen: Boolean) : AppsItem
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(vm: AppsViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<ApplicationInfo>()) }
    LaunchedEffect(Unit) {
        vm.displayApps.collect { apps = it }
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<Set<String>>(emptySet()) }
    var revision by remember { mutableIntStateOf(0) }
    var showSheet by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<ApplicationInfo?>(null) }
    var exportPkg by remember { mutableStateOf<String?>(null) }
    var uninstallTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(CreateDocument("application/vnd.android.package-archive")) { uri ->
            val pkg = exportPkg
            exportPkg = null
            if (pkg == null || uri == null) return@rememberLauncherForActivityResult
            val targetUri = uri
            scope.launch {
                val info = withContext(Dispatchers.IO) { HPackages.getApplicationInfoOrNull(pkg) }
                    ?: return@launch
                runCatching {
                    context.contentResolver.openOutputStream(targetUri, "rwt")?.use { target ->
                        FileInputStream(info.sourceDir).use { source ->
                            HFiles.copy(
                                source,
                                target
                            )
                        }
                    }
                }.onSuccess {
                    HUI.showToast(R.string.msg_extract_apk, targetUri.toString())
                }.onFailure {
                    HUI.showToast(R.string.operation_failed, it.localizedMessage ?: "Unknown")
                }
            }
        }

    fun refresh() {
        revision++
    }

    val items = remember(apps, revision, query) {
        val filtered = if (query.isEmpty()) apps else apps.filter {
            FuzzySearch.search(
                it.packageName,
                query
            ) || FuzzySearch.search(it.loadLabel(context.packageManager).toString(), query)
        }
        if (!HailData.groupApps) filtered.map {
            AppsItem.App(
                it,
                AppManager.isAppFrozen(it.packageName)
            )
        }
        else when (HailData.groupMode) {
            HailData.GROUP_LETTER_PINYIN -> letterGroups(filtered) {
                AppNameIndex.firstLetter(
                    it.loadLabel(
                        context.packageManager
                    )
                )
            }

            HailData.GROUP_LETTER_FIRST_CHAR -> letterGroups(filtered) {
                AppNameIndex.firstChar(
                    it.loadLabel(
                        context.packageManager
                    )
                )
            }

            else -> userSystemGroups(filtered)
        }
    }
    val positions = remember(items) {
        val map = linkedMapOf<Char, Int>()
        items.forEachIndexed { i, item ->
            if (item is AppsItem.App) map.putIfAbsent(appsLetter(item.info), i)
        }
        map
    }
    val letters =
        remember(positions) { positions.keys.sortedWith(compareBy({ it == '#' }, { it })) }
    val showIndex = letters.size > 1 && query.isEmpty() && HailData.sortBy == HailData.SORT_NAME
    val isGrid = HailData.appsLayout == HailData.LAYOUT_GRID
    val columns = HailData.gridColumns
    // Apps already in the cleanup list: saved to the list or currently frozen. The batch
    // checkbox and the grayed-out icon share this membership.
    val cleanupKeys = HailData.checkedList.mapTo(HashSet()) { it.packageName }
    // In batch (edit) mode the checkbox is the cleanup list, so an app counts as checked when
    // it is listed there or currently frozen (Home-side freezes don't write the list back).
    val checkedKeys: Set<String> = if (editMode) {
        val keys = cleanupKeys.toHashSet()
        items.forEach { item ->
            if (item is AppsItem.App && item.frozen) keys.add(item.info.packageName)
        }
        keys
    } else emptySet()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val currentLetter by remember(items, isGrid) {
        derivedStateOf {
            val start = if (isGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
            var index = start
            var letter: Char? = null
            while (index < items.size) {
                val item = items[index]
                if (item is AppsItem.App) {
                    letter = appsLetter(item.info)
                    break
                }
                index++
            }
            letter
        }
    }

    fun enterEdit() {
        editMode = true
        snapshot = HailData.checkedList.map { it.packageName }.toSet()
    }

    fun exitEdit(revert: Boolean) {
        if (revert) {
            val current = HailData.checkedList.map { it.packageName }.toSet()
            snapshot.forEach { if (it !in current) HailData.addCheckedApp(it) }
            current.forEach { if (it !in snapshot) HailData.removeCheckedApp(it) }
            HailData.saveApps()
        }
        editMode = false
        refresh()
    }

    fun toggleChecked(pkg: String) {
        // add/removeCheckedApp already persist; refresh() rebuilds every item row, which is far
        // more work than the single checkbox that actually changed.
        if (HailData.isChecked(pkg) || AppManager.isAppFrozen(pkg)) {
            // The checkbox mirrors the cleanup view: unchecking a listed app clears it, and
            // unchecking a frozen-but-unlisted app unfreezes it, never re-adds it.
            HailData.removeCheckedApp(pkg)
            if (AppManager.isAppFrozen(pkg)) AppManager.setAppFrozen(pkg, false)
        } else {
            HailData.addCheckedApp(pkg)
        }
        revision++
    }

    fun invert() {
        val shown = items.mapNotNull { (it as? AppsItem.App)?.info?.packageName }.toHashSet()
        val current = HailData.checkedList.toList()
        val byPkg = current.associateBy { it.packageName }
        // Keep the existing AppInfo objects (pinned / whitelisted / tags); only membership in
        // the home list flips. One write instead of a saveApps() per app, which froze the UI.
        val next = current.filterNot { it.packageName in shown } +
                shown.map { byPkg[it] ?: AppInfo(it) }
        HailData.checkedList.clear()
        HailData.checkedList.addAll(next)
        HailData.saveApps()
        refresh()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            if (searching) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it; vm.postQuery(it) },
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
                        IconButton(onClick = { searching = false; query = ""; vm.postQuery("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_apps)) },
                    actions = {
                        if (editMode) {
                            IconButton(onClick = { exitEdit(false) }) {
                                Icon(Icons.Filled.Check, stringResource(R.string.action_confirm))
                            }
                            IconButton(onClick = { invert() }) {
                                Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.action_invert))
                            }
                            IconButton(onClick = { exitEdit(true) }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel))
                            }
                        } else {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Outlined.Search, stringResource(R.string.action_search))
                            }
                            IconButton(onClick = { enterEdit() }) {
                                Icon(Icons.Outlined.Checklist, stringResource(R.string.action_edit))
                            }
                            IconButton(onClick = { showSheet = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (items.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        Text(
                            stringResource(R.string.nothing_here),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else if (isGrid) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        itemsIndexed(
                            items,
                            span = { _, item ->
                                if (item is AppsItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(
                                    1
                                )
                            },
                            key = { _, item ->
                                when (item) {
                                    is AppsItem.Header -> "h-${item.title}"; is AppsItem.App -> item.info.packageName
                                }
                            }) { _, item ->
                            when (item) {
                                is AppsItem.Header -> SectionHeader(item.title)
                                is AppsItem.App -> AppsGridItem(
                                    info = item.info,
                                    frozen = item.frozen,
                                    editMode = editMode,
                                    checked = item.info.packageName in checkedKeys,
                                    gray = item.frozen || item.info.packageName in cleanupKeys,
                                    onClick = { if (editMode) toggleChecked(item.info.packageName) },
                                    onLongClick = { actionTarget = item.info },
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
                                is AppsItem.Header -> "h-${item.title}"; is AppsItem.App -> item.info.packageName
                            }
                        }) { _, item ->
                            when (item) {
                                is AppsItem.Header -> SectionHeader(item.title)
                                is AppsItem.App -> AppsListItem(
                                    info = item.info,
                                    frozen = item.frozen,
                                    editMode = editMode,
                                    checked = item.info.packageName in checkedKeys,
                                    gray = item.frozen || item.info.packageName in cleanupKeys,
                                    onClick = { if (editMode) toggleChecked(item.info.packageName) },
                                    onLongClick = { actionTarget = item.info },
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
                                    if (isGrid) gridState.scrollToItem(
                                        index
                                    ) else listState.scrollToItem(index)
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
    }

    if (showSheet) {
        AppsMenuSheet(
            onDismiss = { showSheet = false },
            onSort = { vm.updateDisplayAppList(); refresh() },
            onFilter = { vm.updateDisplayAppList(); refresh() },
            onLayout = { refresh() },
            onGroupMode = { refresh() },
        )
    }

    actionTarget?.let { info ->
        AppContextSheet(
            info = info,
            onDismiss = { actionTarget = null },
            onDetails = {
                actionTarget = null
                HUI.startActivity(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    HPackages.packageUri(info.packageName)
                )
            },
            onExportClipboard = {
                actionTarget = null
                HUI.copyText(info.packageName)
                HUI.showToast(R.string.msg_text_copied, info.packageName)
            },
            onExtractApk = {
                actionTarget = null
                exportPkg = info.packageName
                exportLauncher.launch(
                    HPackages.getUnhiddenPackageInfoOrNull(info.packageName)?.exportFileName
                        ?: info.packageName
                )
            },
            onUninstall = {
                actionTarget = null
                uninstallTarget =
                    info.packageName to info.loadLabel(context.packageManager).toString()
            },
            onReinstall = {
                actionTarget = null
                if (AppManager.reinstallApp(info.packageName)) vm.updateAppList() else HUI.showToast(
                    R.string.operation_failed
                )
            },
        )
    }

    uninstallTarget?.let { (pkg, name) ->
        val self = pkg == app.packageName
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text(name) },
            text = { Text(stringResource(R.string.msg_uninstall)) },
            confirmButton = {
                TextButton(onClick = {
                    uninstallTarget = null
                    when {
                        self && HPolicy.isDeviceOwnerActive -> (context as? com.aistra.hail.ui.main.MainActivity)?.ownerRemoveDialog()
                        self && HPolicy.isProfileOwner -> HPolicy.removeProfileOwner()
                        self && HPolicy.isAdminActive -> HPolicy.removeActiveAdmin()
                        else -> {
                            if (AppManager.uninstallApp(pkg)) vm.updateAppList()
                        }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    uninstallTarget = null
                }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun appsLetter(info: ApplicationInfo): Char {
    val label = info.loadLabel(app.packageManager)
    return if (HailData.groupApps && HailData.groupMode == HailData.GROUP_LETTER_FIRST_CHAR)
        AppNameIndex.firstChar(label) else AppNameIndex.firstLetter(label)
}

private fun userSystemGroups(apps: List<ApplicationInfo>): List<AppsItem> {
    val user = apps.filterNot { it.isSystemApp() }
    val system = apps.filter { it.isSystemApp() }
    return buildList {
        if (user.isNotEmpty()) {
            add(AppsItem.Header(app.getString(R.string.group_user_apps))); user.forEach {
                add(
                    AppsItem.App(it, AppManager.isAppFrozen(it.packageName))
                )
            }
        }
        if (system.isNotEmpty()) {
            add(AppsItem.Header(app.getString(R.string.group_system_apps))); system.forEach {
                add(
                    AppsItem.App(it, AppManager.isAppFrozen(it.packageName))
                )
            }
        }
    }
}

private fun letterGroups(
    apps: List<ApplicationInfo>,
    letterOf: (ApplicationInfo) -> Char
): List<AppsItem> {
    val groups = linkedMapOf<Char, MutableList<ApplicationInfo>>()
    apps.forEach { groups.getOrPut(letterOf(it)) { mutableListOf() }.add(it) }
    return buildList {
        groups.entries.sortedWith(compareBy({ it.key == '#' }, { it.key }))
            .forEach { (letter, list) ->
                add(AppsItem.Header(if (letter == '#') app.getString(R.string.group_other) else letter.toString()))
                list.forEach { add(AppsItem.App(it, AppManager.isAppFrozen(it.packageName))) }
            }
    }
}

private fun ApplicationInfo.isSystemApp(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsGridItem(
    info: ApplicationInfo,
    frozen: Boolean,
    editMode: Boolean,
    checked: Boolean,
    gray: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Box {
            AppIcon(info = info, size = 56.dp, grayscale = gray)
            if (editMode) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            } else if (frozen) {
                FrozenBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        AppLabel(
            name = info.loadLabel(app.packageManager),
            frozen = false,
            whitelisted = false,
            selected = false,
            notFound = HPackages.isAppUninstalled(info.packageName),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsListItem(
    info: ApplicationInfo,
    frozen: Boolean,
    editMode: Boolean,
    checked: Boolean,
    gray: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(44.dp)) {
            AppIcon(info = info, size = 44.dp, grayscale = gray)
            if (!editMode && frozen) {
                FrozenBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AppLabel(
                name = info.loadLabel(app.packageManager),
                frozen = false,
                whitelisted = false,
                selected = false,
                notFound = HPackages.isAppUninstalled(info.packageName),
            )
            Text(
                info.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (editMode) {
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun AppsMenuSheet(
    onDismiss: () -> Unit,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    onLayout: () -> Unit,
    onGroupMode: () -> Unit,
) {
    val context = LocalContext.current
    val entries = remember(HailData.uiRevision) {
        buildList {
            add(
                SheetEntry.Action(
                    Icons.Outlined.Sort,
                    context.getString(R.string.sort_name),
                    HailData.sortBy == HailData.SORT_NAME
                ) { HailData.changeAppsSort(HailData.SORT_NAME); onSort() })
            add(
                SheetEntry.Action(
                    Icons.Outlined.Sort,
                    context.getString(R.string.sort_install),
                    HailData.sortBy == HailData.SORT_INSTALL
                ) { HailData.changeAppsSort(HailData.SORT_INSTALL); onSort() })
            add(
                SheetEntry.Action(
                    Icons.Outlined.Sort,
                    context.getString(R.string.sort_update),
                    HailData.sortBy == HailData.SORT_UPDATE
                ) { HailData.changeAppsSort(HailData.SORT_UPDATE); onSort() })
            add(SheetEntry.Divider)
            add(
                SheetEntry.Action(
                    Icons.Outlined.AcUnit,
                    context.getString(R.string.filter_frozen_apps),
                    HailData.filterFrozenApps
                ) {
                    HailData.changeAppsFilter(
                        HailData.FILTER_FROZEN_APPS,
                        !HailData.filterFrozenApps
                    ); onFilter()
                })
            add(
                SheetEntry.Action(
                    Icons.Outlined.AcUnit,
                    context.getString(R.string.filter_unfrozen_apps),
                    HailData.filterUnfrozenApps
                ) {
                    HailData.changeAppsFilter(
                        HailData.FILTER_UNFROZEN_APPS,
                        !HailData.filterUnfrozenApps
                    ); onFilter()
                })
            add(SheetEntry.Divider)
            add(
                SheetEntry.Action(
                    Icons.Outlined.Group,
                    context.getString(R.string.group_user_apps),
                    HailData.filterUserApps
                ) {
                    HailData.changeAppsFilter(
                        HailData.FILTER_USER_APPS,
                        !HailData.filterUserApps
                    ); onFilter()
                })
            add(
                SheetEntry.Action(
                    Icons.Outlined.Group,
                    context.getString(R.string.group_system_apps),
                    HailData.filterSystemApps
                ) {
                    HailData.changeAppsFilter(
                        HailData.FILTER_SYSTEM_APPS,
                        !HailData.filterSystemApps
                    ); onFilter()
                })
            add(SheetEntry.Divider)
            add(
                SheetEntry.Action(
                    Icons.Outlined.GridView,
                    context.getString(R.string.layout_grid),
                    HailData.appsLayout == HailData.LAYOUT_GRID
                ) { HailData.appsLayout = HailData.LAYOUT_GRID; onLayout() })
            add(
                SheetEntry.Action(
                    Icons.Outlined.ViewList,
                    context.getString(R.string.layout_list),
                    HailData.appsLayout == HailData.LAYOUT_LIST
                ) { HailData.appsLayout = HailData.LAYOUT_LIST; onLayout() })
            add(
                SheetEntry.Stepper(
                    Icons.Outlined.GridView,
                    context.getString(R.string.grid_columns),
                    HailData.gridColumns,
                    1..5
                ) { HailData.gridColumns = it; onLayout() })
            add(SheetEntry.Divider)
            add(
                SheetEntry.Action(
                    Icons.Outlined.Group,
                    context.getString(R.string.group_apps),
                    HailData.groupApps
                ) { HailData.groupApps = !HailData.groupApps; onGroupMode() })
            add(SheetEntry.Header(context.getString(R.string.group_mode)))
            add(
                SheetEntry.Action(
                    Icons.Outlined.Group,
                    context.getString(R.string.group_user_system),
                    HailData.groupMode == HailData.GROUP_USER_SYSTEM,
                    radio = true
                ) {
                    HailData.groupMode = HailData.GROUP_USER_SYSTEM; HailData.groupApps =
                    true; onGroupMode()
                })
            add(
                SheetEntry.Action(
                    Icons.Outlined.Group,
                    context.getString(R.string.group_letter_pinyin),
                    HailData.groupMode == HailData.GROUP_LETTER_PINYIN,
                    radio = true
                ) {
                    HailData.groupMode = HailData.GROUP_LETTER_PINYIN; HailData.groupApps =
                    true; onGroupMode()
                })
            add(
                SheetEntry.Action(
                    Icons.Outlined.Group,
                    context.getString(R.string.group_letter_first_char),
                    HailData.groupMode == HailData.GROUP_LETTER_FIRST_CHAR,
                    radio = true
                ) {
                    HailData.groupMode = HailData.GROUP_LETTER_FIRST_CHAR; HailData.groupApps =
                    true; onGroupMode()
                })
        }
    }
    MenuSheet(onDismiss = onDismiss, entries = entries)
}

@Composable
private fun AppContextSheet(
    info: ApplicationInfo,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onExportClipboard: () -> Unit,
    onExtractApk: () -> Unit,
    onUninstall: () -> Unit,
    onReinstall: () -> Unit,
) {
    val context = LocalContext.current
    MenuSheet(
        onDismiss = onDismiss,
        entries = listOf(
            SheetEntry.Action(
                Icons.Outlined.Info,
                context.getString(R.string.action_details)
            ) { onDetails() },
            SheetEntry.Action(
                Icons.Outlined.ContentCopy,
                context.getString(R.string.action_export_clipboard)
            ) { onExportClipboard() },
            SheetEntry.Action(
                Icons.Outlined.SaveAlt,
                context.getString(R.string.action_extract_apk)
            ) { onExtractApk() },
            SheetEntry.Divider,
            SheetEntry.Action(
                Icons.Outlined.Delete,
                context.getString(R.string.action_uninstall)
            ) { onUninstall() },
            SheetEntry.Action(
                Icons.Outlined.Refresh,
                context.getString(R.string.action_reinstall)
            ) { onReinstall() },
        ),
    )
}
