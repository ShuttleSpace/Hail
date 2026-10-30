package com.aistra.hail.ui.home

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

sealed interface HomeListItem {
    data class Header(val title: CharSequence) : HomeListItem
    data class App(val info: AppInfo) : HomeListItem
}

class PagerAdapter(
    private val selectedList: List<AppInfo>,
    private val flags: MutableMap<String, Int> = mutableMapOf()
) : ListAdapter<HomeListItem, RecyclerView.ViewHolder>(HomeDiff(selectedList, flags)) {
    private var loadIconJob: Job? = null
    var multiselectMode: Boolean = false
    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemLongClickListener: OnItemLongClickListener

    val apps: List<AppInfo>
        get() = currentList.mapNotNull { (it as? HomeListItem.App)?.info }

    fun isHeader(position: Int): Boolean = getItemViewType(position) == TYPE_HEADER

    override fun getItemViewType(position: Int): Int = when (currentList[position]) {
        is HomeListItem.Header -> TYPE_HEADER
        is HomeListItem.App -> if (HailData.homeLayout == HailData.LAYOUT_LIST) TYPE_LIST else TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_LIST -> AppViewHolder(inflater.inflate(R.layout.item_home_list, parent, false))
            else -> AppViewHolder(inflater.inflate(R.layout.item_home, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is HomeListItem.Header -> (holder as HeaderViewHolder).title.setText(item.title)
            is HomeListItem.App -> bindApp(holder as AppViewHolder, item.info)
        }
    }

    private fun bindApp(holder: AppViewHolder, info: AppInfo) = holder.itemView.run {
        flags[info.packageName] = info.getFlag(selectedList)
        holder.check.visibility = if (multiselectMode) android.view.View.VISIBLE else android.view.View.GONE
        holder.check.isChecked = info in selectedList
        setOnClickListener { onItemClickListener.onItemClick(info) }
        setOnLongClickListener { onItemLongClickListener.onItemLongClick(info) }
        holder.icon.run {
            info.applicationInfo?.let {
                loadIconJob = AppIconCache.loadIconBitmapAsync(
                    context,
                    it,
                    myUserId,
                    this,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: run {
                setImageDrawable(context.packageManager.defaultActivityIcon)
                colorFilter = null
            }
        }
        holder.name.run {
            text = buildString {
                if (!HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN) append("\u2744\uFE0F")
                if (info.whitelisted) append("\uD83D\uDD12")
                append(info.name)
            }
            isEnabled = !HailData.grayscaleIcon || info.state != AppInfo.State.FROZEN
            when {
                info in selectedList -> setTextColor(
                    MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                )

                info.state == AppInfo.State.NOT_FOUND -> setTextColor(
                    MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                )

                else -> setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize)
        }
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    private class HomeDiff(
        private val selectedList: List<AppInfo>, private val flags: Map<String, Int>
    ) : DiffUtil.ItemCallback<HomeListItem>() {
        override fun areItemsTheSame(oldItem: HomeListItem, newItem: HomeListItem): Boolean = when {
            oldItem is HomeListItem.Header && newItem is HomeListItem.Header ->
                oldItem.title == newItem.title

            oldItem is HomeListItem.App && newItem is HomeListItem.App ->
                oldItem.info == newItem.info

            else -> false
        }

        override fun areContentsTheSame(oldItem: HomeListItem, newItem: HomeListItem): Boolean = when {
            oldItem is HomeListItem.Header && newItem is HomeListItem.Header -> true
            oldItem is HomeListItem.App && newItem is HomeListItem.App ->
                flags[oldItem.info.packageName] == newItem.info.getFlag(selectedList)

            else -> false
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.section_title)
    }

    private class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val check: MaterialCheckBox = view.findViewById(R.id.app_check)
    }

    interface OnItemClickListener {
        fun onItemClick(info: AppInfo)
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(info: AppInfo): Boolean
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_GRID = 1
        private const val TYPE_LIST = 2
    }
}

private fun AppInfo.getFlag(selectedList: List<AppInfo>) =
    (1 shl state.ordinal) or (this in selectedList).shl(3) or whitelisted.shl(4)

private fun Boolean.shl(bitCount: Int) = if (this) 1 shl bitCount else 0
