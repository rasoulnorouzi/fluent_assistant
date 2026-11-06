package app.tonefixer

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import android.widget.Button

/** Shared look for the Settings screen and the overlay ("Soft & personal", see PLAN.md §4.4). */
object Ui {

    class Palette(
        val bg: Int, val card: Int, val ink: Int, val muted: Int, val line: Int,
        val primary: Int, val onPrimary: Int, val sage: Int, val clay: Int, val clayDeep: Int, val cream: Int,
        /** Error / needs-attention text; readable on [card]. */
        val warn: Int,
    )

    private val light = Palette(
        bg = 0xFFF3F0E8.toInt(), card = 0xFFFBF8F1.toInt(), ink = 0xFF2B2A26.toInt(), muted = 0xFF6B675E.toInt(),
        line = 0xFFCFC8B8.toInt(), primary = 0xFF4F6B55.toInt(), onPrimary = 0xFFFBF8F1.toInt(),
        sage = 0xFF6F8D75.toInt(), clay = 0xFFC47A5A.toInt(), clayDeep = 0xFFA35A3A.toInt(), cream = 0xFFFBF8F1.toInt(),
        warn = 0xFFA35A3A.toInt(),
    )
    private val dark = Palette(
        bg = 0xFF1F2320.toInt(), card = 0xFF2A302B.toInt(), ink = 0xFFECE7DA.toInt(), muted = 0xFFA9A397.toInt(),
        line = 0xFF4A524B.toInt(), primary = 0xFF9BBBA2.toInt(), onPrimary = 0xFF1F2320.toInt(),
        sage = 0xFF6F8D75.toInt(), clay = 0xFFD98E6E.toInt(), clayDeep = 0xFFA35A3A.toInt(), cream = 0xFFFBF8F1.toInt(),
        warn = 0xFFE7A285.toInt(),
    )

    fun palette(ctx: Context): Palette =
        if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) dark else light

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    fun ripple(content: Drawable, rippleColor: Int) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    /** A pill button. [selected] = filled with the primary colour. */
    fun chip(ctx: Context, p: Palette, label: String, selected: Boolean, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        textSize = 14f
        minHeight = dp(ctx, 40); minimumHeight = dp(ctx, 40)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
        setTextColor(if (selected) p.onPrimary else p.ink)
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val bg = if (selected) round(p.primary, dp(ctx, 20).toFloat())
                 else round(p.card, dp(ctx, 20).toFloat(), p.line, dp(ctx, 1))
        background = ripple(bg, Color.argb(40, 0, 0, 0))
        isSelected = selected
        setOnClickListener { onClick() }
    }

    /** A filled primary button. */
    fun button(ctx: Context, p: Palette, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(ctx, 48); minimumHeight = dp(ctx, 48)
        setPadding(dp(ctx, 18), 0, dp(ctx, 18), 0)
        setTextColor(p.onPrimary)
        background = ripple(round(p.primary, dp(ctx, 14).toFloat()), Color.argb(50, 255, 255, 255))
        setOnClickListener { onClick() }
    }

    /** Lays children out left to right and wraps to a new line when full (for chip rows). */
    class Flow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                val left = if (rtl) maxW - x - c.measuredWidth else x
                c.layout(paddingLeft + left, paddingTop + y, paddingLeft + left + c.measuredWidth, paddingTop + y + c.measuredHeight)
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
        }
    }
}
