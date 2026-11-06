package app.tonefixer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
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
    internal var editModels = false
    private var bubbleBack = false // internal: set by the screenshot test
    private var testStatus = ""

    // In-app update state (PLAN.md §4.7). The section redraws on its own, so a background check
    // never rebuilds the screen under the user's fingers.
    private var aboutBox: LinearLayout? = null
    internal var updInfo: Update.Info? = null // internal: set by the screenshot test
    internal var updStatus = "" // internal: read by tests
    private var updError = false
    private var updBusy = false
    /** Android's installer reports here (see Update.install). */
    private val installStatus = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = handleInstallStatus(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cfg = Llm.config(this)
        val filter = android.content.IntentFilter(Update.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installStatus, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(installStatus, filter)
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

    override fun onResume() {
        super.onResume()
        // Opening the app brings the bubble back after it was dropped on ✕.
        if (!prefs.bubbleOn) { prefs.bubbleOn = true; bubbleBack = true }
        build()
        if (prefs.updateAfterAllow && packageManager.canRequestPackageInstalls()) {
            prefs.updateAfterAllow = false // back from "Install unknown apps": carry on
            if (updInfo != null) startUpdate() else checkUpdate(manual = true, thenInstall = true)
        } else if (System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 3600_000L) checkUpdate(manual = false)
    }

    override fun onDestroy() { runCatching { unregisterReceiver(installStatus) }; super.onDestroy() }

    private fun handleInstallStatus(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) { runCatching { startActivity(confirm) }; return }
                setUpd("Android didn't show the install screen. Use \"Download in browser\" below.", error = true)
            }
            PackageInstaller.STATUS_SUCCESS -> setUpd("Updated.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> setUpd("Update cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> setUpd(
                "Android refused the update: it's signed with a different key than the installed app. " +
                "Uninstall Tone Fixer once and install the new version from the browser link below.", error = true)
            else -> setUpd("Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"}.", error = true)
        }
        updBusy = false; renderUpdate()
    }
    override fun onPause() { super.onPause(); saveKey() }

    private fun provider() = cfg.provider(prefs.provider)
    private fun modelId(pr: Llm.Provider) = prefs.currentModel(pr)

    private fun saveKey() {
        val f = keyField ?: return
        val pr = provider()
        val typed = Prefs.clean(f.text.toString())
        if (typed != (prefs.key(pr.id) ?: "")) prefs.setKey(pr.id, typed)
    }

    // ---------- building the screen ----------

    internal fun build() { // internal: the screenshot test redraws after changing state
        saveKey()
        root.removeAllViews()
        val pr = provider()
        val model = modelId(pr)

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
        // Which service and model a tap on the bubble uses right now.
        val usingLabel = pr.models.firstOrNull { it.id == model }?.label ?: model
        val withKeys = cfg.providers.values.filter { prefs.key(it.id) != null }.map { it.label.substringAfter(' ') }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card, dp(16).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(text("Now using: $usingLabel", 15f, p.ink).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(small(when {
                withKeys.size > 1 -> "Keys saved for ${withKeys.joinToString(" and ")}. Hold the bubble to switch model, or tap the model name in the result card."
                withKeys.isEmpty() -> "No API key yet: add one below."
                else -> "Key saved for ${withKeys.first()}. Add a key for another service to switch between them from the bubble."
            }), spaced(2))
        }, spaced(16))
        if (bubbleBack) root.addView(small("The bubble is back on. Drag it to ✕ at the bottom of the screen to turn it off again.").apply {
            setTextColor(p.primary)
        }, spaced(12))
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
                    prefs.provider = o.id; editModels = false; testStatus = ""; build()
                }.apply { if (!isSelected) background = Ui.ripple(Ui.round(0, dp(20).toFloat()), 0x28000000) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        }, spaced(8))

        // Model
        root.addView(label("Model"), spaced(20))
        val ids = prefs.models(pr)
        fun labelOf(id: String) = pr.models.firstOrNull { it.id == id }?.label ?: id
        root.addView(Ui.Flow(this, dp(6)).apply {
            ids.forEach { id ->
                // Edit mode: tap ✕ to remove (one model always stays). Otherwise: tap to use it.
                addView(Ui.chip(context, p, if (editModels) "${labelOf(id)}  ✕" else labelOf(id), id == model) {
                    if (!editModels) prefs.setModel(pr.id, id)
                    else if (ids.size > 1) prefs.setModels(pr, ids - id)
                    build()
                }.apply { contentDescription = if (editModels) "Remove ${labelOf(id)}" else labelOf(id) })
            }
            addView(Ui.chip(context, p, if (editModels) "Done" else "Edit", false) { editModels = !editModels; build() })
        }, spaced(8))
        if (editModels) {
            val add = field("Model id, e.g. ${pr.models.first().id}", "").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(add, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.button(context, p, "Add") {
                    val id = add.text.toString().filter { !it.isWhitespace() }
                    if (id.isNotEmpty()) { prefs.setModels(pr, (ids + id).distinct()); prefs.setModel(pr.id, id) }
                    build()
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, spaced(8))
            val defaults = pr.models.map { it.id }
            root.addView(small("Tap ✕ to remove a model, or add any ${pr.label.substringAfter(' ')} model id. Then press Test below to check it."), spaced(6))
            if (ids != defaults) root.addView(small("Restore defaults ↺").apply {
                setTextColor(p.primary); minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { prefs.setModels(pr, null); build() }
            })
        }

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

        root.addView(small("Hold the bubble to pick another tone. Drag it to move it, or onto ✕ at the bottom to turn it off " +
            "(opening this app turns it back on). Your keys stay on this phone, encrypted."), spaced(20))

        // About & updates
        root.addView(label("About & updates"), spaced(28))
        aboutBox = card().also { root.addView(it, spaced(8)) }
        renderUpdate()
    }

    // ---------- updates ----------

    private fun setUpd(status: String, error: Boolean = false) { updStatus = status; updError = error }

    internal fun renderUpdate() {
        val box = aboutBox ?: return
        box.removeAllViews()
        val info = updInfo
        val newer = info != null && info.versionCode > Update.installedCode(this)
        box.addView(text("Tone Fixer ${Update.installedName(this)}", 15f, p.ink))
        if (updStatus.isNotEmpty()) box.addView(small(updStatus).apply { setTextColor(if (updError) p.warn else p.muted) }, spaced(4))
        if (newer && info!!.notes.isNotBlank() && !updError) box.addView(small(info.notes), spaced(4))
        val btn = if (newer) Ui.button(this, p, "Update to ${info!!.versionName}") { startUpdate() }
                  else Ui.chip(this, p, "Check for updates", false) { checkUpdate(manual = true) }
        btn.isEnabled = !updBusy; btn.alpha = if (updBusy) 0.5f else 1f
        box.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        box.addView(small("Uninstall Tone Fixer").apply {
            setTextColor(p.warn); minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))) }
        }, spaced(6))
        if (newer && updError) box.addView(small("Download in browser ↗").apply {
            setTextColor(p.primary); minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse(info!!.apkUrl))) }
        })
    }

    private fun checkUpdate(manual: Boolean, thenInstall: Boolean = false) {
        if (updBusy) return
        updBusy = true; if (manual) setUpd("Checking…"); renderUpdate()
        thread {
            val r = runCatching { Update.check() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updBusy = false
                r.onSuccess { info ->
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    updInfo = info
                    if (info.versionCode > Update.installedCode(this)) {
                        setUpd("Version ${info.versionName} is ready.")
                        if (thenInstall) { renderUpdate(); startUpdate(); return@runOnUiThread }
                    } else if (manual) setUpd("You have the latest version (${Update.installedName(this)}).") else setUpd("")
                }.onFailure { if (manual) setUpd("Couldn't check for updates. Check your connection and try again.", error = true) }
                renderUpdate()
            }
        }
    }

    private fun startUpdate() {
        val info = updInfo ?: return
        if (updBusy) return
        if (!packageManager.canRequestPackageInstalls()) {
            // One-time switch: Settings → "Install unknown apps" → Tone Fixer. onResume continues.
            prefs.updateAfterAllow = true
            setUpd("Allow \"Install unknown apps\" for Tone Fixer, then come back: the update continues by itself.")
            renderUpdate()
            open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updBusy = true; setUpd("Downloading…"); renderUpdate()
        thread {
            val r = runCatching {
                val apk = Update.download(info, cacheDir) { pct -> runOnUiThread { if (updBusy) { setUpd("Downloading… $pct%"); renderUpdate() } } }
                runOnUiThread { setUpd("Installing…"); renderUpdate() }
                Update.install(this, apk)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onFailure {
                    updBusy = false
                    setUpd("Update failed: ${it.message ?: "no connection"}.", error = true)
                    renderUpdate()
                }
            }
        }
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
