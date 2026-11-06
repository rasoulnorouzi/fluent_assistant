package app.tonefixer

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class UpdateTest {

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun parseResolvesRelativeApk() {
        val i = Update.parse("""{"versionCode":13,"versionName":"0.2.13","apk":"ToneFixer-0.2.13.apk","sha256":"ABC","notes":"n"}""",
            Update.LATEST_URL)
        assertEquals(13L, i.versionCode)
        assertEquals("https://raw.githubusercontent.com/rasoulnorouzi/fluent_assistant/updates/android/ToneFixer-0.2.13.apk", i.apkUrl)
        assertEquals("abc", i.sha256)
        assertEquals("n", i.notes)
    }

    @Test fun parseKeepsAbsoluteApk() {
        val i = Update.parse("""{"versionCode":1,"versionName":"x","apk":"https://example.com/a.apk","sha256":"0"}""", Update.LATEST_URL)
        assertEquals("https://example.com/a.apk", i.apkUrl)
        assertEquals("", i.notes)
    }

    @Test fun sha256OfFile() {
        val f = File.createTempFile("tonefixer", ".bin").apply { writeText("hello"); deleteOnExit() }
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Update.sha256(f))
    }

    /** Serves latest.json + an APK on a local server, then runs check() and download() against it. */
    private fun serve(apk: ByteArray, advertisedSha: String, block: (String, File) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val json = """{"versionCode":7,"versionName":"0.2.7","apk":"app.apk","sha256":"$advertisedSha","notes":"hi"}"""
        server.createContext("/u/latest.json") { ex ->
            val b = json.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/u/app.apk") { ex -> ex.sendResponseHeaders(200, apk.size.toLong()); ex.responseBody.use { it.write(apk) } }
        server.start()
        val dir = Files.createTempDirectory("tf").toFile()
        try { block("http://127.0.0.1:${server.address.port}/u/latest.json", dir) } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun checkAndDownload() {
        val apk = ByteArray(200_000) { (it % 251).toByte() }
        serve(apk, sha(apk)) { url, dir ->
            val info = Update.check(url)
            assertEquals(7L, info.versionCode)
            assertTrue(info.apkUrl.endsWith("/u/app.apk"))
            val seen = mutableListOf<Int>()
            val f = Update.download(info, dir) { seen += it }
            assertTrue(f.readBytes().contentEquals(apk))
            assertEquals(100, seen.last())
            // A second call reuses the verified file.
            assertEquals(f, Update.download(info, dir) { fail("should not download again") })
        }
    }

    @Test fun damagedDownloadIsRejected() {
        val apk = "not the right bytes".toByteArray()
        serve(apk, "0".repeat(64)) { url, dir ->
            try { Update.download(Update.check(url), dir) {}; fail("expected IOException") }
            catch (e: IOException) { assertEquals("Download damaged, try again", e.message) }
            assertFalse(File(dir, "update.apk").exists())
        }
    }

    @Test fun checkFailsCleanlyOffline() {
        try { Update.check("http://127.0.0.1:1/latest.json"); fail() } catch (_: IOException) {}
    }
}
