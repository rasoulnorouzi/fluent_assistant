package app.tonefixer

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

/**
 * Watches which text field has focus, shows the bubble there, and on tap: reads the text
 * (selection or whole field), streams the rewrite into the preview card, and on ✓ writes it back.
 */
class ToneService : AccessibilityService(), Overlay.Callbacks {

    private lateinit var prefs: Prefs
    private lateinit var cfg: Llm.Config
    private lateinit var overlay: Overlay
    private val main = Handler(Looper.getMainLooper())

    /** What we read when the bubble was tapped, so ✓ can write back into the right place. */
    private class Snapshot(val node: AccessibilityNodeInfo, val full: String, val start: Int, val end: Int) {
        val input get() = full.substring(start, end)
    }
    private var snap: Snapshot? = null
    private var call: Llm.Call? = null
    private var result = StringBuilder()

    /** What the card is doing: rewriting, or translating to Persian (own text, the screen, or text from another app). */
    private enum class Job { REWRITE, OWN, SCREEN, TEXT }
    private var job = Job.REWRITE
    /** The text for SCREEN / TEXT translations (OWN uses [snap]). */
    private var source: String? = null

    override fun onServiceConnected() {
        prefs = Prefs(this)
        cfg = Llm.config(this)
        overlay = Overlay(this, prefs, this).apply { setTones(cfg.tones); setTranslateModes(cfg.translateModes) }
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    companion object {
        /** Set while the service runs, so "select text → ترجمه به فارسی" (TranslateActivity) can reach the card. */
        @Volatile var instance: ToneService? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        if (!prefs.bubbleOn) { overlay.hideBubble(); return } // dropped on ✕; the app turns it back on
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (node != null && node.isEditable && !node.isPassword) {
            refreshModels()
            overlay.showBubble(cfg.tone(prefs.tone).letter)
            Llm.warm(cfg.provider(prefs.provider))
        } else {
            overlay.hideBubble()
        }
    }

    override fun onInterrupt() {} // no spoken feedback to stop; keep any running rewrite going

    override fun onDestroy() { instance = null; call?.cancel(); if (::overlay.isInitialized) { overlay.closeCard(); overlay.hideBubble() }; super.onDestroy() }

    // ---------- bubble callbacks ----------

    override fun onTap() = rewrite(cfg.tone(prefs.tone).id, fresh = true)

    override fun onPickTone(id: String) {
        prefs.tone = id
        overlay.setLetter(cfg.tone(id).letter)
        rewrite(id, fresh = snap == null || !overlay.isCardShown() || job != Job.REWRITE)
    }

    override fun onCancel() {
        call?.cancel(); call = null
        applying = false
        snap = null; source = null
        overlay.closeCard()
    }

    // ---------- translate to Persian ----------

    override fun onTranslate(screen: Boolean) {
        if (screen) {
            val t = readScreen()
            if (t == null) { overlay.shake(); return }
            job = Job.SCREEN; source = t
        } else {
            val r = read()
            if (r == null) { overlay.shake(); return }
            job = Job.OWN; snap = r
        }
        translate(cfg.translateMode(prefs.translateMode).id)
    }

    override fun onPickTranslateMode(id: String) {
        prefs.translateMode = id
        translate(id)
    }

    /** From "select text → ترجمه به فارسی" or Share in another app: translate, copy only. */
    fun translateText(text: String) {
        main.post {
            job = Job.TEXT; source = text.trim()
            translate(cfg.translateMode(prefs.translateMode).id)
        }
    }

    private fun translate(modeId: String) {
        val input = (if (job == Job.OWN) snap?.input else source) ?: return
        run(cfg.translateMode(modeId), input, translate = true) {
            overlay.startTranslateCard(modeId, apply = job == Job.OWN, original = input)
        }
    }

    /**
     * The visible text of the app on screen (e.g. the last chat messages), top to bottom. Leaves out
     * typing boxes and bare times/numbers. Null if there's nothing to translate.
     */
    private fun readScreen(): String? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val found = mutableListOf<Pair<Int, String>>()
        val r = android.graphics.Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || !n.isVisibleToUser) return
            val t = n.text?.toString()
            if (!t.isNullOrBlank() && !n.isEditable && !n.isPassword) { n.getBoundsInScreen(r); found += r.top to t }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        return TextOps.screenText(found)
    }

    // ---------- model choice ----------

    /** Models of every service with a saved key (or just the current service if none has one yet). */
    private fun choices(): List<Overlay.ModelChoice> {
        val withKey = cfg.providers.values.filter { prefs.key(it.id) != null }.ifEmpty { listOf(cfg.provider(prefs.provider)) }
        return withKey.flatMap { pr ->
            prefs.models(pr).map { id ->
                Overlay.ModelChoice(pr.id, id, pr.models.firstOrNull { it.id == id }?.label ?: id, pr.label.substringAfter(' '))
            }
        }
    }

    private fun refreshModels() {
        val pr = cfg.provider(prefs.provider)
        val list = choices()
        val now = prefs.currentModel(pr)
        overlay.setModels(list, list.firstOrNull { it.provider == pr.id && it.model == now }
            ?: Overlay.ModelChoice(pr.id, now, pr.models.firstOrNull { it.id == now }?.label ?: now, pr.label.substringAfter(' ')))
    }

    override fun onPickModel(provider: String, model: String) {
        prefs.provider = provider
        prefs.setModel(provider, model)
        refreshModels()
        if (overlay.isCardShown() && job != Job.REWRITE && (snap != null || source != null)) translate(cfg.translateMode(prefs.translateMode).id)
        else if (overlay.isCardShown() && snap != null) rewrite(cfg.tone(prefs.tone).id, fresh = false) // compare on the same text
        else android.widget.Toast.makeText(this, "Tone Fixer now uses ${choices().firstOrNull { it.provider == provider && it.model == model }?.label ?: model}.", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onNextModel() {
        val list = choices()
        if (list.size < 2) return
        val pr = cfg.provider(prefs.provider)
        val i = list.indexOfFirst { it.provider == pr.id && it.model == prefs.currentModel(pr) }
        val next = list[(i + 1) % list.size]
        onPickModel(next.provider, next.model)
    }

    override fun onDismiss() {
        call?.cancel(); call = null
        applying = false; snap = null; source = null
        prefs.bubbleOn = false
        overlay.removeBubble()
        android.widget.Toast.makeText(this, "Tone Fixer is off. Open the Tone Fixer app to turn it back on.", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onOpenSettings() {
        onCancel()
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- rewrite ----------

    /** [fresh] = read the field again; otherwise rerun on the text read at the last tap (tone chips). */
    private fun rewrite(toneId: String, fresh: Boolean) {
        if (fresh) {
            val r = read()
            if (r == null) { overlay.shake(); return }
            snap = r
        }
        val s = snap ?: return
        job = Job.REWRITE
        run(cfg.tone(toneId), s.input, translate = false) { overlay.startCard(toneId, TextOps.looksFarsi(s.input)) }
    }

    /** Streams one rewrite or translation of [input] into the card ([openCard] opens it in the right mode). */
    private fun run(tone: Llm.Tone, input: String, translate: Boolean, openCard: () -> Unit) {
        val pr = cfg.provider(prefs.provider)
        val model = prefs.currentModel(pr)
        val key = prefs.key(pr.id)

        call?.cancel()
        refreshModels()
        openCard()
        if (key == null) { overlay.fail("Add your ${pr.label} API key in Tone Fixer.", settingsLink = true); return }

        val c = Llm.Call().also { call = it }
        result = StringBuilder()
        // A translation starts with a line naming the source language: shown in the header, not in the text.
        val lang = if (translate) TextOps.LanguageLine() else null
        val body = Llm.buildBody(cfg, pr, model, tone, input, translate)
        thread(name = "tonefixer-llm") {
            val out = Llm.stream(pr, model, key, body, c) { piece ->
                main.post {
                    if (call !== c) return@post
                    val shown = if (lang == null) piece else lang.feed(piece) { overlay.setSource(it) }
                    if (shown.isNotEmpty()) { result.append(shown); overlay.append(shown) }
                }
            }
            main.post {
                if (call !== c || out.stop == "cancelled") return@post
                call = null
                lang?.flush()?.takeIf { it.isNotEmpty() }?.let { result.append(it); overlay.append(it) }
                when {
                    out.error != null -> overlay.fail(out.error, settingsLink = "Settings" in out.error)
                    out.text.isBlank() -> overlay.fail("No answer from the model. Try again or pick another tone.", settingsLink = false)
                    out.stop == "cut" -> overlay.finish("Cut off: the answer hit the length limit.")
                    else -> overlay.finish(null)
                }
            }
        }
    }

    /** Reads the focused field. Null if there's nothing to rewrite. */
    private fun read(): Snapshot? {
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!n.isEditable || n.isPassword || n.isShowingHintText) return null
        val full = n.text?.toString() ?: return null
        val (start, end) = TextOps.range(full, n.textSelectionStart, n.textSelectionEnd)
        if (full.substring(start, end).isBlank()) return null
        return Snapshot(n, full, start, end)
    }

    // ---------- apply ----------

    private var applying = false

    override fun onApply() {
        val s = snap ?: return
        if (result.isBlank() || applying) return
        val replacement = TextOps.keepOuterSpace(s.input, result.toString())
        val newFull = TextOps.splice(s.full, s.start, s.end, replacement)
        val cursor = s.start + replacement.length
        val n = s.node

        // The field changed since the tap (kept typing, switched chat): never overwrite it.
        if (!textIs(n, s.full)) {
            giveUp(replacement, "The text changed since you tapped, so I didn't replace it. The result is copied: paste it where you want.")
            return
        }
        val accepted = n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } &&
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFull)
            })
        if (accepted && textIs(n, newFull)) { done(n, cursor); return }
        if (!accepted) { paste(n, s, replacement); return }

        // Chrome/WebView, Flutter and Compose say yes but update their text a moment later: look again.
        applying = true
        main.postDelayed({
            applying = false
            if (snap !== s) return@postDelayed
            when {
                textIs(n, newFull) -> done(n, cursor)
                textIs(n, s.full) -> paste(n, s, replacement) // really ignored: try paste on the untouched text
                else -> giveUp(replacement, "Not sure the app took the text. It's copied: check the field, and paste if needed.")
            }
        }, 350)
    }

    private fun textIs(n: AccessibilityNodeInfo, t: String) = n.refresh() && n.text?.toString() == t

    private fun done(n: AccessibilityNodeInfo, cursor: Int) {
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        snap = null
        overlay.closeCard()
    }

    private fun giveUp(replacement: String, message: String) {
        copy(replacement)
        overlay.fail(message, settingsLink = false)
    }

    /** Fallback for fields without a working SET_TEXT: select the original range and paste the result. */
    private fun paste(n: AccessibilityNodeInfo, s: Snapshot, replacement: String) {
        val msg = "This app didn't accept the text. It's copied: long-press the field and Paste."
        if (n.actionList.none { it.id == AccessibilityNodeInfo.ACTION_PASTE }) { giveUp(replacement, msg); return }
        copy(replacement)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s.start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, s.end)
        })
        if (!n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) { giveUp(replacement, msg); return }
        applying = true
        main.postDelayed({
            applying = false
            if (snap !== s) return@postDelayed
            if (n.refresh() && n.text?.toString()?.contains(replacement.trim()) == true) done(n, s.start + replacement.length)
            else giveUp(replacement, msg)
        }, 350)
    }

    private fun copy(text: String) =
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tone Fixer", text))
}

