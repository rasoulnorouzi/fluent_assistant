package app.tonefixer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * The three floating windows drawn by the accessibility service (no "draw over apps" permission):
 * the bubble, the long-press tone menu, and the preview card. None of them take keyboard focus,
 * so the app being typed in keeps its cursor and keyboard.
 */
class Overlay(private val ctx: Context, private val prefs: Prefs, private val cb: Callbacks) {

    interface Callbacks {
        fun onTap()
        fun onPickTone(id: String)
        fun onApply()
        fun onCancel()
        fun onOpenSettings()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val p = Ui.palette(ctx)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Number) = Ui.dp(ctx, v)

    private var tones: List<Llm.Tone> = emptyList()

    // ---------- bubble ----------

    private val circle = View(ctx)
    private val wave = ImageView(ctx).apply { setImageResource(R.drawable.ic_wave) }
    private val badge = TextView(ctx).apply {
        textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(p.cream); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.clayDeep) }
    }
    private val bubble = FrameLayout(ctx).apply {
        val size = dp(56)
        addView(circle, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))
        addView(wave, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))
        wave.scaleType = ImageView.ScaleType.CENTER
        addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        setPadding(0, 0, 0, 0)
        contentDescription = "Tone Fixer"
    }
    private val bubbleParams = params(dp(64), dp(64)).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(6)
        y = prefs.bubbleY.takeIf { it >= 0 } ?: (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()
    }
    private var bubbleShown = false
    private var pulse: ValueAnimator? = null

    enum class State { IDLE, WORKING, ERROR }

    fun showBubble(letter: String) {
        badge.text = letter
        if (!bubbleShown) {
            setState(State.IDLE)
            runCatching { wm.addView(bubble, bubbleParams); bubbleShown = true }
        }
    }

    fun hideBubble() {
        if (cardShown) return // keep it while a result is on screen
        hideMenu()
        if (bubbleShown) { runCatching { wm.removeView(bubble) }; bubbleShown = false }
    }

    fun setLetter(letter: String) { badge.text = letter }

    fun setState(s: State) {
        pulse?.cancel(); pulse = null
        wave.alpha = 1f
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (s) {
                State.IDLE -> setColor(p.sage)
                State.WORKING -> { setColor(0xFF4F6B55.toInt()); setStroke(dp(4), 0xFFC9D6CB.toInt()) }
                State.ERROR -> setColor(p.clayDeep)
            }
        }
        bubble.alpha = if (s == State.IDLE && !cardShown) 0.6f else 1f
        badge.visibility = if (s == State.WORKING) View.GONE else View.VISIBLE
        if (s == State.WORKING) pulse = ObjectAnimator.ofFloat(wave, View.ALPHA, 1f, 0.35f).apply {
            duration = 550; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    /** Small "nothing to do" wiggle, e.g. when the field is empty. */
    fun shake() {
        ObjectAnimator.ofFloat(bubble, View.TRANSLATION_X, 0f, -14f, 12f, -8f, 5f, 0f).setDuration(380).start()
    }

    init {
        bubble.setOnTouchListener(BubbleTouch())
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downY = 0f; private var startY = 0; private var dragging = false; private var longFired = false
        private val longPress = Runnable { longFired = true; bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); showMenu() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; startY = bubbleParams.y; dragging = false; longFired = false
                    bubble.alpha = 1f
                    main.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && abs(dy) > slop && !longFired) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) {
                        val maxY = ctx.resources.displayMetrics.heightPixels - dp(120)
                        bubbleParams.y = (startY + dy).toInt().coerceIn(dp(24), maxY)
                        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (!cardShown) bubble.alpha = 0.6f
                    when {
                        dragging -> prefs.bubbleY = bubbleParams.y
                        !longFired && e.actionMasked == MotionEvent.ACTION_UP -> { hideMenu(); cb.onTap() }
                    }
                }
            }
            return true
        }
    }

    // ---------- tone menu (long-press) ----------

    private var menu: View? = null

    fun setTones(t: List<Llm.Tone>) { tones = t }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        hideMenu()
        val current = prefs.tone
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card, dp(22).toFloat())
            elevation = dp(10).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(TextView(ctx).apply {
                text = "REWRITE AS"; textSize = 11f; letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.muted); setPadding(dp(10), dp(4), dp(10), dp(6))
            })
            tones.forEach { t -> addView(menuRow(t, t.id == current)) }
        }
        val root = FrameLayout(ctx).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(list, FrameLayout.LayoutParams(dp(200), -2))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hideMenu(); false }
        }
        val lp = params(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = dp(6 + 64 - 4)
            y = (bubbleParams.y - dp(170)).coerceAtLeast(dp(24))
        }
        runCatching { wm.addView(root, lp); menu = root }
    }

    private fun menuRow(t: Llm.Tone, selected: Boolean) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = Ui.ripple(Ui.round(if (selected) p.primary else 0, dp(14).toFloat()), 0x28000000)
        addView(TextView(ctx).apply {
            text = t.letter; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(p.primary)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (selected) p.card else p.bg) }
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(TextView(ctx).apply {
            text = t.label; textSize = 15f
            setTextColor(if (selected) p.onPrimary else p.ink)
            if (selected) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        })
        isClickable = true
        contentDescription = t.label
        setOnClickListener { hideMenu(); cb.onPickTone(t.id) }
    }

    fun hideMenu() { menu?.let { runCatching { wm.removeView(it) } }; menu = null }

    // ---------- preview card ----------

    private var card: View? = null
    private val cardShown get() = card != null
    private lateinit var title: TextView
    private lateinit var farsiTag: TextView
    private lateinit var body: TextView
    private lateinit var bodyScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private lateinit var apply: ImageButton
    private val text = StringBuilder()
    private var streaming = false

    fun isCardShown() = cardShown

    /** Opens (or resets) the card for a new run. */
    fun startCard(toneId: String, fromFarsi: Boolean) {
        if (card == null) buildCard()
        val tone = tones.firstOrNull { it.id == toneId }
        title.text = tone?.label ?: ""
        farsiTag.visibility = if (fromFarsi) View.VISIBLE else View.GONE
        text.clear(); streaming = true
        status.visibility = View.GONE
        renderBody()
        setApplyEnabled(false)
        fillChips(toneId)
        setState(State.WORKING)
    }

    fun append(piece: String) {
        text.append(piece); renderBody()
        bodyScroll.post { bodyScroll.fullScroll(View.FOCUS_DOWN) } // follow long answers
    }

    /** Stream ended. [note] is shown under the text (e.g. cut off). */
    fun finish(note: String?) {
        streaming = false
        renderBody()
        showStatus(note, false)
        setApplyEnabled(text.isNotBlank())
        setState(State.IDLE)
    }

    fun fail(message: String, settingsLink: Boolean) {
        if (card == null) buildCard()
        streaming = false
        renderBody()
        showStatus(message + if (settingsLink) "  Open settings ↗" else "", true)
        status.setOnClickListener(if (settingsLink) View.OnClickListener { cb.onOpenSettings() } else null)
        setApplyEnabled(false)
        setState(State.ERROR)
    }

    fun closeCard() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
        setState(State.IDLE)
    }

    private fun renderBody() {
        val sb = SpannableStringBuilder(text)
        if (streaming) sb.append("▍", ForegroundColorSpan(p.clay), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        body.text = sb
        body.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showStatus(msg: String?, error: Boolean) {
        status.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
        status.text = msg
        status.setTextColor(if (error) p.warn else p.muted)
    }

    private fun setApplyEnabled(on: Boolean) { apply.isEnabled = on; apply.alpha = if (on) 1f else 0.35f }

    private fun fillChips(currentId: String) {
        chips.removeAllViews()
        // Current tone first, then the rest in their usual order.
        tones.sortedBy { if (it.id == currentId) 0 else 1 }.forEach { t ->
            chips.addView(Ui.chip(ctx, p, t.label, t.id == currentId) { if (t.id != currentId) cb.onPickTone(t.id) },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
    }

    private fun buildCard() {
        val maxBody = (ctx.resources.displayMetrics.heightPixels * 0.32f).toInt()
        title = TextView(ctx).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.primary) }
        farsiTag = TextView(ctx).apply {
            text = "from Farsi"; textSize = 12f; setTextColor(p.muted)
            background = Ui.round(p.bg, dp(10).toFloat()); setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        val close = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(p.muted)
            background = Ui.ripple(Ui.round(0, dp(22).toFloat()), 0x28000000)
            contentDescription = "Cancel"
            setOnClickListener { cb.onCancel() }
        }
        body = TextView(ctx).apply {
            textSize = 17f; setTextColor(p.ink); setLineSpacing(0f, 1.3f); setTextIsSelectable(false)
            minHeight = dp(56)
        }
        bodyScroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxBody, MeasureSpec.AT_MOST))
        }.apply { addView(body) }
        status = TextView(ctx).apply { textSize = 13f; visibility = View.GONE }
        chips = LinearLayout(ctx)
        apply = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(p.onPrimary)
            background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.primary) }, 0x40FFFFFF)
            elevation = dp(3).toFloat()
            contentDescription = "Apply"
            setOnClickListener { cb.onApply() }
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card, dp(24).toFloat())
            elevation = dp(12).toFloat()
            setPadding(dp(16), dp(8), dp(12), dp(14))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title)
                addView(farsiTag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            addView(bodyScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); marginEnd = dp(4) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(apply, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val root = FrameLayout(ctx).apply {
            setPadding(dp(12), dp(8), dp(12), dp(16)) // room for the shadow
            addView(col)
        }
        val lp = params(-1, -2).apply { gravity = Gravity.TOP; y = topInset() }
        runCatching { wm.addView(root, lp); card = root }
    }

    // ---------- shared ----------

    /** Status bar / camera cutout height, so the card starts just below it (our windows are laid out in screen coordinates). */
    private fun topInset(): Int {
        val top = if (android.os.Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.getInsets(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()).top
        } else {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24)
        }
        return top.coerceAtLeast(dp(24))
    }

    private fun params(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    /** For tests: the card's root view (null when closed). */
    internal fun cardView(): View? = card
    internal fun bubbleView(): View = bubble
}
