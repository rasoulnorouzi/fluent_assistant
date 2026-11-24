package app.tonefixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceTest {
    @Test fun transcriptJoinsFinishedAndCurrentSentences() {
        val t = VoiceSession.Transcript()
        t.interim("سلام فردا")
        assertEquals("سلام فردا", t.joined())
        t.final("سلام، فردا جلسه داریم.")
        assertEquals("سلام، فردا جلسه داریم.", t.joined())
        t.interim(" لطفاً ")
        assertEquals("سلام، فردا جلسه داریم. لطفاً", t.joined())
        t.final("  ")
        assertEquals("سلام، فردا جلسه داریم.", t.joined())
    }

    /** Live: GEMINI_KEY=… VOICE_PCM=fa16.raw ./gradlew testDebugUnitTest --tests '*liveVoice*' (skipped otherwise). */
    @Test fun liveVoice() {
        val key = System.getenv("GEMINI_KEY"); val pcm = System.getenv("VOICE_PCM")
        assumeTrue(!key.isNullOrBlank() && !pcm.isNullOrBlank())
        val cfg = Llm.parseConfig(File("../shared/tones.json").readText(), File("../shared/providers.json").readText())
        val updates = ArrayList<String>()
        var error: String? = "not finished"
        val done = CountDownLatch(1)
        val t0 = System.currentTimeMillis()
        val s = VoiceSession(cfg.voiceUrl, cfg.voiceModel, key!!, onText = { synchronized(updates) { updates.add(it) }; println("%.1fs %s".format((System.currentTimeMillis() - t0) / 1000.0, it)) },
            onEnd = { error = it; done.countDown() })
        s.start()
        val audio = File(pcm!!).readBytes()
        var off = 0
        while (off < audio.size) { val n = minOf(3200, audio.size - off); s.audio(audio.copyOfRange(off, off + n), n); off += n; Thread.sleep(100) }
        s.stop()
        assertTrue("ended", done.await(20, TimeUnit.SECONDS))
        assertNull(error)
        assertTrue("word by word: ${updates.size} updates", updates.size >= 5)
        val last = updates.last()
        assertTrue(last, last.contains("جلسه") && last.contains("گزارش"))
    }
}
