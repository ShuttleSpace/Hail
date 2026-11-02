package com.aistra.hail.ui.settings

import android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.compose.ListBottomSheet
import com.aistra.hail.ui.compose.MenuSheet
import com.aistra.hail.ui.compose.SheetEntry
import com.aistra.hail.utils.*
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.*
import rikka.shizuku.Shizuku

private data class SheetGroup(val title: String?, val values: List<String>, val entries: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAbout: () -> Unit,
    onRemoveOwner: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var showSheet by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showPinShortcut by remember { mutableStateOf(false) }

    fun entry(values: List<String>, @ArrayRes entriesId: Int, value: String): String =
        context.resources.getStringArray(entriesId)[values.indexOf(value).coerceAtLeast(0)]

    @Composable
    fun workingModeGroups(): List<SheetGroup> {
        val stop = stringResource(R.string.mode_action_stop)
        val disable = stringResource(R.string.mode_action_disable)
        val hide = stringResource(R.string.mode_action_hide)
        val suspend = stringResource(R.string.mode_action_suspend)
        return listOf(
            SheetGroup(null, listOf(HailData.MODE_DEFAULT), listOf(stringResource(R.string.mode_default))),
            SheetGroup(stringResource(R.string.working_mode_shizuku), listOf(HailData.MODE_SHIZUKU_STOP, HailData.MODE_SHIZUKU_DISABLE, HailData.MODE_SHIZUKU_HIDE, HailData.MODE_SHIZUKU_SUSPEND), listOf(stop, disable, hide, suspend)),
            SheetGroup(stringResource(R.string.working_mode_root), listOf(HailData.MODE_SU_STOP, HailData.MODE_SU_DISABLE, HailData.MODE_SU_HIDE, HailData.MODE_SU_SUSPEND), listOf(stop, disable, hide, suspend)),
            SheetGroup(stringResource(R.string.working_mode_owner), listOf(HailData.MODE_OWNER_HIDE, HailData.MODE_OWNER_SUSPEND), listOf(hide, suspend)),
            SheetGroup(stringResource(R.string.working_mode_dhizuku), listOf(HailData.MODE_DHIZUKU_HIDE, HailData.MODE_DHIZUKU_SUSPEND), listOf(hide, suspend)),
            SheetGroup(stringResource(R.string.working_mode_island), listOf(HailData.MODE_ISLAND_HIDE, HailData.MODE_ISLAND_SUSPEND), listOf(hide, suspend)),
            SheetGroup(stringResource(R.string.working_mode_privapp), listOf(HailData.MODE_PRIVAPP_STOP, HailData.MODE_PRIVAPP_DISABLE), listOf(stop, disable)),
            SheetGroup(stringResource(R.string.working_mode_jailbreak), listOf(HailData.MODE_JAILBREAK_DISABLE), listOf(disable)),
        )
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
        when {
            mode.startsWith(HailData.OWNER) -> if (!HPolicy.isDeviceOwnerActive) {
                HUI.showToast(R.string.title_set_owner)
                return false
            }

            mode.startsWith(HailData.DHIZUKU) -> return runCatching {
                Dhizuku.init(app)
                if (Dhizuku.isPermissionGranted()) true
                else {
                    scope.launch {
                        val result = callbackFlow {
                            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                override fun onRequestPermission(grantResult: Int) {
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                            })
                            awaitClose()
                        }.first()
                        if (result) {
                            rememberState.value = mode
                            if (HTarget.O) HDhizuku.setDelegatedScopes()
                        }
                    }
                    false
                }
            }.getOrElse { HLog.e(it); HUI.showToast(R.string.permission_denied); false }

            mode.startsWith(HailData.SU) -> if (!HShell.checkSU) {
                HUI.showToast(R.string.permission_denied); return false
            }

            mode == HailData.MODE_JAILBREAK_DISABLE -> if (
                ContextCompat.checkSelfPermission(context, CHANGE_COMPONENT_ENABLED_STATE) == PackageManager.PERMISSION_DENIED
                && !HShell.grantPermission(context.packageName, CHANGE_COMPONENT_ENABLED_STATE)
            ) { HUI.showToast(R.string.permission_denied); return false }

            mode.startsWith(HailData.SHIZUKU) -> return runCatching {
                when {
                    Shizuku.isPreV11() -> throw IllegalStateException("unsupported shizuku version")
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                    Shizuku.shouldShowRequestPermissionRationale() -> { HUI.showToast(R.string.permission_denied); false }
                    else -> {
                        scope.launch {
                            val result = callbackFlow {
                                val code = 0
                                val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                                    if (requestCode != code) return@OnRequestPermissionResultListener
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.requestPermission(code)
                                awaitClose { Shizuku.removeRequestPermissionResultListener(listener) }
                            }.first()
                            if (result) rememberState.value = mode
                        }
                        false
                    }
                }
            }.getOrElse { HLog.e(it); HUI.showToast(R.string.shizuku_missing); false }

            mode.startsWith(HailData.ISLAND) -> return runCatching {
                when {
                    mode == HailData.MODE_ISLAND_HIDE && HIsland.freezePermissionGranted() -> true
                    mode == HailData.MODE_ISLAND_SUSPEND && HIsland.suspendPermissionGranted() -> true
                    else -> {
                        scope.launch {
                            permissionLauncher.launch(
                                if (mode == HailData.MODE_ISLAND_HIDE) HIsland.PERMISSION_FREEZE_PACKAGE
                                else HIsland.PERMISSION_SUSPEND_PACKAGE
                            )
                        }
                        false
                    }
                }
            }.getOrElse { HLog.e(it); HUI.showToast(R.string.permission_denied); false }.also { if (it) HIsland.checkOwnerApp() }

            mode.startsWith(HailData.PRIVAPP) -> if (!HPackages.isPrivilegedApp(app.packageName)) {
                HUI.showToast(R.string.permission_denied); return false
            }
        }
        return true
    }

    fun LazyListScope.horizontalDivider() = item { HorizontalDivider() }

    fun LazyListScope.switchPreference(
        rememberState: @Composable () -> MutableState<Boolean>,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = item(key = titleId, contentType = "SwitchPreference") {
        val state = rememberState()
        SwitchPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            title = { Text(stringResource(titleId)) },
            enabled = enabled,
            icon = { Icon(icon, null) },
        )
    }

    fun LazyListScope.switchPreference(
        key: String, defaultValue: Boolean,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int, enabled: Boolean = true, icon: ImageVector,
    ) = switchPreference({ rememberPreferenceState(key, defaultValue) }, onValueChange, titleId, enabled, icon)

    fun LazyListScope.sheetListPreference(
        key: String, @StringRes titleId: Int, icon: ImageVector,
        values: List<String>, entryOf: @Composable (String) -> String,
        current: String, onSelected: (String) -> Unit,
    ) = item(key = key, contentType = "SheetListPreference") {
        var show by remember { mutableStateOf(false) }
        val currentEntry = entryOf(current)
        Preference(
            onClick = { show = true },
            title = { Text(stringResource(titleId)) },
            icon = { Icon(icon, null) },
            summary = { Text(currentEntry) },
        )
        if (show) {
            ListBottomSheet(
                title = stringResource(titleId),
                entries = values.map { entryOf(it) },
                current = currentEntry,
                onDismiss = { show = false },
                onSelected = { onSelected(it); show = false },
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                actions = {
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                    }
                },
            )
        },
    ) { padding ->
        ProvidePreferenceLocals {
            val autoFreezeAfterLock = rememberPreferenceState(HailData.AUTO_FREEZE_AFTER_LOCK, false)
            val workingModeState = rememberPreferenceState(HailData.WORKING_MODE, HailData.MODE_DEFAULT)
            var showWorkingModeSheet by remember { mutableStateOf(false) }
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {

                preference(
                    key = HailData.WORKING_MODE,
                    title = { Text(stringResource(R.string.working_mode)) },
                    icon = { Icon(Icons.Outlined.Adb, null) },
                    summary = { Text(entry(HailData.WORKING_MODE_VALUES, R.array.working_mode_entries, workingModeState.value)) },
                    onClick = { showWorkingModeSheet = true },
                )
                switchPreference(HailData.BIOMETRIC_LOGIN, false, titleId = R.string.action_biometric, icon = Icons.Outlined.Fingerprint)
                horizontalDivider()
                preferenceCategory(key = "customize", title = { Text(stringResource(R.string.title_customize)) })
                sheetListPreference(
                    key = HailData.GROUP_MODE, titleId = R.string.group_mode, icon = Icons.Outlined.Group,
                    values = listOf(HailData.GROUP_USER_SYSTEM.toString(), HailData.GROUP_LETTER_PINYIN.toString(), HailData.GROUP_LETTER_FIRST_CHAR.toString()),
                    entryOf = { when (it.toInt()) {
                        HailData.GROUP_USER_SYSTEM -> stringResource(R.string.group_user_system)
                        HailData.GROUP_LETTER_PINYIN -> stringResource(R.string.group_letter_pinyin)
                        else -> stringResource(R.string.group_letter_first_char)
                    } },
                    current = HailData.groupMode.toString(),
                    onSelected = { HailData.groupMode = it.toInt() },
                )
                sheetListPreference(
                    key = HailData.APP_THEME, titleId = R.string.app_theme, icon = Icons.Outlined.DarkMode,
                    values = HailData.APP_THEME_VALUES,
                    entryOf = { entry(HailData.APP_THEME_VALUES, R.array.app_theme_entries, it) },
                    current = HailData.appTheme,
                    onSelected = { app.setAppTheme(it) },
                )
                sheetListPreference(
                    key = HailData.ICON_PACK, titleId = R.string.icon_pack, icon = Icons.Outlined.Palette,
                    values = iconPackValues(),
                    entryOf = { iconPackName(it) },
                    current = HailData.iconPack,
                    onSelected = { AppIconCache.clear() },
                )
                switchPreference(key = HailData.COMPACT_ICON, defaultValue = false, titleId = R.string.compact_icon, icon = Icons.Outlined.Apps)
                switchPreference(key = HailData.SYNTHESIZE_ADAPTIVE_ICONS, defaultValue = false, titleId = R.string.synthesize_adaptive_icons, icon = Icons.Outlined.Layers)
                sliderPreference(key = HailData.HOME_FONT_SIZE, defaultValue = 14f, title = { Text(stringResource(R.string.home_font_size)) }, valueRange = 11f..16f, valueSteps = 4, icon = { Icon(Icons.Outlined.TextFields, null) }, valueText = { Text("%.0f".format(it)) })
                switchPreference(key = HailData.FUZZY_SEARCH, defaultValue = false, titleId = R.string.fuzzy_search, icon = Icons.Outlined.ManageSearch)
                switchPreference(key = HailData.NINE_KEY_SEARCH, defaultValue = false, titleId = R.string.nine_key, icon = Icons.Outlined.Dialpad)
                sheetListPreference(
                    key = HailData.TILE_ACTION, titleId = R.string.tile_action, icon = Icons.Outlined.DashboardCustomize,
                    values = HailData.TILE_ACTION_VALUES,
                    entryOf = { entry(HailData.TILE_ACTION_VALUES, R.array.tile_action_entries, it) },
                    current = HailData.tileAction,
                    onSelected = { },
                )
                horizontalDivider()
                preferenceCategory(key = "auto_freeze", title = { Text(stringResource(R.string.auto_freeze)) })
                switchPreference({ autoFreezeAfterLock }, { _, value -> app.setAutoFreezeService(value); true }, R.string.auto_freeze_after_lock, icon = Icons.Outlined.ScreenLockPortrait)
                sliderPreference(key = HailData.AUTO_FREEZE_DELAY, defaultValue = 0f, title = { Text(stringResource(R.string.auto_freeze_delay)) }, valueRange = 0f..30f, valueSteps = 29, enabled = { autoFreezeAfterLock.value }, icon = { Icon(Icons.Outlined.LockClock, null) }, valueText = { Text("%.0f".format(it)) })
                switchPreference(key = HailData.SKIP_WHILE_CHARGING, defaultValue = false, titleId = R.string.skip_while_charging, enabled = autoFreezeAfterLock.value, icon = Icons.Outlined.BatteryChargingFull)
                switchPreference(key = HailData.SKIP_FOREGROUND_APP, defaultValue = false, onValueChange = { _, value -> if (value && !HSystem.checkOpUsageStats(context)) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false } else true }, titleId = R.string.skip_foreground_app, enabled = autoFreezeAfterLock.value, icon = Icons.Outlined.Android)
                switchPreference(key = HailData.SKIP_NOTIFYING_APP, defaultValue = false, onValueChange = { _, value -> val g = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName); if (value && !g) { app.setAutoFreezeServiceEnabled(true); context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false } else true }, titleId = R.string.skip_notifying_app, enabled = autoFreezeAfterLock.value, icon = Icons.Outlined.NotificationsActive)
                horizontalDivider()
                preferenceCategory(key = "shortcuts", title = { Text(stringResource(R.string.title_shortcuts)) })
                preference(key = "add_pin_shortcut", title = { Text(stringResource(R.string.action_add_pin_shortcut)) }, icon = { Icon(Icons.Outlined.Shortcut, null) }, onClick = { showPinShortcut = true })
                sheetListPreference(
                    key = HailData.DYNAMIC_SHORTCUT_ACTION, titleId = R.string.dynamic_shortcut_action, icon = Icons.Outlined.AppShortcut,
                    values = HailData.DYNAMIC_SHORTCUT_ACTIONS,
                    entryOf = { entry(HailData.DYNAMIC_SHORTCUT_ACTIONS, R.array.dynamic_shortcut_entries, it) },
                    current = HailData.dynamicShortcutAction,
                    onSelected = { action -> HShortcuts.removeAllDynamicShortcuts(); HShortcuts.addDynamicShortcutAction(action) },
                )
                preference(key = "clear_dynamic_shortcuts", title = { Text(stringResource(R.string.action_clear_dynamic_shortcuts)) }, icon = { Icon(Icons.Outlined.CleaningServices, null) }, onClick = { HShortcuts.removeAllDynamicShortcuts(); HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction) })
                horizontalDivider()
                preference(key = "about", title = { Text(stringResource(R.string.title_about)) }, icon = { Icon(Icons.Outlined.Info, null) }, onClick = onNavigateToAbout)
            }
            if (showWorkingModeSheet) {
                WorkingModeBottomSheet(
                    current = workingModeState.value,
                    onDismiss = { showWorkingModeSheet = false },
                    onSelected = { mode -> onWorkingModeChange(workingModeState, mode).also { if (it) workingModeState.value = mode } },
                )
            }
        }
    }

    if (showSheet) {
        val canTerminal = HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(HailData.SHIZUKU)
        MenuSheet(
            onDismiss = { showSheet = false },
            entries = buildList {
                add(SheetEntry.Action(Icons.Outlined.HelpOutline, context.getString(R.string.action_help)) { HUI.openLink(HailData.URL_README) })
                if (canTerminal) add(SheetEntry.Action(Icons.Outlined.Terminal, context.getString(R.string.action_terminal)) { showTerminal = true })
                if (HPolicy.isDeviceOwnerActive) add(SheetEntry.Action(Icons.Outlined.PersonOff, context.getString(R.string.title_remove_owner)) { onRemoveOwner() })
            },
        )
    }

    if (showPinShortcut) {
        PinShortcutSheet(onDismiss = { showPinShortcut = false })
    }

    if (showTerminal) {
        TerminalDialog(onDismiss = { showTerminal = false })
    }
}