/** Pure text helpers (unit-tested). */
object TextOps {
    /** The range to rewrite: the selection if there is one, else the whole text. */
    fun range(full: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val a = minOf(selStart, selEnd); val b = maxOf(selStart, selEnd)
        return if (a in 0 until b && b <= full.length) a to b else 0 to full.length
    }

    fun splice(full: String, start: Int, end: Int, replacement: String) =
        full.substring(0, start) + replacement + full.substring(end)

    /** Models often drop or add leading/trailing whitespace; keep the user's original spacing. */
    fun keepOuterSpace(input: String, output: String): String =
        input.takeWhile { it.isWhitespace() } + output.trim() + input.takeLastWhile { it.isWhitespace() }

    fun looksFarsi(s: String) = s.any { it in '؀'..'ۿ' || it in 'ﭐ'..'﷿' }

    /**
     * Splits a streamed translation into its first line (the source language, e.g. «هلندی») and the
     * rest. [feed] returns the text to show; it calls onLanguage once the first line is complete.
     * If no line break comes within 40 characters, there's no language line and everything is shown.
     */
    class LanguageLine {
        private val buf = StringBuilder()
        private var done = false
        private var skipBlank = false

        fun feed(piece: String, onLanguage: (String) -> Unit): String {
            if (done) return trimStart(piece)
            buf.append(piece)
            val nl = buf.indexOf("\n")
            if (nl < 0) {
                if (buf.length <= 40) return ""
                done = true; return buf.toString().also { buf.clear() }
            }
            done = true; skipBlank = true
            onLanguage(buf.substring(0, nl).trim().removePrefix("زبان").trim())
            val rest = buf.substring(nl + 1); buf.clear()
            return trimStart(rest)
        }

        /** At the end of the stream: whatever is still held back (an answer shorter than one line). */
        fun flush(): String = if (done) "" else buf.toString().also { buf.clear(); done = true }

        private fun trimStart(s: String): String {
            if (!skipBlank) return s
            val t = s.trimStart('\n', '\r', ' ')
            if (t.isNotEmpty()) skipBlank = false
            return t
        }
    }

    /** Visible screen texts (top position, text) → one text, top to bottom, without bare times/numbers, last 3000 characters. */
    fun screenText(items: List<Pair<Int, String>>): String? {
        val lines = items.sortedBy { it.first }.map { it.second.trim() }
            .filter { it.length >= 2 && !it.matches(Regex("[\\d\\s:.,/\\-–]+")) }
        val deduped = lines.filterIndexed { i, l -> i == 0 || l != lines[i - 1] }
        if (deduped.isEmpty()) return null
        val all = deduped.joinToString("\n")
        return if (all.length <= 3000) all else all.takeLast(3000).substringAfter('\n')
    }
}
