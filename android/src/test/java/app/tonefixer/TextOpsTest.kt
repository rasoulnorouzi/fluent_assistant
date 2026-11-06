package app.tonefixer

import org.junit.Assert.assertEquals
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
}
