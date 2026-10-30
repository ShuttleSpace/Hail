package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.CompoundButton
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentAppsBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.common.MenuBottomSheet
import com.aistra.hail.ui.common.SheetEntry
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.AppNameIndex
import com.aistra.hail.utils.HFiles
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.aistra.hail.views.HRecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class AppsFragment : MainFragment(), AppsAdapter.OnItemClickListener, AppsAdapter.OnItemCheckedChangeListener,
    MenuProvider {

    private val model: AppsViewModel by viewModels()

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var appsAdapter: AppsAdapter

    // Prevent the same data from being filtered twice in `onCreateView`
    private var lastAppsHash: Int = 0
    private lateinit var lastQuery: String
    private val isAppsChanged get() = model.apps.value.hashCode() != lastAppsHash
    private val isQueryChanged get() = model.query.value != lastQuery
    private var contextMenuInfo: ContextMenu.ContextMenuInfo? = null

    private var editMode: Boolean = false
    private var editSnapshot: Set<String> = emptySet()


    private var exportApkPkg: String? = null
    private val exportApk =
        registerForActivityResult(CreateDocument("application/vnd.android.package-archive")) { uri ->
            val exportApkPkg = exportApkPkg
            this.exportApkPkg = null
            if (exportApkPkg == null || uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val applicationInfo = HPackages.getApplicationInfoOrNull(exportApkPkg) ?: return@launch
                val dialog =
                    MaterialAlertDialogBuilder(activity).setView(R.layout.dialog_progress).setCancelable(false).show()
                runCatching {
                    withContext(Dispatchers.IO) {
                        FileInputStream(applicationInfo.sourceDir).use { source ->
                            activity.contentResolver.openOutputStream(uri, "rwt").use { target ->
                                if (target == null) return@withContext
                                HFiles.copy(source, target)
                            }
                        }
                    }
                }.onSuccess {
                    HUI.showToast(R.string.msg_extract_apk, uri.toString())
                }.onFailure {
                    HUI.showToast(R.string.operation_failed, it.localizedMessage ?: "Unknown", true)
                }
                dialog.dismiss()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        appsAdapter = AppsAdapter().apply {
            onItemClickListener = this@AppsFragment
            onItemCheckedChangeListener = this@AppsFragment
        }
        binding.refresh.apply {
            setOnRefreshListener { updateAppList() }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        binding.recyclerView.apply {
            activity.appbar.setLiftOnScrollTargetView(this)
            adapter = appsAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
            registerForContextMenu(this)
        }
        applyLayoutMode()

        model.isRefreshing.observe(viewLifecycleOwner) {
            binding.refresh.isRefreshing = it
        }
        model.apps.apply {
            lastAppsHash = value.hashCode()
            observe(viewLifecycleOwner) {
                if (isAppsChanged) updateDisplayAppList()
                lastAppsHash = it.hashCode()
            }
        }
        model.query.apply {
            lastQuery = value ?: ""
            observe(viewLifecycleOwner) {
                if (isQueryChanged) updateDisplayAppList()
                lastQuery = it
            }
        }
        model.displayApps.observe(viewLifecycleOwner) {
            submitDisplayList(it)
        }
        updateDisplayAppList()

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        applyLayoutMode()
    }

    override fun onItemClick(buttonView: CompoundButton) {
        if (appsAdapter.editMode && buttonView is MaterialCheckBox) {
            buttonView.isChecked = !buttonView.isChecked
        }
    }

    override fun onCreateContextMenu(
        menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        val info = menuInfo as? HRecyclerView.RecyclerViewContextMenuInfo ?: return
        val viewHolder = info.viewHolder
        if (viewHolder !is AppsAdapter.AppViewHolder) return
        contextMenuInfo = menuInfo
        menu.setHeaderTitle(viewHolder.info.loadLabel(activity.packageManager))
        activity.menuInflater.inflate(R.menu.menu_apps_action, menu)
        super.onCreateContextMenu(menu, v, menuInfo)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = contextMenuInfo as? HRecyclerView.RecyclerViewContextMenuInfo ?: return super.onContextItemSelected(item)
        val viewHolder = info.viewHolder as? AppsAdapter.AppViewHolder
            ?: return super.onContextItemSelected(item)
        val appInfo = viewHolder.info
        val name = appInfo.loadLabel(app.packageManager)
        val pkg = appInfo.packageName
        when (item.itemId) {
            R.id.action_details -> HUI.startActivity(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
            )

            R.id.action_export_clipboard -> {
                HUI.copyText(pkg)
                HUI.showToast(R.string.msg_text_copied, pkg)
            }

            R.id.action_extract_apk -> extractApk(pkg)
            R.id.action_uninstall -> uninstallApp(name, pkg)
            R.id.action_reinstall -> {
                if (AppManager.reinstallApp(pkg)) updateAppList()
                else HUI.showToast(R.string.operation_failed, name)
            }

            else -> return super.onContextItemSelected(item)
        }
        return true
    }

    private fun extractApk(pkg: String) {
        exportApkPkg = pkg
        exportApk.launch(HPackages.getUnhiddenPackageInfoOrNull(pkg)?.exportFileName ?: pkg)
    }

    private fun uninstallApp(name: CharSequence, pkg: String) {
        when {
            HPackages.isAppUninstalled(pkg) -> HUI.showToast(R.string.app_not_installed)

            pkg == app.packageName -> {
                when {
                    HPolicy.isDeviceOwnerActive -> activity.ownerRemoveDialog()
                    HPolicy.isProfileOwner -> HPolicy.removeProfileOwner()
                    HPolicy.isAdminActive -> HPolicy.removeActiveAdmin()
                    else -> showUninstallDialog(name, pkg)
                }
            }

            HailData.workingMode == HailData.MODE_DEFAULT -> AppManager.uninstallApp(pkg)
            else -> showUninstallDialog(name, pkg)
        }
    }

    private fun showUninstallDialog(name: CharSequence, pkg: String) {
        MaterialAlertDialogBuilder(activity).setTitle(name).setMessage(R.string.msg_uninstall)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (AppManager.uninstallApp(pkg)) updateAppList()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onItemCheckedChange(
        buttonView: CompoundButton, isChecked: Boolean, packageName: String
    ) {
        if (isChecked) HailData.addCheckedApp(packageName)
        else HailData.removeCheckedApp(packageName)
        buttonView.isChecked = HailData.isChecked(packageName)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_apps, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val editItem = menu.findItem(R.id.action_edit)
        val invertItem = menu.findItem(R.id.action_invert)
        val cancelItem = menu.findItem(R.id.action_cancel)
        val moreItem = menu.findItem(R.id.action_more)
        if (editMode) {
            searchItem.isVisible = false
            moreItem.isVisible = false
            invertItem.isVisible = true
            cancelItem.isVisible = true
            editItem.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_check)
        } else {
            searchItem.isVisible = true
            moreItem.isVisible = true
            invertItem.isVisible = false
            cancelItem.isVisible = false
            editItem.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_outline_select_all)
        }
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                model.postQuery(newText, if (newText.isEmpty()) 0L else 300L)
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                model.postQuery(query, 0L)
                return true
            }
        })
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit -> {
                if (editMode) exitEditMode(false)
                else enterEditMode()
                activity.invalidateMenu()
            }

            R.id.action_invert -> {
                appsAdapter.apps.forEach { info ->
                    if (HailData.isChecked(info.packageName)) HailData.removeCheckedApp(info.packageName)
                    else HailData.addCheckedApp(info.packageName)
                }
                HailData.saveApps()
                appsAdapter.notifyDataSetChanged()
            }

            R.id.action_cancel -> {
                exitEditMode(true)
                activity.invalidateMenu()
            }

            R.id.action_more -> showAppsSheet()
        }
        return false
    }

    private fun enterEditMode() {
        editMode = true
        editSnapshot = HailData.checkedList.map { it.packageName }.toSet()
        appsAdapter.editMode = true
        appsAdapter.notifyDataSetChanged()
    }

    private fun exitEditMode(revert: Boolean) {
        if (revert) {
            val current = HailData.checkedList.map { it.packageName }.toSet()
            editSnapshot.forEach { pkg -> if (pkg !in current) HailData.addCheckedApp(pkg) }
            current.forEach { pkg -> if (pkg !in editSnapshot) HailData.removeCheckedApp(pkg) }
            HailData.saveApps()
            appsAdapter.notifyDataSetChanged()
        }
        editMode = false
        appsAdapter.editMode = false
        appsAdapter.notifyDataSetChanged()
    }

    private fun showAppsSheet() {
        MenuBottomSheet(requireContext()) {
            buildList {
                add(
                    SheetEntry.Action(
                        R.drawable.ic_outline_sort, R.string.sort_name,
                        HailData.sortBy == HailData.SORT_NAME
                    ) {
                        changeAppsSort(HailData.SORT_NAME)
                        false
                    })
                add(
                    SheetEntry.Action(
                        R.drawable.ic_outline_sort, R.string.sort_install,
                        HailData.sortBy == HailData.SORT_INSTALL
                    ) {
                        changeAppsSort(HailData.SORT_INSTALL)
                        false
                    })
                add(
                    SheetEntry.Action(
                        R.drawable.ic_outline_sort, R.string.sort_update,
                        HailData.sortBy == HailData.SORT_UPDATE
                    ) {
                        changeAppsSort(HailData.SORT_UPDATE)
                        false
                    })
                add(SheetEntry.Divider)
                add(
                    SheetEntry.Action(
                        R.drawable.ic_round_frozen, R.string.filter_frozen_apps,
                        HailData.filterFrozenApps
                    ) {
                        toggleFilter(HailData.FILTER_FROZEN_APPS)
                        false
                    })
                add(
                    SheetEntry.Action(
                        R.drawable.ic_round_unfrozen, R.string.filter_unfrozen_apps,
                        HailData.filterUnfrozenApps
                    ) {
                        toggleFilter(HailData.FILTER_UNFROZEN_APPS)
                        false
                    })
                add(SheetEntry.Divider)
                add(
                    SheetEntry.Action(
                        R.drawable.ic_group, R.string.group_user_apps,
                        HailData.filterUserApps
                    ) { toggleType(HailData.FILTER_USER_APPS) })
                add(
                    SheetEntry.Action(
                        R.drawable.ic_group, R.string.group_system_apps,
                        HailData.filterSystemApps
                    ) { toggleType(HailData.FILTER_SYSTEM_APPS) })
                add(SheetEntry.Divider)
                add(
                    SheetEntry.Action(
                        R.drawable.ic_view_grid, R.string.layout_grid,
                        HailData.appsLayout == HailData.LAYOUT_GRID
                    ) {
                        setAppsLayout(HailData.LAYOUT_GRID)
                        false
                    })
                add(
                    SheetEntry.Action(
                        R.drawable.ic_view_list, R.string.layout_list,
                        HailData.appsLayout == HailData.LAYOUT_LIST
                    ) {
                        setAppsLayout(HailData.LAYOUT_LIST)
                        false
                    })
                add(
                    SheetEntry.Stepper(
                        R.drawable.ic_view_grid, R.string.grid_columns,
                        HailData.gridColumns, 1..5
                    ) {
                        HailData.gridColumns = it
                        applyLayoutMode()
                    })
                add(SheetEntry.Divider)
                add(
                    SheetEntry.Action(
                        R.drawable.ic_group, R.string.group_apps, HailData.groupApps
                    ) {
                        HailData.groupApps = !HailData.groupApps
                        refreshDisplay()
                        false
                    })
            }
        }.show()
    }

    private fun changeAppsSort(sort: String) {
        HailData.changeAppsSort(sort)
        updateDisplayAppList()
    }

    private fun toggleFilter(filter: String) {
        val enabled = when (filter) {
            HailData.FILTER_FROZEN_APPS -> HailData.filterFrozenApps
            else -> HailData.filterUnfrozenApps
        }
        HailData.changeAppsFilter(filter, !enabled)
        if (!HailData.filterFrozenApps && !HailData.filterUnfrozenApps) {
            HailData.changeAppsFilter(
                if (filter == HailData.FILTER_FROZEN_APPS) HailData.FILTER_UNFROZEN_APPS
                else HailData.FILTER_FROZEN_APPS, true
            )
        }
        updateDisplayAppList()
    }

    private fun toggleType(filter: String): Boolean {
        if (filter == HailData.FILTER_SYSTEM_APPS && !HailData.filterSystemApps) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.freeze_system_app)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    HailData.changeAppsFilter(filter, true)
                    updateDisplayAppList()
                }.setNegativeButton(android.R.string.cancel, null).show()
            return true
        }
        val enable = if (filter == HailData.FILTER_USER_APPS) !HailData.filterUserApps
        else !HailData.filterSystemApps
        HailData.changeAppsFilter(filter, enable)
        if (!HailData.filterUserApps && !HailData.filterSystemApps) {
            HailData.changeAppsFilter(
                if (filter == HailData.FILTER_USER_APPS) HailData.FILTER_SYSTEM_APPS
                else HailData.FILTER_USER_APPS, true
            )
        }
        updateDisplayAppList()
        return false
    }

    private fun setAppsLayout(layout: String) {
        HailData.appsLayout = layout
        applyLayoutMode()
        refreshDisplay()
    }

    private fun applyLayoutMode() {
        if (!::appsAdapter.isInitialized) return
        val span = if (HailData.appsLayout == HailData.LAYOUT_GRID) HailData.gridColumns else 1
        val current = binding.recyclerView.layoutManager as? GridLayoutManager
        if (current?.spanCount == span) return
        val layoutManager = GridLayoutManager(activity, span)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (appsAdapter.isHeader(position)) span else 1
        }
        binding.recyclerView.layoutManager = layoutManager
        appsAdapter.notifyDataSetChanged()
    }

    private fun submitDisplayList(apps: List<ApplicationInfo>) {
        val items = buildItems(apps)
        appsAdapter.submitList(items)
        updateIndexBar(items)
    }

    private fun buildItems(apps: List<ApplicationInfo>): List<AppsListItem> {
        if (!HailData.groupApps) return apps.map { AppsListItem.App(it) }
        return when (HailData.groupMode) {
            HailData.GROUP_LETTER_PINYIN -> buildLetterGroups(apps) { AppNameIndex.firstLetter(it.loadLabel(app.packageManager)) }
            HailData.GROUP_LETTER_FIRST_CHAR -> buildLetterGroups(apps) { AppNameIndex.firstChar(it.loadLabel(app.packageManager)) }
            else -> buildUserSystemGroups(apps)
        }
    }

    private fun buildUserSystemGroups(apps: List<ApplicationInfo>): List<AppsListItem> {
        val userApps = apps.filterNot { it.isSystemApp }
        val systemApps = apps.filter { it.isSystemApp }
        return buildList {
            if (userApps.isNotEmpty()) {
                add(AppsListItem.Header(resources.getString(R.string.group_user_apps)))
                userApps.forEach { add(AppsListItem.App(it)) }
            }
            if (systemApps.isNotEmpty()) {
                add(AppsListItem.Header(resources.getString(R.string.group_system_apps)))
                systemApps.forEach { add(AppsListItem.App(it)) }
            }
        }
    }

    private fun buildLetterGroups(apps: List<ApplicationInfo>, letterOf: (ApplicationInfo) -> Char): List<AppsListItem> {
        val groups = linkedMapOf<Char, MutableList<ApplicationInfo>>()
        apps.forEach { info ->
            val letter = letterOf(info)
            groups.getOrPut(letter) { mutableListOf() }.add(info)
        }
        val sorted = groups.entries.sortedWith(compareBy({ it.key == '#' }, { it.key }))
        return buildList {
            sorted.forEach { (letter, groupApps) ->
                add(AppsListItem.Header(letter.titleText()))
                groupApps.forEach { add(AppsListItem.App(it)) }
            }
        }
    }

    private fun Char.titleText(): CharSequence = when (this) {
        '#' -> resources.getString(R.string.group_other)
        else -> toString()
    }

    private fun updateIndexBar(items: List<AppsListItem>) {
        if (HailData.sortBy != HailData.SORT_NAME || !model.query.value.isNullOrEmpty()) {
            binding.indexBar.isVisible = false
            return
        }
        val positions = linkedMapOf<Char, Int>()
        items.forEachIndexed { index, item ->
            if (item is AppsListItem.App) {
                val letter = AppNameIndex.firstLetter(item.info.loadLabel(app.packageManager))
                positions.putIfAbsent(letter, index)
            }
        }
        val letters = positions.keys.sortedWith(compareBy({ it == '#' }, { it }))
        binding.indexBar.isVisible = letters.size > 1
        binding.indexBar.setLetters(letters)
        binding.indexBar.setOnLetterSelectedListener { letter ->
            positions[letter]?.let {
                (binding.recyclerView.layoutManager as? GridLayoutManager)
                    ?.scrollToPositionWithOffset(it, 0)
            }
        }
    }

    private fun refreshDisplay() {
        model.displayApps.value?.let { submitDisplayList(it) }
    }

    private fun updateAppList() = model.updateAppList()
    private fun updateDisplayAppList() = model.updateDisplayAppList()

    override fun onDestroy() {
        appsAdapter.onDestroy()
        super.onDestroy()
        _binding = null
    }
}
