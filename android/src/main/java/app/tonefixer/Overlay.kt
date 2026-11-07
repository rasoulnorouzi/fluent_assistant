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
        /** The bubble was dropped on ✕: turn it off until the app is opened again. */
        fun onDismiss()
        /** A model was picked in the bubble menu. */
        fun onPickModel(provider: String, model: String)
        /** The model line in the card was tapped: rerun with the next model. */
        fun onNextModel()
        /** Bubble menu: translate to Persian. [screen] = what's on screen, else the text in the box. */
        fun onTranslate(screen: Boolean)
        /** A translate mode chip (روان / رسمی) was tapped in the Persian card. */
        fun onPickTranslateMode(id: String)
        /** ↻ in the card: a slightly different version of the same rewrite. */
        fun onAnother()
    }

    /** One model the user can switch to: only services with a saved key are offered. */
    class ModelChoice(val provider: String, val model: String, val label: String, val service: String)

    private var models: List<ModelChoice> = emptyList()
    private var current: ModelChoice? = null

    fun setModels(list: List<ModelChoice>, now: ModelChoice?) {
        models = list; current = now
        if (card != null) renderModelLine()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val p = Ui.palette(ctx)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Number) = Ui.dp(ctx, v)

    private var tones: List<Llm.Tone> = emptyList()

    // ---------- bubble ----------

    private val size = dp(28)          // the circle
    private val win = dp(33)           // its window: room for the badge to stick out
    private val margin = dp(6)         // gap to the screen edge

    /** Idle = a ghost: faint fill and wave, but a clear ring so it's findable on any background. */
    private var idleLook = true

    private val circle = View(ctx)
    private val wave = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_wave); scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private val badge = TextView(ctx).apply {
        elevation = dp(4).toFloat()
        textSize = 6.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(p.cream); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.clayDeep) }
    }
    private val bubble = FrameLayout(ctx).apply {
        addView(circle, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))
        addView(wave, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))
        addView(badge, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END))
        contentDescription = "Tone Fixer. Tap to rewrite, hold for tones, drag to the X at the bottom to turn off."
    }
    private val bubbleParams = params(win, win).apply {
        gravity = Gravity.TOP or Gravity.START
        y = prefs.bubbleY.takeIf { it >= 0 } ?: (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()
    }
    private var bubbleShown = false
    private var pulse: ValueAnimator? = null

    enum class State { IDLE, WORKING, ERROR }

    private fun screenW(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else ctx.resources.displayMetrics.widthPixels
    private fun screenH(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else ctx.resources.displayMetrics.heightPixels

    /** x for the side the bubble lives on (it remembers left or right). */
    private fun sideX() = if (prefs.bubbleRight) screenW() - win - margin else margin

    fun showBubble(letter: String) {
        badge.text = letter
        if (!bubbleShown) {
            setState(State.IDLE)
            bubbleParams.x = sideX() // also right after a rotation
            bubbleParams.y = bubbleParams.y.coerceIn(dp(24), screenH() - dp(120))
            runCatching { wm.addView(bubble, bubbleParams); bubbleShown = true }
        }
    }

    fun hideBubble() {
        if (cardShown) return // keep it while a result is on screen
        hideMenu()
        if (bubbleShown) { runCatching { wm.removeView(bubble) }; bubbleShown = false }
    }

    /** Hides the bubble even while the card is open (it was dropped on ✕). */
    fun removeBubble() {
        closeCard(); hideMenu(); hideTarget()
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
                State.WORKING -> { setColor(0xFF4F6B55.toInt()); setStroke(dp(3), 0xFFC9D6CB.toInt()) }
                State.ERROR -> setColor(p.clayDeep)
            }
        }
        if (s == State.IDLE && !cardShown) ghost(true) else ghost(false)
        badge.visibility = if (s == State.WORKING) View.GONE else View.VISIBLE
        if (s == State.WORKING) pulse = ObjectAnimator.ofFloat(wave, View.ALPHA, 1f, 0.35f).apply {
            duration = 550; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    /**
     * [on] = ghost look: a faint sage fill and wave inside a clearer ring (light inner + dark outer
     * line, so it shows on light and dark screens). Off = the full, solid bubble (touch, working).
     */
    private fun ghost(on: Boolean) {
        idleLook = on
        if (on) {
            circle.background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x266F8D75); setStroke(dp(1.5f), 0xB34F6B55.toInt()) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), 0x80FFFFFF.toInt()) },
            )).apply { setLayerInset(1, dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f)) }
            wave.setColorFilter(0x994F6B55.toInt())
            circle.elevation = 0f; wave.elevation = 0f
            badge.alpha = 0.55f
        } else {
            if (circle.background is android.graphics.drawable.LayerDrawable)
                circle.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.sage) }
            wave.clearColorFilter()
            circle.elevation = dp(3).toFloat(); wave.elevation = dp(3).toFloat() // the wave stays above the circle
            badge.alpha = 1f
        }
    }

    /** Small "nothing to do" wiggle, e.g. when the field is empty. */
    fun shake() {
        ObjectAnimator.ofFloat(bubble, View.TRANSLATION_X, 0f, -14f, 12f, -8f, 5f, 0f).setDuration(380).start()
    }

    // ---------- the ✕ target shown while dragging ----------

    private val targetSize = dp(64)
    private val target = FrameLayout(ctx).apply {
        val ring = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE62B2A26.toInt()) } }
        addView(ring, FrameLayout.LayoutParams(targetSize, targetSize, Gravity.CENTER))
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_close); setColorFilter(p.cream) },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        contentDescription = "Drop here to turn off the bubble"
    }
    private val targetParams = params(dp(96), dp(96)).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        y = dp(40)
    }
    private var targetShown = false
    private var armed = false

    private fun showTarget() {
        if (targetShown) return
        armed = false
        target.scaleX = 1f; target.scaleY = 1f; target.alpha = 0f
        runCatching { wm.addView(target, targetParams); targetShown = true }
        target.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideTarget() {
        if (targetShown) { runCatching { wm.removeView(target) }; targetShown = false }
        armed = false
    }

    /** Is the finger over the ✕? Grows the ✕ and buzzes once when it gets there. */
    private fun updateArmed(rawX: Float, rawY: Float) {
        if (!targetShown) return
        val loc = IntArray(2); target.getLocationOnScreen(loc)
        val cx = loc[0] + target.width / 2f; val cy = loc[1] + target.height / 2f
        val now = Math.hypot((rawX - cx).toDouble(), (rawY - cy).toDouble()) < dp(72)
        if (now != armed) {
            armed = now
            target.animate().scaleX(if (now) 1.25f else 1f).scaleY(if (now) 1.25f else 1f).setDuration(120).start()
            if (now) target.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        bubble.setOnTouchListener(BubbleTouch())
    }

    /** Tap = rewrite. Hold = tone menu. Drag = move it (snaps to the nearer side), or drop on ✕ to turn it off. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false; private var longFired = false
        private val longPress = Runnable { longFired = true; bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); showMenu() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false; longFired = false
                    if (idleLook) ghost(false)
                    main.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop && !longFired) {
                        dragging = true; main.removeCallbacks(longPress); hideMenu(); showTarget()
                    }
                    if (dragging) {
                        bubbleParams.x = (startX + dx).toInt().coerceIn(0, screenW() - win)
                        bubbleParams.y = (startY + dy).toInt().coerceIn(0, screenH() - win)
                        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                        updateArmed(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (!cardShown && pulse == null) main.postDelayed({ if (!cardShown && pulse == null) ghost(true) }, 900) // fade back slowly
                    when {
                        dragging && armed && e.actionMasked == MotionEvent.ACTION_UP -> { hideTarget(); cb.onDismiss() }
                        dragging -> { hideTarget(); snapToSide(e.rawX) }
                        !longFired && e.actionMasked == MotionEvent.ACTION_UP -> { hideMenu(); cb.onTap() }
                    }
                }
            }
            return true
        }
    }

    /** Glides to the nearer screen edge and remembers that side and the height. */
    private fun snapToSide(rawX: Float) {
        prefs.bubbleRight = rawX > screenW() / 2f
        bubbleParams.y = bubbleParams.y.coerceIn(dp(24), screenH() - dp(120))
        prefs.bubbleY = bubbleParams.y
        ValueAnimator.ofInt(bubbleParams.x, sideX()).apply {
            duration = 180
            addUpdateListener { bubbleParams.x = it.animatedValue as Int; if (bubbleShown) runCatching { wm.updateViewLayout(bubble, bubbleParams) } }
            start()
        }
    }

    // ---------- tone menu (long-press) ----------

    private var menu: View? = null

    fun setTones(t: List<Llm.Tone>) { tones = t }

    private var translateModes: List<Llm.Tone> = emptyList()
    fun setTranslateModes(m: List<Llm.Tone>) { translateModes = m }

    @SuppressLint("ClickableViewAccessibility")
    internal fun showMenu() { // internal: the screenshot test opens it
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
            if (translateModes.isNotEmpty()) {
                addView(View(ctx).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(6), dp(10), dp(4)) })
                addView(TextView(ctx).apply {
                    text = "PERSIAN · فارسی"; textSize = 11f; letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(p.muted); setPadding(dp(10), dp(6), dp(10), dp(6))
                })
                addView(actionRow("ف", "فارسی", "Translate the text in this box") { cb.onTranslate(screen = false) })
                addView(actionRow("⧉", "ترجمه‌ی صفحه", "Translate what's on screen") { cb.onTranslate(screen = true) })
            }
            if (models.size > 1) {
                addView(View(ctx).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(6), dp(10), dp(4)) })
                addView(TextView(ctx).apply {
                    text = "MODEL"; textSize = 11f; letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(p.muted); setPadding(dp(10), dp(6), dp(10), dp(6))
                })
                models.forEach { m -> addView(modelRow(m, m.provider == this@Overlay.current?.provider && m.model == this@Overlay.current?.model)) }
            }
        }
        val maxH = (screenH() * 0.75f).toInt()
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(list) }
        val root = FrameLayout(ctx).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(scroll, FrameLayout.LayoutParams(dp(220), -2))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hideMenu(); false }
        }
        val lp = params(-2, -2).apply {
            // Opens beside the bubble, on the screen side the bubble is on.
            gravity = Gravity.TOP or if (prefs.bubbleRight) Gravity.END else Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = margin + win - dp(4)
            // Roughly the menu's height, so it doesn't run off the bottom of the screen.
            val est = minOf(maxH, dp(60 + 44 * tones.size + if (models.size > 1) 60 + 44 * models.size else 0))
            y = (bubbleParams.y - dp(170)).coerceAtMost(screenH() - est - dp(48)).coerceAtLeast(dp(24))
        }
        runCatching { wm.addView(root, lp); menu = root }
    }

    /** A menu row that runs an action (letter circle + label), e.g. «فارسی». */
    private fun actionRow(letter: String, label: String, description: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = Ui.ripple(Ui.round(0, dp(14).toFloat()), 0x28000000)
        addView(TextView(ctx).apply {
            text = letter; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(p.clayDeep)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.bg) }
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(TextView(ctx).apply { text = label; textSize = 15f; setTextColor(p.ink); setPadding(dp(12), 0, 0, 0) })
        isClickable = true
        contentDescription = description
        setOnClickListener { hideMenu(); onClick() }
    }

    private fun modelRow(m: ModelChoice, selected: Boolean) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = Ui.ripple(Ui.round(if (selected) p.primary else 0, dp(14).toFloat()), 0x28000000)
        addView(View(ctx).apply { // a dot: filled = in use
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(p.onPrimary) else setStroke(dp(2), p.muted)
            }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { setMargins(dp(7), 0, dp(7), 0) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), 0, dp(6))
            addView(TextView(ctx).apply {
                text = m.label; textSize = 14f
                setTextColor(if (selected) p.onPrimary else p.ink)
                if (selected) typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(ctx).apply { text = m.service; textSize = 11f; setTextColor(if (selected) p.onPrimary else p.muted) })
        })
        isClickable = true
        contentDescription = "Use ${m.label}"
        setOnClickListener { hideMenu(); cb.onPickModel(m.provider, m.model) }
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
    private lateinit var modelLine: TextView
    private lateinit var body: TextView
    private lateinit var bodyScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private lateinit var apply: ImageButton
    private lateinit var copy: ImageButton
    private lateinit var again: ImageButton
    private lateinit var originalToggle: TextView
    private lateinit var originalText: TextView
    private lateinit var col: LinearLayout
    private val text = StringBuilder()
    private var streaming = false
    /** True while the card shows a Persian translation (right-to-left, modes, copy). */
    private var persian = false
    private var canApply = true

    fun isCardShown() = cardShown

    /** Opens (or resets) the card for a new run. */
    fun startCard(toneId: String, fromFarsi: Boolean) {
        if (card == null) buildCard()
        setPersian(false, original = null)
        val tone = tones.firstOrNull { it.id == toneId }
        title.text = tone?.label ?: ""
        farsiTag.text = "from Farsi"
        farsiTag.visibility = if (fromFarsi) View.VISIBLE else View.GONE
        renderModelLine()
        text.clear(); streaming = true
        status.visibility = View.GONE
        renderBody()
        setApplyEnabled(false)
        fillChips(toneId)
        setState(State.WORKING)
    }

    /**
     * Opens (or resets) the card for a translation to Persian. [apply] = the text is the user's own
     * (✓ replaces it); otherwise only copy. [original] can be shown under the translation.
     */
    fun startTranslateCard(modeId: String, apply: Boolean, original: String) {
        if (card == null) buildCard()
        setPersian(true, original)
        canApply = apply
        title.text = "ترجمه به فارسی"
        farsiTag.visibility = View.GONE // shows «از هلندی» once the model names the language
        renderModelLine()
        text.clear(); streaming = true
        status.visibility = View.GONE
        renderBody()
        setApplyEnabled(false)
        chips.removeAllViews()
        translateModes.forEach { m ->
            chips.addView(Ui.chip(ctx, p, m.label, m.id == modeId) { if (m.id != modeId) cb.onPickTranslateMode(m.id) },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        setState(State.WORKING)
    }

    /** The source language the model named («هلندی»). */
    fun setSource(language: String) {
        if (!persian || language.isBlank()) return
        farsiTag.text = if (language == "فارسی") "متن فارسی است" else "از $language"
        farsiTag.visibility = View.VISIBLE
    }

    private fun setPersian(on: Boolean, original: String?) {
        persian = on
        canApply = true
        val dir = if (on) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        col.layoutDirection = dir
        body.textDirection = if (on) View.TEXT_DIRECTION_ANY_RTL else View.TEXT_DIRECTION_FIRST_STRONG
        body.textSize = if (on) 18f else 17f
        body.setLineSpacing(0f, if (on) 1.15f else 1.3f) // Persian letters are taller
        modelLine.textDirection = if (on) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
        copy.visibility = if (on) View.VISIBLE else View.GONE
        again.visibility = if (on) View.GONE else View.VISIBLE
        originalText.text = original ?: ""
        originalText.visibility = View.GONE
        originalToggle.text = "متن اصلی ▾"
        originalToggle.visibility = if (on && !original.isNullOrBlank()) View.VISIBLE else View.GONE
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
        if (persian) copy.alpha = if (text.isNotBlank()) 1f else 0.35f
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

    /** "Claude Haiku 5.5 ⇄": which model wrote this; tap to rerun with the next one. */
    private fun renderModelLine() {
        val m = current ?: return run { modelLine.visibility = View.GONE }
        modelLine.visibility = View.VISIBLE
        val canSwitch = models.size > 1
        modelLine.text = if (canSwitch) "${m.label}  ⇄" else m.label
        modelLine.isClickable = canSwitch
        modelLine.contentDescription = if (canSwitch) "${m.label}. Tap to try the next model." else m.label
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

    private fun setApplyEnabled(on: Boolean) {
        again.isEnabled = on; again.alpha = if (on) 1f else 0.35f
        apply.visibility = if (persian && !canApply) View.GONE else View.VISIBLE
        apply.isEnabled = on; apply.alpha = if (on) 1f else 0.35f
    }

    private fun copyResult() {
        if (text.isBlank()) return
        ctx.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("Tone Fixer", text.toString().trim()))
        showStatus("کپی شد", false)
    }

    private fun fillChips(currentId: String) {
        chips.removeAllViews()
        // Current tone first, then the rest in their usual order.
        tones.sortedBy { if (it.id == currentId) 0 else 1 }.forEach { t ->
            chips.addView(Ui.chip(ctx, p, t.label, t.id == currentId) { if (!streaming) cb.onPickTone(t.id) },
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
        modelLine = TextView(ctx).apply {
            textSize = 12f; setTextColor(p.muted)
            minHeight = dp(24); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (models.size > 1 && !streaming) cb.onNextModel() }
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
        status = TextView(ctx).apply { textSize = 13f; visibility = View.GONE }
        chips = LinearLayout(ctx)
        apply = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(p.onPrimary)
            background = Ui.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.primary) }, 0x40FFFFFF)
            elevation = dp(3).toFloat()
            contentDescription = "Apply"
            setOnClickListener { cb.onApply() }
        }

        copy = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_copy); setColorFilter(p.primary)
            background = Ui.ripple(Ui.round(p.bg, dp(26).toFloat()), 0x28000000)
            contentDescription = "Copy"
            visibility = View.GONE
            setOnClickListener { copyResult() }
        }
        again = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_again); setColorFilter(p.primary)
            background = Ui.ripple(Ui.round(p.bg, dp(26).toFloat()), 0x28000000)
            contentDescription = "Another version"
            setOnClickListener { if (!streaming) cb.onAnother() }
        }
        originalText = TextView(ctx).apply {
            textSize = 14f; setTextColor(p.muted); setLineSpacing(0f, 1.25f); visibility = View.GONE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        originalToggle = TextView(ctx).apply {
            textSize = 13f; setTextColor(p.primary); minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setOnClickListener {
                val show = originalText.visibility != View.VISIBLE
                originalText.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "متن اصلی ▴" else "متن اصلی ▾"
            }
        }
        val bodyBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
            addView(originalText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        bodyScroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxBody, MeasureSpec.AT_MOST))
        }.apply { addView(bodyBox) }
        col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card, dp(24).toFloat())
            elevation = dp(12).toFloat()
            setPadding(dp(16), dp(8), dp(12), dp(14))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(ctx).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(title)
                        addView(farsiTag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    })
                    addView(modelLine)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(6) })
                addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            addView(bodyScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); marginEnd = dp(4) })
            addView(originalToggle)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(again, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(8) })
                addView(copy, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(8) })
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

    /** For tests: the tone/model menu (null when closed). */
    internal fun menuView(): View? = menu

    /** For tests: the ✕ target shown while dragging. */
    internal fun targetView(): View = target

    /** For tests: the card's root view (null when closed). */
    internal fun cardView(): View? = card
    internal fun bubbleView(): View = bubble
}
