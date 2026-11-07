package app.tonefixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOpsTest {
    @Test fun rangeUsesSelectionWhenPresent() {
        assertEquals(6 to 11, TextOps.range("hello world", 6, 11))
        assertEquals(6 to 11, TextOps.range("hello world", 11, 6)) // selected backwards
    }

    @Test fun rangeFallsBackToWholeField() {
        assertEquals(0 to 11, TextOps.range("hello world", 5, 5))   // just a cursor
        assertEquals(0 to 11, TextOps.range("hello world", -1, -1)) // no selection info
        assertEquals(0 to 3, TextOps.range("abc", 1, 99))           // stale selection
    }

    @Test fun spliceReplacesOnlyTheRange() {
        assertEquals("hello there friend", TextOps.splice("hello wrld friend", 6, 10, "there"))
        assertEquals("NEW", TextOps.splice("old", 0, 3, "NEW"))
    }

    @Test fun keepsUsersOuterWhitespace() {
        assertEquals(" Hi there.\n", TextOps.keepOuterSpace(" hi there\n", "Hi there.\n\n"))
        assertEquals("Hi.", TextOps.keepOuterSpace("hi", "  Hi.  "))
    }

    @Test fun farsiDetection() {
        assertTrue(TextOps.looksFarsi("سلام سارا"))
        assertTrue(TextOps.looksFarsi("ok, فردا"))
        assertFalse(TextOps.looksFarsi("salam sara")) // Finglish: no Farsi letters
    }

    // ---------- translate to Persian ----------

    private fun stream(vararg pieces: String): Pair<String?, String> {
        val l = TextOps.LanguageLine(); var lang: String? = null
        val shown = pieces.joinToString("") { l.feed(it) { x -> lang = x } } + l.flush()
        return lang to shown
    }

    @Test fun languageLineIsHiddenAndReported() {
        assertEquals("هلندی" to "سلام! ساعت ۱۰ میام دنبالت.", stream("هل", "ندی\nسلام! ساعت ", "۱۰ میام دنبالت."))
        assertEquals("هلندی" to "متن", stream("زبان هلندی\n", "\n", "متن"))      // «زبان» dropped, blank line skipped
        assertEquals("ترکی" to "جلسه\n\nفردا", stream("ترکی\n\njalse".replace("jalse", "جلسه"), "\n\nفردا")) // later blank lines kept
    }

    @Test fun noLanguageLineShowsEverything() {
        val long = "این یک جمله‌ی بلند است که خط اولش اسم زبان نیست و ادامه دارد"
        assertEquals(null to long, stream(long))
        assertEquals(null to "کوتاه", stream("کوتاه")) // short answer without a line break: flushed at the end
    }

    @Test fun screenTextOrdersFiltersAndDedupes() {
        val items = listOf(300 to "great!! bring the cake 😄", 100 to "hey are u coming tmrw?", 120 to "20:14",
            200 to "yes for sure", 210 to "yes for sure", 50 to "x", 250 to " 3 ")
        assertEquals("hey are u coming tmrw?\nyes for sure\ngreat!! bring the cake 😄", TextOps.screenText(items))
        assertNull(TextOps.screenText(listOf(1 to "12:30", 2 to "5")))
        val many = (1..400).map { it to "message number $it is here" }
        val t = TextOps.screenText(many)!!
        assertTrue(t.length <= 3000 && t.endsWith("message number 400 is here") && t.startsWith("message"))
    }
}
