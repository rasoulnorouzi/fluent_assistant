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

    override fun onServiceConnected() {
        prefs = Prefs(this)
        cfg = Llm.config(this)
        overlay = Overlay(this, prefs, this).apply { setTones(cfg.tones) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (node != null && node.isEditable && !node.isPassword) {
            overlay.showBubble(cfg.tone(prefs.tone).letter)
            Llm.warm(cfg.provider(prefs.provider))
        } else {
            overlay.hideBubble()
        }
    }

    override fun onInterrupt() {} // no spoken feedback to stop; keep any running rewrite going

    override fun onDestroy() { call?.cancel(); if (::overlay.isInitialized) { overlay.closeCard(); overlay.hideBubble() }; super.onDestroy() }

    // ---------- bubble callbacks ----------

    override fun onTap() = rewrite(cfg.tone(prefs.tone).id, fresh = true)

    override fun onPickTone(id: String) {
        prefs.tone = id
        overlay.setLetter(cfg.tone(id).letter)
        rewrite(id, fresh = snap == null || !overlay.isCardShown())
    }

    override fun onCancel() {
        call?.cancel(); call = null
        applying = false
        snap = null
        overlay.closeCard()
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
        val pr = cfg.provider(prefs.provider)
        val model = prefs.currentModel(pr)
        val key = prefs.key(pr.id)

        call?.cancel()
        overlay.startCard(toneId, TextOps.looksFarsi(s.input))
        if (key == null) { overlay.fail("Add your ${pr.label} API key in Tone Fixer.", settingsLink = true); return }

        val c = Llm.Call().also { call = it }
        result = StringBuilder()
        val body = Llm.buildBody(cfg, pr, model, cfg.tone(toneId), s.input)
        thread(name = "tonefixer-llm") {
            val out = Llm.stream(pr, model, key, body, c) { piece ->
                main.post { if (call === c) { result.append(piece); overlay.append(piece) } }
            }
            main.post {
                if (call !== c || out.stop == "cancelled") return@post
                call = null
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
}
