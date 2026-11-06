package app.tonefixer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The per-provider model list in Settings: add, remove, restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val gemini = Llm.config(ctx).provider("gemini")
    private val defaults = gemini.models.map { it.id }

    @Test fun modelList() {
        val prefs = Prefs(ctx)
        assertEquals(defaults, prefs.models(gemini))
        assertEquals(defaults.first(), prefs.currentModel(gemini))

        // Pick the second, then remove it: falls back to the first.
        prefs.setModel("gemini", defaults[1])
        assertEquals(defaults[1], prefs.currentModel(gemini))
        prefs.setModels(gemini, prefs.models(gemini) - defaults[1])
        assertEquals(listOf(defaults[0]), prefs.models(gemini))
        assertEquals(defaults[0], prefs.currentModel(gemini))

        // Add a new one and a removed built-in one back.
        prefs.setModels(gemini, prefs.models(gemini) + "gemini-9-pro" + defaults[1])
        assertEquals(listOf(defaults[0], "gemini-9-pro", defaults[1]), prefs.models(gemini))

        // Restore defaults also forgets the picked model.
        prefs.setModel("gemini", "gemini-9-pro")
        prefs.setModels(gemini, null)
        assertEquals(defaults, prefs.models(gemini))
        assertEquals(defaults.first(), prefs.currentModel(gemini))

        // An empty saved list never leaves Settings without a model.
        prefs.setModels(gemini, emptyList())
        assertEquals(defaults, prefs.models(gemini))
    }

    @Test fun oldCustomModelIsKept() {
        val prefs = Prefs(ctx)
        prefs.setModel("gemini", "gemini-old-custom") // picked with the old "Custom…" field
        assertEquals(defaults + "gemini-old-custom", prefs.models(gemini))
        assertEquals("gemini-old-custom", prefs.currentModel(gemini))
    }
}
