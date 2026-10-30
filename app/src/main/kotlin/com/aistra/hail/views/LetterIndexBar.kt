package com.aistra.hail.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

class LetterIndexBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, deflateStyleAttr: Int = 0
) : View(context, attrs, deflateStyleAttr) {
    private val letters = mutableListOf<Char>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
        )
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurfaceContainer, 0xE6FFFFFF.toInt()
        )
        alpha = 0xE6
    }
    private val normalColor = MaterialColors.getColor(
        context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0x8A000000.toInt()
    )
    private val activeColor = MaterialColors.getColor(
        context, androidx.appcompat.R.attr.colorPrimary, 0xFF000000.toInt()
    )
    private val itemHeight = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
    )
    private val horizontalPadding = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
    )
    private val verticalPadding = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
    )
    private val pillGap = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics
    )
    private val pillRadius = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 14f, resources.displayMetrics
    )
    private val pillTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
    )
    private var activeIndex = -1
    private var onLetterSelected: ((Char) -> Unit)? = null
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = activeColor
        style = Paint.Style.FILL
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = pillTextSize
    }

    fun setLetters(value: List<Char>) {
        if (letters == value) return
        letters.clear()
        letters.addAll(value)
        activeIndex = -1
        requestLayout()
        invalidate()
    }

    fun setOnLetterSelectedListener(listener: (Char) -> Unit) {
        onLetterSelected = listener
    }

    override fun onMeasure(width: Int, height: Int) {
        val pillWidth = (pillRadius * 2).toInt()
        val desiredWidth = (itemHeight + horizontalPadding * 2 + pillGap + pillWidth).toInt()
        val desiredHeight = (letters.size * itemHeight + verticalPadding * 2).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, width),
            resolveSize(desiredHeight, height)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val radius = width / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)
        val centerX = pillGap + pillRadius + horizontalPadding + itemHeight / 2f
        letters.forEachIndexed { index, letter ->
            paint.color = if (index == activeIndex) activeColor else normalColor
            val centerY = verticalPadding + itemHeight * index + itemHeight / 2f -
                    (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letter.toString(), centerX, centerY, paint)
        }
        if (activeIndex in letters.indices) {
            val cy = verticalPadding + itemHeight * activeIndex + itemHeight / 2f
            val pill = RectF(pillGap, cy - pillRadius, pillGap + pillRadius * 2, cy + pillRadius)
            val path = Path().apply {
                addRoundRect(pill, pillRadius, pillRadius, Path.Direction.CW)
            }
            canvas.drawPath(path, pillPaint)
            canvas.drawText(
                letters[activeIndex].toString(),
                pillGap + pillRadius,
                cy - (pillTextPaint.ascent() + pillTextPaint.descent()) / 2f,
                pillTextPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = ((event.y - verticalPadding) / itemHeight).toInt()
                    .coerceIn(0, letters.lastIndex)
                if (index != activeIndex) {
                    activeIndex = index
                    onLetterSelected?.invoke(letters[index])
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                activeIndex = -1
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}