package app.tonefixer

import java.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Voice typing: microphone audio → Gemini's live speech-to-text (Persian and English) → the words so far,
 * many times a second. Uses the Gemini key even when Claude rewrites the text.
 *
 * Protocol (tested live): setup {model, inputAudioTranscription:{}}; audio as realtimeInput.audio
 * (16 kHz 16-bit mono PCM, base64); realtimeInput.audioStreamEnd at the end. The server sends
 * serverContent.interimInputTranscription.text (the current sentence so far, replaced each time) and
 * serverContent.inputTranscription.text (the finished sentence), then generationComplete.
 */
class VoiceSession(
    private val url: String, private val model: String, private val key: String,
    /** The whole transcript so far (finished sentences + the one being spoken). Called on a background thread. */
    private val onText: (String) -> Unit,
    /** Finished: null = fine, else a message for the user. Called once, on a background thread. */
    private val onEnd: (String?) -> Unit,
) {
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var ended = false
    @Volatile private var stopping = false
    private val queue = ArrayList<String>()
    val text = Transcript()

    fun start() = thread(name = "tonefixer-voice") {
        try {
            val w = WebSocket.connect("$url?key=$key") { msg -> onMessage(msg) }
            ws = w
            w.send(JSONObject().put("setup", JSONObject().put("model", "models/$model").put("inputAudioTranscription", JSONObject())).toString())
            w.readLoop()
            finish(if (stopping || ready && w.closeCode == 1000) null else closeMessage(w.closeCode, w.closeReason))
        } catch (e: Exception) {
            finish(if (stopping) null else "No connection.")
        }
    }

    /** 16 kHz 16-bit mono PCM. */
    fun audio(pcm: ByteArray, len: Int) {
        if (stopping || ended) return
        val msg = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", Base64.getEncoder().encodeToString(pcm.copyOf(len))).put("mimeType", "audio/pcm;rate=16000"))).toString()
        synchronized(queue) { if (!ready) { if (queue.size < 200) queue.add(msg); return } }
        runCatching { ws?.send(msg) }
    }

    /** No more audio: the last words still arrive, then onEnd. */
    fun stop() {
        if (stopping) return
        stopping = true
        if (ready) runCatching { ws?.send("""{"realtimeInput":{"audioStreamEnd":true}}""") }
        thread { Thread.sleep(if (ready) 3000 else 0); ws?.close() ?: finish(null) }
    }

    fun cancel() { stopping = true; ws?.close() }

    private fun onMessage(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (m.has("setupComplete")) {
            val pending = synchronized(queue) { ready = true; queue.toList().also { queue.clear() } }
            pending.forEach { runCatching { ws?.send(it) } }
            return
        }
        val c = m.optJSONObject("serverContent") ?: return
        c.optJSONObject("interimInputTranscription")?.let { text.interim(it.optString("text")); onText(text.joined()) }
        c.optJSONObject("inputTranscription")?.let { text.final(it.optString("text")); onText(text.joined()) }
        if (c.optBoolean("generationComplete") && stopping) ws?.close()
    }

    private fun finish(error: String?) { if (!ended) { ended = true; onEnd(error) } }

    private fun closeMessage(code: Int, reason: String) = when {
        reason.contains("key", ignoreCase = true) -> "Gemini didn't accept the API key. Check it in Tone Fixer."
        reason.isNotBlank() -> "Voice typing stopped: $reason"
        else -> "Voice typing stopped ($code)."
    }

    /** Finished sentences + the one being spoken, joined with spaces. */
    class Transcript {
        private val finals = ArrayList<String>()
        private var interim = ""
        @Synchronized fun interim(t: String) { interim = t.trim() }
        @Synchronized fun final(t: String) { if (t.isNotBlank()) finals.add(t.trim()); interim = "" }
        @Synchronized fun joined() = (finals + interim).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * A small WebSocket client (RFC 6455, client side only): enough for Gemini's live API, without a
 * library. Text frames out (masked), text/binary frames in, ping → pong, close. A desktop JVM's
 * https.proxyHost is honoured (tests in the cloud box); phones connect directly.
 */
class WebSocket private constructor(private val socket: Socket, private val onMessage: (String) -> Unit) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    var closeCode = 1006; private set
    var closeReason = ""; private set

    companion object {
        fun connect(url: String, onMessage: (String) -> Unit): WebSocket {
            val u = URI(url)
            val secure = u.scheme == "wss"
            val port = if (u.port > 0) u.port else if (secure) 443 else 80
            val proxyHost = System.getProperty("https.proxyHost")?.takeIf { secure && it.isNotEmpty() }
            var s: Socket = Socket()
            if (proxyHost != null) {
                s.connect(InetSocketAddress(proxyHost, System.getProperty("https.proxyPort")?.toIntOrNull() ?: 443), 10_000)
                s.getOutputStream().write("CONNECT ${u.host}:$port HTTP/1.1\r\nHost: ${u.host}:$port\r\n\r\n".toByteArray())
                val head = readHead(s.getInputStream())
                require(head.startsWith("HTTP/1.1 200") || head.startsWith("HTTP/1.0 200")) { "Proxy: ${head.lineSequence().first()}" }
            } else s.connect(InetSocketAddress(u.host, port), 10_000)
            if (secure) s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, u.host, port, true).also { (it as SSLSocket).startHandshake() }
            s.soTimeout = 0
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            val head = readHead(s.getInputStream())
            if (!head.startsWith("HTTP/1.1 101")) { s.close(); throw java.io.IOException("Handshake: ${head.lineSequence().first()}") }
            return WebSocket(s, onMessage)
        }

        private fun readHead(i: InputStream): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = i.read(); if (c < 0) break
                b.write(c)
                val a = b.toByteArray(); val n = a.size
                if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) break
            }
            return b.toString("UTF-8")
        }
    }

    @Synchronized fun send(text: String) = frame(0x1, text.toByteArray())

    fun close() {
        runCatching { frame(0x8, byteArrayOf(0x03, 0xE8.toByte())) } // 1000 = normal
        runCatching { socket.close() }
    }

    /** Reads until the connection closes, calling onMessage for every text (or binary) message. */
    fun readLoop() {
        val msg = ByteArrayOutputStream()
        try {
            while (true) {
                val b0 = input.read(); if (b0 < 0) break
                val b1 = input.read(); if (b1 < 0) break
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) { val e = readN(2); len = ((e[0].toLong() and 0xFF) shl 8) or (e[1].toLong() and 0xFF) }
                if (len == 127L) len = readN(8).fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xFF) }
                val mask = if (b1 and 0x80 != 0) readN(4) else null
                val data = readN(len.toInt())
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0F) {
                    0x0, 0x1, 0x2 -> { msg.write(data); if (b0 and 0x80 != 0) { onMessage(msg.toString("UTF-8")); msg.reset() } }
                    0x8 -> {
                        if (data.size >= 2) closeCode = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        if (data.size > 2) closeReason = String(data, 2, data.size - 2, Charsets.UTF_8)
                        runCatching { frame(0x8, data.copyOf(minOf(2, data.size))) }
                        break
                    }
                    0x9 -> runCatching { synchronized(this) { frame(0xA, data) } }
                }
            }
        } catch (_: Exception) { /* closed */ }
        runCatching { socket.close() }
    }

    private fun readN(n: Int): ByteArray {
        val a = ByteArray(n); var o = 0
        while (o < n) { val r = input.read(a, o, n - o); if (r < 0) throw java.io.EOFException(); o += r }
        return a
    }

    /** One final frame, masked (clients must mask). */
    private fun frame(opcode: Int, data: ByteArray) {
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)
        when {
            data.size < 126 -> head.write(0x80 or data.size)
            data.size < 65536 -> { head.write(0x80 or 126); head.write(data.size shr 8); head.write(data.size and 0xFF) }
            else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        head.write(mask)
        val body = ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(output) { output.write(head.toByteArray()); output.write(body); output.flush() }
    }
}
