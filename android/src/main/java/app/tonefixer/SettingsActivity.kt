package app.tonefixer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** The app's only screen. Placeholder until step 1.7. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Tone Fixer"
            textSize = 28f
            setPadding(48, 96, 48, 48)
        })
    }
}
