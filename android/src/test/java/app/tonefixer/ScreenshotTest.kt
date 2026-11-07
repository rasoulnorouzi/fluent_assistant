package app.tonefixer

import android.content.Intent
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

    @Test fun settingsEditModels() = shoot("settings-models") {
        Prefs(this).setModels(Llm.config(this).provider("gemini"), listOf("gemini-3.5-flash-lite", "gemini-3.5-flash"))
        editModels = true
        build()
    }

    @Test fun settingsUpdateReady() = shoot("settings-update") {
        updInfo = Update.Info(99, "0.2.99", "https://example.com/a.apk", "00", "Faster bubble, and a fix for Telegram.")
        updStatus = "Version 0.2.99 is ready."
        renderUpdate()
    }

    @Config(qualifiers = "+night")
    @Test fun settingsDark() = shoot("settings-dark")

    // ---------- overlay ----------

    private fun overlay(): Overlay {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val cfg = Llm.config(ctx)
        val prefs = Prefs(ctx).apply { tone = "polite" }
        return Overlay(ctx, prefs, object : Overlay.Callbacks {
            override fun onTap() {}
            override fun onPickTone(id: String) {}
            override fun onApply() {}
            override fun onCancel() {}
            override fun onOpenSettings() {}
            override fun onDismiss() {}
            override fun onPickModel(provider: String, model: String) {}
            override fun onNextModel() {}
            override fun onTranslate(screen: Boolean) {}
            override fun onPickTranslateMode(id: String) {}
            override fun onAnother() {}
        }).apply {
            setTones(cfg.tones)
            setTranslateModes(cfg.translateModes)
            // As if keys were saved for both services.
            val choices = listOf(
                Overlay.ModelChoice("gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Gemini"),
                Overlay.ModelChoice("gemini", "gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", "Gemini"),
                Overlay.ModelChoice("claude", "claude-haiku-5-5", "Claude Haiku 5.5", "Claude"),
            )
            setModels(choices, choices[2])
        }
    }

    @Test fun cardStreaming() = overlay().run {
        startCard("polite", fromFarsi = true)
        append("Hi Sara! I'm really sorry, but I can't make it tomorrow. I'm not")
        save(cardView()!!, "card-streaming")
    }

    @Test fun cardDone() = overlay().run {
        startCard("formal", fromFarsi = false)
        append("Dear Mr. Ahmadi,\n\nPlease accept my apologies for the delay. I will send the report to you by Friday.\n\nBest regards,")
        finish(null)
        save(cardView()!!, "card-done")
    }

    @Config(qualifiers = "+night")
    @Test fun cardErrorDark() = overlay().run {
        startCard("daily", fromFarsi = false)
        fail("API key not accepted. Check it in Settings.", settingsLink = true)
        save(cardView()!!, "card-error-dark")
    }

    @Test fun translateCard() = overlay().run {
        startTranslateCard("fluent", apply = false, original = "Uw eigen risico voor 2026 is €385.")
        setSource("هلندی")
        append("فرانشیز (eigen risico) شما برای سال ۲۰۲۶ مبلغ ۳۸۵ یورو است. این مبلغ را خودتان می‌پردازید.")
        finish(null)
        save(cardView()!!, "card-translate")
    }

    @Test fun menuWithModels() = overlay().run {
        showBubble("P")
        showMenu()
        save(menuView()!!, "menu-models")
    }

    @Test fun bubbleAndMenu() = overlay().run {
        showBubble("F")
        save(bubbleView(), "bubble")
        save(bubbleView(), "bubble-on-white", 0xFFFFFFFF.toInt())
        save(bubbleView(), "bubble-on-dark", 0xFF121212.toInt())
        setState(Overlay.State.WORKING)
        save(bubbleView(), "bubble-working")
    }

    @Test fun dropTarget() = overlay().run {
        val t = targetView()
        t.layoutParams = android.view.ViewGroup.LayoutParams(Ui.dp(t.context, 96), Ui.dp(t.context, 96))
        save(t, "drop-target")
    }

    @Test fun openingTheAppTurnsTheBubbleBackOn() {
        val prefs = Prefs(app()).apply { bubbleOn = false; lastUpdateCheck = System.currentTimeMillis() }
        Robolectric.buildActivity(SettingsActivity::class.java).setup()
        org.junit.Assert.assertTrue(prefs.bubbleOn)
    }

    @Test fun installerAnswersReachSettings() {
        Prefs(app()).lastUpdateCheck = System.currentTimeMillis()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val status = { code: Int -> Intent(Update.ACTION_STATUS).setPackage(app().packageName)
            .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, code) }
        // 1) "Needs the user's OK": the system's confirm screen is opened.
        app().sendBroadcast(status(android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("test.CONFIRM_INSTALL")))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.junit.Assert.assertEquals("test.CONFIRM_INSTALL", org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.action)
        // 2) The user said no.
        app().sendBroadcast(status(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.junit.Assert.assertEquals("Update cancelled.", activity.updStatus)
    }

    @Test fun launcherIcon() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val icon = ctx.getDrawable(R.mipmap.ic_launcher)!!
        val size = 324 // 108dp at 3x
        val bmp = Bitmap.createBitmap(size * 2 + 40, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(0xFFDDDAD2.toInt())
            icon.setBounds(0, 0, size, size)
            // Circle mask (most launchers) and rounded square. AdaptiveIconDrawable treats the bounds as the visible area.
            save(); clipPath(android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }); icon.draw(this); restore()
            save(); translate(size + 40f, 0f)
            clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 80f, 80f, android.graphics.Path.Direction.CW) }); icon.draw(this); restore()
        }
        File(File("build/screens").apply { mkdirs() }, "launcher-icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Lays out a detached view at phone width and saves it. */
    private fun save(v: View, name: String, bg: Int = 0xFFDDDAD2.toInt()) {
        val w = 784
        val lw = v.layoutParams?.width
        val spec = when {
            lw == null || lw == -1 -> View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
            lw == -2 -> View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST) // wrap content
            else -> View.MeasureSpec.makeMeasureSpec(lw, View.MeasureSpec.EXACTLY)
        }
        v.measure(spec, View.MeasureSpec.UNSPECIFIED)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(bg); v.draw(this) }
        val out = File("build/screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun app() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun shoot(name: String, tweak: SettingsActivity.() -> Unit = {}) {
        // Pretend we just checked, so the screen doesn't go to the network for an update.
        Prefs(app()).lastUpdateCheck = System.currentTimeMillis()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.tweak()
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
