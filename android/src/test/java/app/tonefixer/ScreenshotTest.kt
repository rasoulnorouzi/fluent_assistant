package app.tonefixer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders screens to android/build/screens/ (PNG) so the UI can be checked without a phone.
 * Not a pass/fail test: it only fails if a screen crashes while being built.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h900dp-xhdpi")
class ScreenshotTest {

    @Test fun settingsLight() = shoot("settings-light")

    @Config(qualifiers = "+night")
    @Test fun settingsDark() = shoot("settings-dark")

    private fun shoot(name: String) {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val content = activity.findViewById<View>(android.R.id.content)
        // Measure the whole scroll content so the PNG shows the full screen, not just the first page.
        val scroll = (content as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView
        val inner = scroll.getChildAt(0)
        val w = content.width.takeIf { it > 0 } ?: 784
        inner.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        val bmp = Bitmap.createBitmap(inner.measuredWidth, inner.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(bmp).also { scroll.background?.apply { setBounds(0, 0, bmp.width, bmp.height); draw(it) } ; inner.draw(it) }
        val out = File("build/screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
