package app.tonefixer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** The app's only screen: setup checklist, provider, model, key, tone, and a box to try it. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var cfg: Llm.Config
    private lateinit var p: Ui.Palette
    private lateinit var root: LinearLayout
    private var keyField: EditText? = null
    private var customMode = false
    private var testStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cfg = Llm.config(this)
        p = Ui.palette(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
            setOnApplyWindowInsetsListener { v, ins ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                    v.post { v.findFocus()?.let { f -> f.requestRectangleOnScreen(android.graphics.Rect(0, 0, f.width, f.height)) } }
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                }
                ins
            }
        }
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); build() }
    override fun onPause() { super.onPause(); saveKey() }

    private fun provider() = cfg.provider(prefs.provider)
    private fun modelId(pr: Llm.Provider) = prefs.model(pr.id) ?: pr.models.first().id

    private fun saveKey() {
        val f = keyField ?: return
        val pr = provider()
        val typed = Prefs.clean(f.text.toString())
        if (typed != (prefs.key(pr.id) ?: "")) prefs.setKey(pr.id, typed)
    }

    // ---------- building the screen ----------

    private fun build() {
        saveKey()
        root.removeAllViews()
        val pr = provider()
        val model = modelId(pr)
        if (pr.models.none { it.id == model }) customMode = true

        // Header
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text("Tone Fixer", 28f, p.ink).apply {
                // Fraunces (soft, 600), shipped as a tiny file holding only the letters of "Tone Fixer".
                typeface = runCatching { resources.getFont(R.font.fraunces_title) }.getOrDefault(Typeface.create(Typeface.SERIF, Typeface.BOLD))
                setPadding(dp(12), 0, 0, 0)
            })
        })

        // Setup checklist
        val steps = buildList<Triple<String, Boolean, (() -> Unit)?>> {
            if (Build.VERSION.SDK_INT >= 33) add(Triple("Allow restricted settings", accessibilityOn()) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
            add(Triple("Turn on Tone Fixer in Accessibility", accessibilityOn()) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            add(Triple("API key works (tap Test below)", prefs.keyOk(pr.id), null))
            add(Triple("Battery: Unrestricted", batteryOk()) {
                @Suppress("BatteryLife")
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
        }
        val card = card()
        card.addView(label("Setup · ${steps.count { it.second }} of ${steps.size} done"))
        steps.forEach { (title, done, action) -> card.addView(stepRow(title, done, action)) }
        if (Build.VERSION.SDK_INT >= 33 && !accessibilityOn()) card.addView(small(
            "Android blocks Accessibility for apps installed outside the Play Store. First try to turn on " +
            "Tone Fixer in Accessibility (Android will say \"Restricted setting\"). Then come back, tap Open next to " +
            "\"Allow restricted settings\", tap ⋮ (top right) → Allow restricted settings, and turn it on again."))
        if (Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "oneplus", "oppo", "realme", "vivo")) card.addView(
            stepRow("${Build.MANUFACTURER}: allow Autostart / no battery limits", null) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
        root.addView(card, spaced(20))

        // Provider
        root.addView(label("Provider"), spaced(24))
        root.addView(LinearLayout(this).apply {
            background = Ui.round(p.line, dp(16).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            cfg.providers.values.forEach { o ->
                addView(Ui.chip(context, p, o.label.substringAfter(' '), o.id == pr.id) {
                    saveKey(); keyField = null // the field holds the OLD provider's key
                    prefs.provider = o.id; customMode = false; testStatus = ""; build()
                }.apply { if (!isSelected) background = Ui.ripple(Ui.round(0, dp(20).toFloat()), 0x28000000) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        }, spaced(8))

        // Model
        root.addView(label("Model"), spaced(20))
        root.addView(Ui.Flow(this, dp(6)).apply {
            pr.models.forEach { m ->
                addView(Ui.chip(context, p, m.label, !customMode && m.id == model) {
                    prefs.setModel(pr.id, m.id); customMode = false; build()
                })
            }
            addView(Ui.chip(context, p, "Custom…", customMode) { customMode = true; build() })
        }, spaced(8))
        if (customMode) root.addView(field("Model id, e.g. ${pr.models.first().id}", if (pr.models.any { it.id == model }) "" else model).apply {
            addTextChangedListener(after { s -> if (s.isNotBlank()) prefs.setModel(pr.id, s.trim()) })
        }, spaced(8))

        // API key
        root.addView(label("${pr.label.substringAfter(' ')} API key"), spaced(20))
        val key = field("Paste your key", prefs.key(pr.id) ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT // password fields default to monospace
        }
        keyField = key
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(key, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.button(context, p, "Test") { test() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }, spaced(8))
        if (testStatus.isNotEmpty()) root.addView(small(testStatus).apply { setTextColor(if (prefs.keyOk(pr.id)) p.primary else p.warn) }, spaced(6))
        if (pr.keyHelp.isNotEmpty()) root.addView(small("Get a key ↗").apply {
            setTextColor(p.primary); setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse(pr.keyHelp))) }
            minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
        })

        // Tone
        root.addView(label("Tone for a tap on the bubble"), spaced(20))
        val current = cfg.tone(prefs.tone)
        root.addView(Ui.Flow(this, dp(6)).apply {
            cfg.tones.forEach { t -> addView(Ui.chip(context, p, t.label, t.id == current.id) { prefs.tone = t.id; build() }) }
        }, spaced(8))

        // Try it
        root.addView(label("Try it here"), spaced(24))
        root.addView(field("Type something (English or Farsi), then tap the bubble", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        }, spaced(8))

        root.addView(small("Hold the bubble to pick another tone. Your keys stay on this phone, encrypted."), spaced(20))
    }

    private fun test() {
        saveKey()
        val pr = provider()
        val key = prefs.key(pr.id)
        if (key == null) { testStatus = "Enter a key first."; build(); return }
        val model = modelId(pr)
        testStatus = "Testing…"; build()
        thread {
            val t0 = System.nanoTime(); var first = 0L
            val body = Llm.buildBody(cfg, pr, model, cfg.tone("grammar"), "helo, how are you")
            val out = Llm.stream(pr, model, key, body, Llm.Call()) { if (first == 0L) first = System.nanoTime() }
            runOnUiThread {
                val ok = out.error == null && out.text.isNotBlank()
                prefs.setKeyOk(pr.id, ok)
                testStatus = if (ok) "Key works · first word in %.1f s".format((first - t0) / 1e9) else out.error ?: "No answer from the model."
                build()
            }
        }
    }

    // ---------- checks ----------

    private fun accessibilityOn(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == ComponentName(this, ToneService::class.java) }
    }

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun open(i: Intent) { runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } } }

    // ---------- small view helpers ----------

    private fun dp(v: Number) = Ui.dp(this, v)

    private fun spaced(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }

    private fun label(s: String) = text(s, 14f, p.ink).apply { typeface = Typeface.DEFAULT_BOLD }

    private fun small(s: String) = text(s, 13f, p.muted).apply { setLineSpacing(0f, 1.2f) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.round(p.card, dp(20).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /** [done] null = can't be checked (shown as an info row). */
    private fun stepRow(title: String, done: Boolean?, action: (() -> Unit)?) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        addView(text(if (done == true) "✓" else "○", 18f, if (done == true) p.primary else p.clayDeep).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), -2))
        addView(text(title, 15f, p.ink), LinearLayout.LayoutParams(0, -2, 1f))
        if (done != true && action != null) addView(Ui.chip(context, p, "Open", false, action))
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 15f
        setTextColor(p.ink)
        setHintTextColor(p.muted)
        background = Ui.round(p.card, dp(14).toFloat(), p.line, dp(1))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(48)
    }

    private fun after(f: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = f(s?.toString().orEmpty())
    }
}
