package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

sealed interface AppsListItem {
    data class Header(val title: CharSequence) : AppsListItem
    data class App(val info: ApplicationInfo) : AppsListItem
}

class AppsAdapter : ListAdapter<AppsListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppsListItem>() {
            override fun areItemsTheSame(oldItem: AppsListItem, newItem: AppsListItem): Boolean = when {
                oldItem is AppsListItem.Header && newItem is AppsListItem.Header ->
                    oldItem.title == newItem.title

                oldItem is AppsListItem.App && newItem is AppsListItem.App ->
                    oldItem.info.packageName == newItem.info.packageName

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppsListItem, newItem: AppsListItem): Boolean = when {
                oldItem is AppsListItem.Header && newItem is AppsListItem.Header -> true
                oldItem is AppsListItem.App && newItem is AppsListItem.App ->
                    oldItem.info.flags and ApplicationInfo.FLAG_INSTALLED ==
                            newItem.info.flags and ApplicationInfo.FLAG_INSTALLED

                else -> false
            }
        }

        private const val TYPE_HEADER = 0
        private const val TYPE_LIST = 1
        private const val TYPE_GRID = 2
    }

    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemCheckedChangeListener: OnItemCheckedChangeListener
    var editMode: Boolean = false
    private var loadIconJob: Job? = null

    val apps: List<ApplicationInfo>
        get() = currentList.mapNotNull { (it as? AppsListItem.App)?.info }

    fun isHeader(position: Int): Boolean = getItemViewType(position) == TYPE_HEADER

    override fun getItemViewType(position: Int): Int = when (currentList[position]) {
        is AppsListItem.Header -> TYPE_HEADER
        is AppsListItem.App -> if (HailData.appsLayout == HailData.LAYOUT_GRID) TYPE_GRID else TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_GRID -> AppViewHolder(inflater.inflate(R.layout.item_apps_grid, parent, false))
            else -> AppViewHolder(inflater.inflate(R.layout.item_apps, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is AppsListItem.Header -> (holder as HeaderViewHolder).title.setText(item.title)
            is AppsListItem.App -> (holder as AppViewHolder).bindInfo(item.info)
        }
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val desc: TextView? = view.findViewById(R.id.app_desc)
        val star: MaterialCheckBox = view.findViewById(R.id.app_star)
        lateinit var info: ApplicationInfo
        private val pkg get() = info.packageName

        /**
         * Flag that view data is being updated to avoid triggering the event.
         * */
        private var updating = false

        init {
            itemView.apply {
                setOnClickListener { onItemClickListener.onItemClick(star) }
                isLongClickable = true
            }
            star.setOnCheckedChangeListener { button, isChecked ->
                if (!updating) onItemCheckedChangeListener.onItemCheckedChange(button, isChecked, pkg)
            }
        }

        fun bindInfo(info: ApplicationInfo) {
            updating = true
            this.info = info
            val frozen = AppManager.isAppFrozen(pkg)
            val checked = HailData.isChecked(pkg)
            val muted = checked || frozen

            icon.apply {
                loadIconJob = AppIconCache.loadIconBitmapAsync(
                    context, info, HPackages.myUserId, this, muted
                )
            }
            name.apply {
                val label = info.loadLabel(context.packageManager)
                text = if (!HailData.grayscaleIcon && frozen) "❄️$label" else label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
                setTextColor(
                    when {
                        HPackages.isAppUninstalled(pkg) -> MaterialColors.getColor(
                            this, androidx.appcompat.R.attr.colorError
                        )

                        muted -> androidx.core.graphics.ColorUtils.setAlphaComponent(onSurface, 0x66)
                        else -> onSurface
                    }
                )
            }
            desc?.apply {
                text = pkg
                isEnabled = editMode || !muted
            }
            star.visibility = if (editMode || checked) View.VISIBLE else View.GONE
            star.isEnabled = editMode
            star.isChecked = checked
            updating = false
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.section_title)
    }

    interface OnItemClickListener {
        fun onItemClick(buttonView: CompoundButton)
    }

    interface OnItemCheckedChangeListener {
        fun onItemCheckedChange(buttonView: CompoundButton, isChecked: Boolean, packageName: String)
    }
}