private fun iconPackValues(): List<String> = mutableListOf(HailData.ACTION_NONE).apply {
    addAll(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME").let {
        if (HTarget.T) app.packageManager.queryIntentActivities(it, PackageManager.ResolveInfoFlags.of(0))
        else app.packageManager.queryIntentActivities(it, 0)
    }.map { it.activityInfo.packageName })
}

private fun iconPackName(pack: String): String =
    if (pack == HailData.ACTION_NONE) app.getString(R.string.action_none)
    else HPackages.getApplicationInfoOrNull(pack)?.loadLabel(app.packageManager)?.toString() ?: pack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkingModeBottomSheet(current: String, onDismiss: () -> Unit, onSelected: (String) -> Boolean) {
    val context = LocalContext.current
    val stop = stringResource(R.string.mode_action_stop)
    val disable = stringResource(R.string.mode_action_disable)
    val hide = stringResource(R.string.mode_action_hide)
    val suspend = stringResource(R.string.mode_action_suspend)
    val groups = listOf(
        SheetGroup(null, listOf(HailData.MODE_DEFAULT), listOf(stringResource(R.string.mode_default))),
        SheetGroup(stringResource(R.string.working_mode_shizuku), listOf(HailData.MODE_SHIZUKU_STOP, HailData.MODE_SHIZUKU_DISABLE, HailData.MODE_SHIZUKU_HIDE, HailData.MODE_SHIZUKU_SUSPEND), listOf(stop, disable, hide, suspend)),
        SheetGroup(stringResource(R.string.working_mode_root), listOf(HailData.MODE_SU_STOP, HailData.MODE_SU_DISABLE, HailData.MODE_SU_HIDE, HailData.MODE_SU_SUSPEND), listOf(stop, disable, hide, suspend)),
        SheetGroup(stringResource(R.string.working_mode_owner), listOf(HailData.MODE_OWNER_HIDE, HailData.MODE_OWNER_SUSPEND), listOf(hide, suspend)),
        SheetGroup(stringResource(R.string.working_mode_dhizuku), listOf(HailData.MODE_DHIZUKU_HIDE, HailData.MODE_DHIZUKU_SUSPEND), listOf(hide, suspend)),
        SheetGroup(stringResource(R.string.working_mode_island), listOf(HailData.MODE_ISLAND_HIDE, HailData.MODE_ISLAND_SUSPEND), listOf(hide, suspend)),
        SheetGroup(stringResource(R.string.working_mode_privapp), listOf(HailData.MODE_PRIVAPP_STOP, HailData.MODE_PRIVAPP_DISABLE), listOf(stop, disable)),
        SheetGroup(stringResource(R.string.working_mode_jailbreak), listOf(HailData.MODE_JAILBREAK_DISABLE), listOf(disable)),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
            items(groups) { group ->
                group.title?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp))
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(2.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        group.values.forEachIndexed { index, value ->
                            val selected = value == current
                            Box(
                                modifier = Modifier.weight(1f).selectable(selected = selected, role = Role.RadioButton, onClick = { if (onSelected(value)) onDismiss() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                val textModifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)
                                if (selected) {
                                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                                        Text(group.entries[index], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = textModifier)
                                    }
                                } else {
                                    Text(group.entries[index], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = textModifier)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun PinShortcutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tagAction by remember { mutableStateOf<String?>(null) }
    if (tagAction != null) {
        ListBottomSheet(
            title = context.getString(if (tagAction == HailApi.ACTION_FREEZE_TAG) R.string.action_freeze_tag else R.string.action_unfreeze_tag),
            entries = HailData.tags.map { it.first },
            current = "",
            onDismiss = { tagAction = null },
            onSelected = { tag ->
                val action = tagAction!!
                HShortcuts.addPinShortcut(
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        context, if (action == HailApi.ACTION_FREEZE_TAG) R.drawable.ic_round_frozen_shortcut else R.drawable.ic_round_unfrozen_shortcut
                    )!!,
                    action + tag, tag, HailApi.getIntentForTag(action, tag)
                )
                tagAction = null
                onDismiss()
            },
        )
        return
    }
    MenuSheet(
        onDismiss = onDismiss,
        entries = listOf(
            SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze_tag)) { tagAction = HailApi.ACTION_FREEZE_TAG },
            SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze_tag)) { tagAction = HailApi.ACTION_UNFREEZE_TAG },
            SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze_all)) {
                HShortcuts.addPinShortcut(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_round_frozen_shortcut)!!, HailApi.ACTION_FREEZE_ALL, context.getString(R.string.action_freeze_all), Intent(HailApi.ACTION_FREEZE_ALL)); onDismiss()
            },
            SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_unfreeze_all)) {
                HShortcuts.addPinShortcut(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_round_unfrozen_shortcut)!!, HailApi.ACTION_UNFREEZE_ALL, context.getString(R.string.action_unfreeze_all), Intent(HailApi.ACTION_UNFREEZE_ALL)); onDismiss()
            },
            SheetEntry.Action(Icons.Outlined.AcUnit, context.getString(R.string.action_freeze_non_whitelisted)) {
                HShortcuts.addPinShortcut(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_round_frozen_shortcut)!!, HailApi.ACTION_FREEZE_NON_WHITELISTED, context.getString(R.string.action_freeze_non_whitelisted), Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)); onDismiss()
            },
            SheetEntry.Action(Icons.Outlined.Lock, context.getString(R.string.action_lock)) {
                HShortcuts.addPinShortcut(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_outline_lock_shortcut)!!, HailApi.ACTION_LOCK, context.getString(R.string.action_lock), Intent(HailApi.ACTION_LOCK)); onDismiss()
            },
            SheetEntry.Action(Icons.Outlined.Lock, context.getString(R.string.action_lock_freeze)) {
                HShortcuts.addPinShortcut(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_outline_lock_shortcut)!!, HailApi.ACTION_LOCK_FREEZE, context.getString(R.string.action_lock_freeze), Intent(HailApi.ACTION_LOCK_FREEZE)); onDismiss()
            },
        ),
    )
}

@Composable
private fun TerminalDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_terminal)) },
        text = {
            Column {
                OutlinedTextField(value = command, onValueChange = { command = it }, singleLine = true, label = { Text(stringResource(R.string.command)) })
                result?.let { (code, msg) ->
                    if (!msg.isNullOrBlank() || code != 0) {
                        Text(msg ?: stringResource(R.string.operation_failed, code.toString()), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { result = AppManager.execute(command) } }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
