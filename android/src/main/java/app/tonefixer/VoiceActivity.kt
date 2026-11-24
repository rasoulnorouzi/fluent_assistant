package app.tonefixer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Voice typing panel: a small card at the bottom of the screen while you speak. Being on screen is what
 * lets Android give us the microphone (an app in the background only gets silence). The words go to
 * ToneService, which types them into the text box behind. Tap Stop (or outside the card) to finish.
 */
class VoiceActivity : Activity() {
    private lateinit var p: Ui.Palette
    private lateinit var title: TextView
    private lateinit var preview: TextView
    private var session: VoiceSession? = null
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        val dp = { v: Number -> Ui.dp(this, v) }
        title = TextView(this).apply { text = "Starting…"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.ink) }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.clayDeep) } }
        ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply { duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() }
        preview = TextView(this).apply {
            text = "Speak Persian or English. The words go into the text box."
            textSize = 15f; setTextColor(p.muted); maxLines = 2; ellipsize = TextUtils.TruncateAt.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card, dp(24).toFloat())
            elevation = dp(12).toFloat()
            setPadding(dp(18), dp(14), dp(14), dp(14))
            isClickable = true // taps on the card don't count as "outside"
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
                addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.button(context, p, "Stop") { stop() })
            })
            addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(FrameLayout(this).apply {
            setOnClickListener { stop() } // outside the card
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(24)) })
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
        else end("Voice typing needs the microphone: allow it for Tone Fixer.")
    }

    @SuppressLint("MissingPermission") // checked in onCreate
    private fun begin() {
        val key = Prefs(this).key("gemini") ?: return end("Voice typing uses Google Gemini: add a Gemini API key in Tone Fixer.")
        if (ToneService.instance == null) return end("Tone Fixer isn't on: switch it on in Accessibility settings.")
        val cfg = Llm.config(this)
        val s = VoiceSession(cfg.voiceUrl, cfg.voiceModel, key,
            onText = { words -> ToneService.instance?.voiceText(words); runOnUiThread { if (words.isNotBlank()) { preview.text = words; preview.setTextColor(p.ink) } } },
            onEnd = { error -> runOnUiThread { end(error) } })
        session = s
        s.start()
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); s.cancel(); return end("The microphone is busy or not available.") }
        recorder = r
        recording = true
        r.startRecording()
        title.text = "Listening…"
        thread(name = "tonefixer-mic") {
            val buf = ByteArray(3200) // 0.1 s
            while (recording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) s.audio(buf, n)
            }
        }
    }

    /** Stop listening; the last words still arrive, then the panel closes. */
    private fun stop() {
        if (done) return
        if (!recording) { session?.cancel(); end(null); return }
        recording = false
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        title.text = "Finishing…"
        session?.stop()
    }

    private fun end(error: String?) {
        if (done) return
        done = true
        recording = false
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        ToneService.instance?.voiceDone(error) ?: error?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onStop() { super.onStop(); if (!done) stop() } // left the panel (home, another app)
    override fun onDestroy() { if (!done) { session?.cancel(); end(null) }; super.onDestroy() }
}
