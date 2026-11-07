package app.tonefixer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "Select text → ترجمه به فارسی" (Android's text menu) and Share → Tone Fixer in any app. Has no
 * screen of its own: it hands the text to the running service, which shows the Persian card over
 * the app, and closes right away.
 */
class TranslateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))?.toString()
        val service = ToneService.instance
        when {
            text.isNullOrBlank() -> {}
            service == null -> {
                Toast.makeText(this, "Turn on Tone Fixer in Accessibility first.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            else -> service.translateText(text)
        }
        finish()
    }
}
