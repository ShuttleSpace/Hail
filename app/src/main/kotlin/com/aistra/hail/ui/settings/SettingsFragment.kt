package com.aistra.hail.ui.settings

import android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.ui.common.MenuBottomSheet
import com.aistra.hail.ui.common.SheetEntry
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.*
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
class SettingsFragment : MainFragment(), MenuProvider {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    ProvidePreferenceLocals {
                        SettingsScreen()
                    }
                }
            }
        }
    }


    private data class ListSheetConfig(val title: String, val entries: List<String>, val current: String, val onSelected: (String) -> Unit)

    private data class SheetGroup(val title: String, val values: List<String>, val entries: List<String>)

    @Composable
    private fun workingModeGroups(): List<SheetGroup> {
        val shizuku = stringResource(R.string.working_mode_shizuku)
        val root = stringResource(R.string.working_mode_root)
        val owner = stringResource(R.string.working_mode_owner)
        val dhizuku = stringResource(R.string.working_mode_dhizuku)
        val island = stringResource(R.string.working_mode_island)
        val privapp = stringResource(R.string.working_mode_privapp)
        val jailbreak = stringResource(R.string.working_mode_jailbreak)
        return listOf(
            SheetGroup(shizuku, listOf(HailData.MODE_SHIZUKU_STOP, HailData.MODE_SHIZUKU_DISABLE, HailData.MODE_SHIZUKU_HIDE, HailData.MODE_SHIZUKU_SUSPEND), listOf(stringResource(R.string.mode_shizuku_stop), stringResource(R.string.mode_shizuku_disable), stringResource(R.string.mode_shizuku_hide), stringResource(R.string.mode_shizuku_suspend))),
            SheetGroup(root, listOf(HailData.MODE_SU_STOP, HailData.MODE_SU_DISABLE, HailData.MODE_SU_HIDE, HailData.MODE_SU_SUSPEND), listOf(stringResource(R.string.mode_su_stop), stringResource(R.string.mode_su_disable), stringResource(R.string.mode_su_hide), stringResource(R.string.mode_su_suspend))),
            SheetGroup(owner, listOf(HailData.MODE_OWNER_HIDE, HailData.MODE_OWNER_SUSPEND), listOf(stringResource(R.string.mode_owner_hide), stringResource(R.string.mode_owner_suspend))),
            SheetGroup(dhizuku, listOf(HailData.MODE_DHIZUKU_HIDE, HailData.MODE_DHIZUKU_SUSPEND), listOf(stringResource(R.string.mode_dhizuku_hide), stringResource(R.string.mode_dhizuku_suspend))),
            SheetGroup(island, listOf(HailData.MODE_ISLAND_HIDE, HailData.MODE_ISLAND_SUSPEND), listOf(stringResource(R.string.mode_island_hide), stringResource(R.string.mode_island_suspend))),
            SheetGroup(privapp, listOf(HailData.MODE_PRIVAPP_STOP, HailData.MODE_PRIVAPP_DISABLE), listOf(stringResource(R.string.mode_privapp_stop), stringResource(R.string.mode_privapp_disable))),
            SheetGroup(jailbreak, listOf(HailData.MODE_JAILBREAK_DISABLE), listOf(stringResource(R.string.mode_jailbreak_disable)))
        )
    }

    private fun LazyListScope.horizontalDivider() = item { HorizontalDivider() }

    private fun LazyListScope.switchPreference(
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
            title = { Text(text = stringResource(titleId)) },
            enabled = enabled,
            icon = { Icon(imageVector = icon, contentDescription = null) })
    }

    private fun LazyListScope.switchPreference(
        key: String,
        defaultValue: Boolean,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = switchPreference(
        rememberState = { rememberPreferenceState(key, defaultValue) },
        onValueChange = onValueChange,
        titleId = titleId,
        enabled = enabled,
        icon = icon
    )

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @StringRes titleId: Int,
        icon: ImageVector,
        summary: @Composable (String) -> String,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU,
        valueToText: (String) -> String
    ) = item(key = key, contentType = "ListPreference") {
        val state = rememberPreferenceState(key, defaultValue)
        ListPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            values = values,
            title = { Text(text = stringResource(titleId)) },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            summary = { Text(text = summary(state.value)) },
            type = type,
            valueToText = { AnnotatedString(valueToText(it)) })
    }

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @ArrayRes entriesId: Int,
        @StringRes titleId: Int,
        icon: ImageVector,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU
    ) = listPreference(
        key = key,
        defaultValue = defaultValue,
        onValueChange = onValueChange,
        values = values,
        titleId = titleId,
        icon = icon,
        summary = { it.toEntry(values, entriesId) },
        type = type,
        valueToText = { it.toEntry(values, entriesId) })

    private fun String.toEntry(values: List<String>, @ArrayRes entriesId: Int): String =
        resources.getStringArray(entriesId)[values.indexOf(this)]

    @Composable
    private fun SettingsScreen() {
        val autoFreezeAfterLock = rememberPreferenceState(HailData.AUTO_FREEZE_AFTER_LOCK, false)
        val workingModeState = rememberPreferenceState(HailData.WORKING_MODE, HailData.MODE_DEFAULT)
        var showWorkingModeSheet by remember { mutableStateOf(false) }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            preference(
                key = HailData.WORKING_MODE,
                title = { Text(text = stringResource(R.string.working_mode)) },
                icon = { Icon(imageVector = Icons.Outlined.Adb, contentDescription = null) },
                summary = { Text(text = workingModeState.value.toWorkingModeEntry()) },
                onClick = { showWorkingModeSheet = true }
            )
            switchPreference(
                key = HailData.BIOMETRIC_LOGIN,
                defaultValue = false,
                onValueChange = { _, value -> if (value) resetDynamicShortcuts(); true },
                titleId = R.string.action_biometric,
                icon = Icons.Outlined.Fingerprint
            )
            horizontalDivider()
            preferenceCategory(key = "customize", title = { Text(text = stringResource(R.string.title_customize)) })
            sheetListPreference(
                key = HailData.GROUP_MODE,
                titleId = R.string.group_mode,
                icon = Icons.Outlined.Group,
                values = listOf(HailData.GROUP_USER_SYSTEM.toString(), HailData.GROUP_LETTER_PINYIN.toString(), HailData.GROUP_LETTER_FIRST_CHAR.toString()),
                entryOf = { when (it.toInt()) {
                    HailData.GROUP_USER_SYSTEM -> stringResource(R.string.group_user_system)
                    HailData.GROUP_LETTER_PINYIN -> stringResource(R.string.group_letter_pinyin)
                    else -> stringResource(R.string.group_letter_first_char)
                } },
                current = HailData.groupMode.toString(),
                onSelected = { HailData.groupMode = it.toInt() }
            )
            sheetListPreference(
                key = HailData.APP_THEME,
                titleId = R.string.app_theme,
                icon = Icons.Outlined.DarkMode,
                values = HailData.APP_THEME_VALUES,
                entriesId = R.array.app_theme_entries,
                current = HailData.appTheme,
                onSelected = { app.setAppTheme(it) }
            )
            sheetListPreference(
                key = HailData.ICON_PACK,
                titleId = R.string.icon_pack,
                icon = Icons.Outlined.Palette,
                values = iconPackValues(),
                entryOf = { iconPackName(it) },
                current = HailData.iconPack,
                onSelected = { AppIconCache.clear() }
            )
            switchPreference(key = HailData.GRAYSCALE_ICON, defaultValue = true, titleId = R.string.grayscale_icon, icon = Icons.Outlined.FilterBAndW)
            switchPreference(key = HailData.COMPACT_ICON, defaultValue = false, titleId = R.string.compact_icon, icon = Icons.Outlined.Apps)
            switchPreference(key = HailData.SYNTHESIZE_ADAPTIVE_ICONS, defaultValue = false, titleId = R.string.synthesize_adaptive_icons, icon = Icons.Outlined.Layers)
            sliderPreference(key = HailData.HOME_FONT_SIZE, defaultValue = 14f, title = { Text(text = stringResource(R.string.home_font_size)) }, valueRange = 11f..16f, valueSteps = 4, icon = { Icon(imageVector = Icons.Outlined.TextFields, contentDescription = null) }, valueText = { Text(text = "%.0f".format(it)) })
            switchPreference(key = HailData.FUZZY_SEARCH, defaultValue = false, titleId = R.string.fuzzy_search, icon = Icons.AutoMirrored.Outlined.ManageSearch)
            switchPreference(key = HailData.NINE_KEY_SEARCH, defaultValue = false, titleId = R.string.nine_key, icon = Icons.Outlined.Dialpad)
            sheetListPreference(
                key = HailData.TILE_ACTION,
                titleId = R.string.tile_action,
                icon = Icons.Outlined.DashboardCustomize,
                values = HailData.TILE_ACTION_VALUES,
                entriesId = R.array.tile_action_entries,
                current = HailData.tileAction,
                onSelected = { }
            )
            horizontalDivider()
            preferenceCategory(key = "auto_freeze", title = { Text(text = stringResource(R.string.auto_freeze)) })
            switchPreference(rememberState = { autoFreezeAfterLock }, onValueChange = { _, value -> app.setAutoFreezeService(value); true }, titleId = R.string.auto_freeze_after_lock, icon = Icons.Outlined.ScreenLockPortrait)
            sliderPreference(key = HailData.AUTO_FREEZE_DELAY, defaultValue = 0f, title = { Text(text = stringResource(R.string.auto_freeze_delay)) }, valueRange = 0f..30f, valueSteps = 29, enabled = { autoFreezeAfterLock.value }, icon = { Icon(imageVector = Icons.Outlined.LockClock, contentDescription = null) }, valueText = { Text(text = "%.0f".format(it)) })
            switchPreference(key = HailData.SKIP_WHILE_CHARGING, defaultValue = false, titleId = R.string.skip_while_charging, enabled = autoFreezeAfterLock.value, icon = Icons.Outlined.BatteryChargingFull)
            switchPreference(key = HailData.SKIP_FOREGROUND_APP, defaultValue = false, onValueChange = { _, value -> if (value && !HSystem.checkOpUsageStats(requireContext())) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false } else true }, titleId = R.string.skip_foreground_app, enabled = autoFreezeAfterLock.value, icon = Icons.Outlined.Android)
            switchPreference(key = HailData.SKIP_NOTIFYING_APP, defaultValue = false, onValueChange = { _, value -> val g = NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().packageName); if (value && !g) { app.setAutoFreezeServiceEnabled(true); startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false } else true }, titleId = R.string.skip_notifying_app, enabled = autoFreezeAfterLock.value, icon = Icons.Outlined.NotificationsActive)
            horizontalDivider()
            preferenceCategory(key = "shortcuts", title = { Text(text = stringResource(R.string.title_shortcuts)) })
            preference(key = "add_pin_shortcut", title = { Text(text = stringResource(R.string.action_add_pin_shortcut)) }, icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Shortcut, contentDescription = null) }, onClick = ::addPinShortcut)
            sheetListPreference(
                key = HailData.DYNAMIC_SHORTCUT_ACTION,
                titleId = R.string.dynamic_shortcut_action,
                icon = Icons.Outlined.AppShortcut,
                values = HailData.DYNAMIC_SHORTCUT_ACTIONS,
                entriesId = R.array.dynamic_shortcut_entries,
                current = HailData.dynamicShortcutAction,
                onSelected = { action -> HShortcuts.removeAllDynamicShortcuts(); HShortcuts.addDynamicShortcutAction(action) }
            )
            preference(key = "clear_dynamic_shortcuts", title = { Text(text = stringResource(R.string.action_clear_dynamic_shortcuts)) }, icon = { Icon(imageVector = Icons.Outlined.CleaningServices, contentDescription = null) }, onClick = ::resetDynamicShortcuts)
            horizontalDivider()
            preference(key = "about", title = { Text(text = stringResource(R.string.title_about)) }, icon = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) }, onClick = { findNavController().navigate(R.id.nav_about) })
        }
        if (showWorkingModeSheet) {
            WorkingModeBottomSheet(
                current = workingModeState.value,
                onDismiss = { showWorkingModeSheet = false },
                onSelected = { onWorkingModeChange(workingModeState, it) }
            )
        }
    }

    private fun iconPackValues(): List<String> = mutableListOf(HailData.ACTION_NONE).apply {
        addAll(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME").let {
            if (HTarget.T) app.packageManager.queryIntentActivities(it, PackageManager.ResolveInfoFlags.of(0))
            else app.packageManager.queryIntentActivities(it, 0)
        }.map { it.activityInfo.packageName })
    }

    private fun String.toWorkingModeEntry(): String = toEntry(HailData.WORKING_MODE_VALUES, R.array.working_mode_entries)

    private fun LazyListScope.sheetListPreference(
        key: String,
        @StringRes titleId: Int,
        icon: ImageVector,
        values: List<String>,
        @ArrayRes entriesId: Int = 0,
        entryOf: @Composable (String) -> String = { if (entriesId != 0) it.toEntry(values, entriesId) else it },
        current: String,
        onSelected: (String) -> Unit
    ) = item(key = key, contentType = "SheetListPreference") {
        var show by remember { mutableStateOf(false) }
        val currentEntry = entryOf(current)
        Preference(
            onClick = { show = true },
            title = { Text(text = stringResource(titleId)) },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            summary = { Text(text = currentEntry) }
        )
        if (show) {
            ListBottomSheet(
                title = stringResource(titleId),
                entries = values.map { entryOf(it) },
                current = currentEntry,
                onDismiss = { show = false },
                onSelected = { onSelected(it); show = false }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WorkingModeBottomSheet(current: String, onDismiss: () -> Unit, onSelected: (String) -> Boolean) {
        val groups = workingModeGroups()
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
            LazyColumn(modifier = Modifier.padding(bottom = 16.dp)) {
                items(groups) { group ->
                    val selected = group.values.indexOf(current)
                    Text(text = group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp))
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 0.dp, shadowElevation = 0.dp, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            group.values.forEachIndexed { index, value ->
                                val selectedState = index == selected
                                Box(modifier = Modifier.weight(1f).clickable { if (onSelected(value)) onDismiss() }, contentAlignment = Alignment.Center) {
                                    if (selectedState) {
                                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                                            Text(text = group.entries[index], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                                        }
                                    } else {
                                        Text(text = group.entries[index], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
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
    private fun ListBottomSheet(title: String, entries: List<String>, current: String, onDismiss: () -> Unit, onSelected: (String) -> Unit) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
            LazyColumn(modifier = Modifier.padding(bottom = 16.dp)) {
                item { Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp)) }
                items(entries) { entry ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSelected(entry); onDismiss() }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = entry, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        RadioButton(selected = entry == current, onClick = null)
                    }
                }
                item { HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            }
        }
    }

    private fun resetDynamicShortcuts() {
        HShortcuts.removeAllDynamicShortcuts()
        HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
    }

    private fun iconPackName(pack: String): String = if (pack == HailData.ACTION_NONE) getString(R.string.action_none)
    else HPackages.getApplicationInfoOrNull(pack)?.loadLabel(app.packageManager)?.toString() ?: pack

    private fun addPinShortcut() {
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_add_pin_shortcut)
            .setItems(R.array.pin_shortcut_entries) { _, which ->
                when (which) {
                    0 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_freeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_frozen_shortcut
                                )!!,
                                HailApi.ACTION_FREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_FREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    1 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_unfrozen_shortcut
                                )!!,
                                HailApi.ACTION_UNFREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_UNFREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    2 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_ALL,
                        getString(R.string.action_freeze_all),
                        Intent(HailApi.ACTION_FREEZE_ALL)
                    )

                    3 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_unfrozen_shortcut
                        )!!,
                        HailApi.ACTION_UNFREEZE_ALL,
                        getString(R.string.action_unfreeze_all),
                        Intent(HailApi.ACTION_UNFREEZE_ALL)
                    )

                    4 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_NON_WHITELISTED,
                        getString(R.string.action_freeze_non_whitelisted),
                        Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)
                    )

                    5 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!, HailApi.ACTION_LOCK, getString(R.string.action_lock), Intent(HailApi.ACTION_LOCK)
                    )

                    6 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!,
                        HailApi.ACTION_LOCK_FREEZE,
                        getString(R.string.action_lock_freeze),
                        Intent(HailApi.ACTION_LOCK_FREEZE)
                    )
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
        // Show/hide terminal menu.
        activity.invalidateOptionsMenu()
        when {
            mode.startsWith(HailData.OWNER) -> if (!HPolicy.isDeviceOwnerActive) {
                MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.title_set_owner)
                    .setMessage(getString(R.string.msg_set_owner, HPolicy.ADB_COMMAND))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(HPolicy.ADB_COMMAND) }.show()
                    .findViewById<MaterialTextView>(android.R.id.message)?.setTextIsSelectable(true)
                return false
            }

            mode.startsWith(HailData.DHIZUKU) -> return runCatching {
                Dhizuku.init(app)
                when {
                    Dhizuku.isPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
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
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }

            mode.startsWith(HailData.SU) -> if (!HShell.checkSU) {
                HUI.showToast(R.string.permission_denied)
                return false
            }

            mode == HailData.MODE_JAILBREAK_DISABLE -> if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    CHANGE_COMPONENT_ENABLED_STATE
                ) == PackageManager.PERMISSION_DENIED
                && !HShell.grantPermission(requireContext().packageName, CHANGE_COMPONENT_ENABLED_STATE)
            ) {
                HUI.showToast(R.string.permission_denied)
                return false
            }

            mode.startsWith(HailData.SHIZUKU) -> return runCatching {
                when {
                    Shizuku.isPreV11() -> throw IllegalStateException("unsupported shizuku version")
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                    Shizuku.shouldShowRequestPermissionRationale() -> {
                        HUI.showToast(R.string.permission_denied)
                        false
                    }

                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                val shizukuRequestCode = 0
                                val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                                    if (requestCode != shizukuRequestCode) return@OnRequestPermissionResultListener
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.requestPermission(shizukuRequestCode)
                                awaitClose {
                                    Shizuku.removeRequestPermissionResultListener(listener)
                                }
                            }.first()
                            if (result) rememberState.value = mode
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.shizuku_missing)
                false
            }

            mode.startsWith(HailData.ISLAND) -> return runCatching {
                when {
                    mode == HailData.MODE_ISLAND_HIDE && HIsland.freezePermissionGranted() -> true
                    mode == HailData.MODE_ISLAND_SUSPEND && HIsland.suspendPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            requestPermissionLauncher.launch(
                                if (mode == HailData.MODE_ISLAND_HIDE) HIsland.PERMISSION_FREEZE_PACKAGE
                                else HIsland.PERMISSION_SUSPEND_PACKAGE
                            )
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }.also {
                if (it) {
                    HIsland.checkOwnerApp()
                }
            }

            mode.startsWith(HailData.PRIVAPP) -> if (!HPackages.isPrivilegedApp(app.packageName)) {
                HUI.showToast(R.string.permission_denied)
                return false
            }
        }

        return true
    }

    private suspend fun onTerminalResult(exitValue: Int, msg: String?) = withContext(Dispatchers.Main) {
        if (exitValue == 0 && msg.isNullOrBlank()) return@withContext
        MaterialAlertDialogBuilder(requireActivity()).apply {
            if (!msg.isNullOrBlank()) {
                if (exitValue != 0) {
                    setTitle(getString(R.string.operation_failed, exitValue.toString()))
                }
                setMessage(msg)
                setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(msg) }
            } else if (exitValue != 0) {
                setMessage(getString(R.string.operation_failed, exitValue.toString()))
            }
        }.setPositiveButton(android.R.string.ok, null).show().findViewById<MaterialTextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_more) showSettingsSheet()
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_settings, menu)
    }

    private fun showSettingsSheet() {
        MenuBottomSheet(requireContext()) {
            buildList {
                add(
                    SheetEntry.Action(R.drawable.ic_outline_help, R.string.action_help) {
                        HUI.openLink(HailData.URL_README)
                        true
                    })
                if (HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(
                        HailData.SHIZUKU
                    )
                ) add(
                    SheetEntry.Action(R.drawable.ic_baseline_terminal, R.string.action_terminal) {
                        showTerminalDialog()
                        true
                    })
                if (HPolicy.isDeviceOwnerActive) add(
                    SheetEntry.Action(R.drawable.ic_outline_remove_owner, R.string.title_remove_owner) {
                        (requireActivity() as MainActivity).ownerRemoveDialog()
                        true
                    })
            }
        }.show()
    }

    private fun showTerminalDialog() {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.command)
        binding.editText.run {
            setSingleLine()
            filters = arrayOf()
        }
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_terminal).setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val result = AppManager.execute(binding.editText.text.toString())
                    onTerminalResult(result.first, result.second)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}