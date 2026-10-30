package com.aistra.hail.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.aistra.hail.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textview.MaterialTextView

sealed interface SheetEntry {
    data class Action(
        @DrawableRes val icon: Int? = null,
        @StringRes val title: Int? = null,
        val checked: Boolean? = null,
        val text: CharSequence? = null,
        val radio: Boolean = false,
        val onClick: () -> Boolean
    ) : SheetEntry

    data class Header(
        @StringRes val title: Int,
        val text: CharSequence? = null
    ) : SheetEntry

    data class Stepper(
        @DrawableRes val icon: Int? = null,
        @StringRes val title: Int,
        val value: Int,
        val range: IntRange,
        val onChange: (Int) -> Unit
    ) : SheetEntry

    data object Divider : SheetEntry
}

class MenuBottomSheet(
    context: Context,
    private val builder: () -> List<SheetEntry>
) : BottomSheetDialog(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, context.resources.getDimensionPixelSize(R.dimen.padding_small))
    }
    private val scrollView = NestedScrollView(context).apply {
        addView(content, MATCH_PARENT, WRAP_CONTENT)
    }

    init {
        setContentView(scrollView)
        rebuild()
    }

    fun rebuild() {
        content.removeAllViews()
        content.addView(createHandle())
        builder().forEach { entry ->
            when (entry) {
                is SheetEntry.Action -> content.addView(createRow(entry))
                is SheetEntry.Header -> content.addView(createHeader(entry))
                is SheetEntry.Stepper -> content.addView(createStepper(entry))
                SheetEntry.Divider -> content.addView(createDivider())
            }
        }
    }

    private fun createHandle() = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0x33000000))
        }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    private fun createRow(item: SheetEntry.Action) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56))
        setPadding(dp(24), 0, dp(24), 0)
        isClickable = true
        isFocusable = true
        setBackgroundResource(selectableItemBackground())

        addView(if (item.icon != null) ImageView(context).apply {
            setImageResource(item.icon)
            imageTintList = ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF000000.toInt())
            )
        } else View(context), LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginEnd = dp(24)
        })

        addView(MaterialTextView(context).apply {
            text = when {
                item.text != null -> item.text
                item.title != null -> context.getString(item.title)
                else -> ""
            }
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt()))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (item.checked != null) addView(if (item.radio) MaterialRadioButton(context).apply {
            isChecked = item.checked
            isClickable = false
            isFocusable = false
        } else ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(
                color(androidx.appcompat.R.attr.colorPrimary, 0xFF000000.toInt())
            )
            isVisible = item.checked
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginStart = dp(24)
        })

        setOnClickListener {
            if (item.onClick()) dismiss() else rebuild()
        }
    }

    private fun createHeader(item: SheetEntry.Header) = MaterialTextView(context).apply {
        text = when {
            item.text != null -> item.text
            else -> context.getString(item.title)
        }
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        setTextColor(color(androidx.appcompat.R.attr.colorPrimary, 0xFF000000.toInt()))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(24), dp(16), dp(24), dp(4))
        }
    }

    private fun createStepper(item: SheetEntry.Stepper) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56))
        setPadding(dp(24), 0, dp(24), 0)

        if (item.icon != null) addView(ImageView(context).apply {
            setImageResource(item.icon)
            imageTintList = ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF000000.toInt())
            )
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(24) })

        addView(MaterialTextView(context).apply {
            setText(item.title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt()))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val valueText = MaterialTextView(context).apply {
            text = item.value.toString()
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), WRAP_CONTENT)
        }
        addView(valueText)

        fun button(text: String, enabled: Boolean, change: (Int) -> Unit) = MaterialTextView(context).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            gravity = Gravity.CENTER
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.38f
            setBackgroundResource(selectableItemBackground())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { change(item.value) }
        }

        addView(button("−", item.value > item.range.first) {
            val next = (it - 1).coerceIn(item.range)
            item.onChange(next)
            valueText.text = next.toString()
            rebuild()
        })
        addView(button("+", item.value < item.range.last) {
            val next = (it + 1).coerceIn(item.range)
            item.onChange(next)
            valueText.text = next.toString()
            rebuild()
        })
    }

    private fun createDivider() = View(context).apply {
        setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant, 0x1F000000))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    private fun selectableItemBackground(): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun color(attr: Int, default: Int): Int = MaterialColors.getColor(context, attr, default)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
