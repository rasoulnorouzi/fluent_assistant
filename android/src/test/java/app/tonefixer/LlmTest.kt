package app.tonefixer

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class LlmTest {

    // Unit tests run with android/ as the working directory.
    private val cfg = Llm.parseConfig(File("../shared/tones.json").readText(), File("../shared/providers.json").readText())
    private val gemini = cfg.providers.getValue("gemini")
    private val claude = cfg.providers.getValue("claude")
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    // ---------- config ----------

    @Test fun configLoads() {
        assertEquals(8, cfg.tones.size)
        assertEquals("daily", cfg.tone(null).id)
        assertEquals("polite", cfg.tone("polite").id)
        assertEquals("daily", cfg.tone("friendly").id) // removed tone → default
        assertEquals("daily", cfg.tone("no-such-tone").id)
        assertEquals("gemini", cfg.provider(null).id)
        assertEquals("2026-10-21", claude.headers["anthropic-version"])
        assertTrue(gemini.models.isNotEmpty() && claude.models.isNotEmpty())
        cfg.tones.forEach { assertEquals(1, it.letter.length) }
    }

    @Test fun translateConfigAndBody() {
        assertEquals(listOf("fluent", "formal"), cfg.translateModes.map { it.id })
        assertEquals(listOf("روان", "رسمی"), cfg.translateModes.map { it.label })
        assertEquals("fluent", cfg.translateMode(null).id)
        assertEquals("formal", cfg.translateMode("formal").id)
        val m = claude.models.first().id
        val b = Llm.buildBody(cfg, claude, m, cfg.translateMode("formal"), "Hallo", translate = true)
        val sys = b.getString("system")
        assertTrue(sys.startsWith("You translate text into Persian"))
        assertTrue(sys.contains("Formal (رسمی)") && !sys.contains("{style}"))
        assertEquals("<text>\nHallo\n</text>", b.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(400, b.getInt("max_tokens")) // twice the rewrite room (Persian needs more tokens)
        assertTrue(claude.models.any { it.id == "claude-sonnet-5-5" })
    }

    @Test fun maxTokensScalesAndClamps() {
        assertEquals(200, Llm.maxTokens(cfg, 5))          // min
        assertEquals(100 * 3 + 150, Llm.maxTokens(cfg, 300)) // ceil(300/3)=100
        assertEquals(4096, Llm.maxTokens(cfg, 100_000))   // cap
    }

    // ---------- request bodies ----------

    @Test fun geminiBody() {
        val m = gemini.models.first()
        val b = Llm.buildBody(cfg, gemini, m.id, cfg.tone("formal"), "hello there")
        val sys = b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(sys.contains(cfg.tone("formal").style))
        assertFalse(sys.contains("{style}"))
        assertEquals("<text>\nhello there\n</text>", b.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        val gen = b.getJSONObject("generationConfig")
        assertEquals(200, gen.getInt("maxOutputTokens"))                       // base body kept...
        assertEquals("minimal", gen.getJSONObject("thinkingConfig").getString("thinkingLevel")) // ...extra merged in
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/${m.id}:streamGenerateContent?alt=sse",
            Llm.requestUrl(gemini, m.id))
    }

    @Test fun claudeBody() {
        val m = claude.models.first()
        val b = Llm.buildBody(cfg, claude, m.id, cfg.tone("casual"), "yo")
        assertEquals(m.id, b.getString("model"))
        assertTrue(b.getBoolean("stream"))
        assertTrue(b.getString("system").contains(cfg.tone("casual").style))
        assertEquals("<text>\nyo\n</text>", b.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
        assertEquals("low", b.getJSONObject("output_config").getString("effort"))
        assertFalse(b.has("temperature"))
    }

    @Test fun anotherVersionAddsEarlierAnswers() {
        val c = Llm.withEarlier(Llm.buildBody(cfg, claude, claude.models.first().id, cfg.tone("daily"), "hi"), "claude", listOf("Hi!", "Hey!"))
        val msgs = c.getJSONArray("messages")
        assertEquals(5, msgs.length())
        assertEquals("assistant", msgs.getJSONObject(1).getString("role")); assertEquals("Hi!", msgs.getJSONObject(1).getString("content"))
        assertEquals(Llm.AGAIN, msgs.getJSONObject(4).getString("content"))
        val g = Llm.withEarlier(Llm.buildBody(cfg, gemini, gemini.models.first().id, cfg.tone("daily"), "hi"), "gemini", listOf("Hi!"))
        val cs = g.getJSONArray("contents")
        assertEquals(3, cs.length())
        assertEquals("model", cs.getJSONObject(1).getString("role"))
        assertEquals("user", cs.getJSONObject(2).getString("role"))
        assertEquals(1, Llm.withEarlier(Llm.buildBody(cfg, claude, claude.models.first().id, cfg.tone("daily"), "hi"), "claude", emptyList()).getJSONArray("messages").length())
    }

    @Test fun customModelGetsNoExtra() {
        val b = Llm.buildBody(cfg, gemini, "gemini-9-ultra", cfg.tone("daily"), "x")
        assertFalse(b.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun deepMergeMergesObjectsAndReplacesValues() {
        val base = JSONObject("""{"a":{"x":1,"y":2},"b":1,"c":[1]}""")
        Llm.deepMerge(base, JSONObject("""{"a":{"y":3,"z":4},"b":{"k":true},"c":[2,3]}"""))
        val a = base.getJSONObject("a")
        assertEquals(listOf(1, 3, 4), listOf(a.getInt("x"), a.getInt("y"), a.getInt("z")))
        assertTrue(base.getJSONObject("b").getBoolean("k"))
        assertEquals(2, base.getJSONArray("c").length())
    }

    // ---------- SSE parsing ----------

    @Test fun sseDataLines() {
        assertEquals("{\"a\":1}", Llm.sseData("data: {\"a\":1}\r"))
        assertEquals("{\"a\":1}", Llm.sseData("data:{\"a\":1}"))
        assertNull(Llm.sseData("event: ping"))
        assertNull(Llm.sseData(""))
        assertNull(Llm.sseData("data: [DONE]"))
    }

    private fun parseAll(format: String, sse: String): Pair<String, String?> {
        val sb = StringBuilder(); var stop: String? = null
        sse.lines().mapNotNull { Llm.sseData(it) }.forEach {
            val c = Llm.parseChunk(format, JSONObject(it))
            sb.append(c.text); if (c.stop != null) stop = c.stop
        }
        return sb.toString() to stop
    }

    @Test fun geminiRecordedStream() {
        val (text, stop) = parseAll("gemini", res("gemini_ok.sse"))
        assertEquals("Dear Mr. Ahmadi,\n\nPlease accept my apologies for the delay. I will send the report to you by Friday.\n\nBest regards,", text)
        assertEquals("done", stop)
    }

    @Test fun geminiCutOff() {
        assertEquals("Dear Mr. Ahmadi, I will send" to "cut", parseAll("gemini", res("gemini_cut.sse")))
    }

    @Test fun geminiSkipsThoughtParts() {
        val c = Llm.parseChunk("gemini", JSONObject("""{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},{"text":"Hi"}]}}]}"""))
        assertEquals("Hi", c.text)
    }

    @Test fun geminiSafetyIsBlocked() {
        assertEquals("blocked", Llm.parseChunk("gemini", JSONObject("""{"candidates":[{"finishReason":"SAFETY"}]}""")).stop)
    }

    @Test fun claudeStream() {
        assertEquals("Hi Sara! I'm sorry, I can't make it tomorrow." to "done", parseAll("claude", res("claude_ok.sse")))
    }

    @Test fun claudeStreamError() {
        val err = res("claude_overloaded.sse").lines().mapNotNull { Llm.sseData(it) }
            .map { Llm.parseChunk("claude", JSONObject(it)) }.firstNotNullOf { it.error }
        assertEquals("Overloaded", err)
    }

    @Test fun claudeMaxTokensIsCut() {
        assertEquals("cut", Llm.parseChunk("claude", JSONObject("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""")).stop)
    }

    // ---------- error messages ----------

    @Test fun errorMessages() {
        assertEquals("Model not found. Pick another in Settings.", Llm.errorMessage(404, res("gemini_404.json")))
        assertEquals("API key not accepted. Check it in Settings.", Llm.errorMessage(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertEquals("The provider rejected the request: bad thing", Llm.errorMessage(400, """{"error":{"message":"bad thing"}}"""))
        assertEquals("The provider rejected the request.", Llm.errorMessage(400, "not json"))
        assertEquals("The provider is having trouble. Try again.", Llm.errorMessage(529, null))
        assertEquals("Daily free limit reached for this model. Pick another model in Settings, or try again tomorrow.",
            Llm.errorMessage(429, "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota. * Quota exceeded for metric: generate_content_free_tier_requests, limit: 500, description: Generate content requests per day limit exceeded on Free Tier.\"}}"))
        assertEquals("Too many requests or quota used up. Try again in a moment.", Llm.errorMessage(429, null))
    }

    // ---------- full stream() over real HTTP (local server) ----------

    private fun serve(status: Int, body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seenKey: String? = null
        server.createContext("/") { ex ->
            seenKey = ex.requestHeaders.getFirst("x-goog-api-key")
            ex.requestBody.readBytes()
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
        assertEquals("test-key", seenKey)
    }

    private fun localGemini(base: String) = Llm.Provider("gemini", "G", "gemini", "$base/{model}", base,
        "x-goog-api-key", emptyMap(), "", gemini.models)

    @Test fun streamEndToEnd() = serve(200, res("gemini_ok.sse")) { base ->
        val pieces = mutableListOf<String>()
        val p = localGemini(base)
        val out = Llm.stream(p, "m", "test-key", Llm.buildBody(cfg, p, "m", cfg.tone("formal"), "x"), Llm.Call()) { pieces += it }
        assertNull(out.error)
        assertEquals("done", out.stop)
        assertTrue(pieces.size >= 3)
        assertEquals(pieces.joinToString(""), out.text)
    }

    @Test fun streamHttpError() = serve(404, res("gemini_404.json")) { base ->
        val p = localGemini(base)
        val out = Llm.stream(p, "m", "test-key", JSONObject(), Llm.Call()) {}
        assertEquals("Model not found. Pick another in Settings.", out.error)
    }

    // ---------- regressions from the device review ----------

    private fun localClaude(base: String) = Llm.Provider("claude", "C", "claude", base, base,
        "x-goog-api-key", emptyMap(), "", claude.models) // header name reused so serve() can check the key

    @Test fun claudeCutOffSurvivesMessageStop() {
        val sse = res("claude_ok.sse").replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"max_tokens\"")
        serve(200, sse) { base ->
            val out = Llm.stream(localClaude(base), "m", "test-key", JSONObject(), Llm.Call()) {}
            assertEquals("cut", out.stop)
            assertNull(out.error)
        }
    }

    @Test fun claudeRefusalIsAnError() {
        val sse = res("claude_ok.sse").replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"refusal\"")
        serve(200, sse) { base ->
            val out = Llm.stream(localClaude(base), "m", "test-key", JSONObject(), Llm.Call()) {}
            assertEquals("The provider blocked this text.", out.error)
        }
    }

    @Test fun geminiPromptBlocked() {
        assertEquals("blocked", Llm.parseChunk("gemini", JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")).stop)
    }

    @Test fun badKeyCharacterDoesNotCrash() {
        val out = Llm.stream(localGemini("http://127.0.0.1:1"), "m", "abc\u0001def", JSONObject(), Llm.Call()) {}
        assertTrue(out.error!!.isNotEmpty())
    }

    @Test fun garbageDataLineDoesNotCrash() = serve(200, "data: not json\n\n") { base ->
        val out = Llm.stream(localGemini(base), "m", "test-key", JSONObject(), Llm.Call()) {}
        assertEquals("Unexpected response from the provider.", out.error)
    }

    @Test fun keyCleaning() {
        assertEquals("AIzaABC-123_x", Prefs.clean(" AIza​ABC-123_x \n"))
    }

    /** Real API call through the real code. Runs only with GEMINI_KEY set (never in CI). */
    @Test fun liveGemini() {
        val key = System.getenv("GEMINI_KEY")
        org.junit.Assume.assumeTrue(!key.isNullOrBlank())
        for (m in gemini.models) {
            val body = Llm.buildBody(cfg, gemini, m.id, cfg.tone("polite"), "سلام، فردا نمی‌تونم بیام. جمعه خوبه؟")
            val t0 = System.nanoTime(); var first = 0L
            val out = Llm.stream(gemini, m.id, key, body, Llm.Call()) { if (first == 0L) first = System.nanoTime() }
            println("LIVE ${m.id}: first text ${(first - t0) / 1_000_000} ms, stop=${out.stop}, error=${out.error}\n  ${out.text}")
            assertNull(out.error)
            assertEquals("done", out.stop)
            assertTrue(out.text.isNotBlank())
        }
    }

    /** Real translation through the real code, with the language line split off. Runs only with CLAUDE_KEY set. */
    @Test fun liveClaudeTranslate() {
        val key = System.getenv("CLAUDE_KEY")
        org.junit.Assume.assumeTrue(!key.isNullOrBlank())
        for ((model, text) in listOf("claude-haiku-5-5" to "Hoi! Zin om zaterdag mee te gaan naar de markt? Ik haal je om 10 uur op.",
                                     "claude-sonnet-5-5" to "Break a leg tonight! It's a piece of cake.")) {
            val body = Llm.buildBody(cfg, claude, model, cfg.translateMode("fluent"), text, translate = true)
            val split = TextOps.LanguageLine(); var lang: String? = null
            val t0 = System.nanoTime(); var first = 0L
            val shown = StringBuilder()
            val out = Llm.stream(claude, model, key, body, Llm.Call()) {
                if (first == 0L) first = System.nanoTime()
                shown.append(split.feed(it) { l -> lang = l })
            }
            shown.append(split.flush())
            println("LIVE $model: first text ${(first - t0) / 1_000_000} ms, language=$lang, error=${out.error}\n  $shown")
            assertNull(out.error)
            assertTrue(!lang.isNullOrBlank() && shown.isNotBlank() && !shown.startsWith("\n"))
        }
    }

    @Test fun noConnection() {
        val p = localGemini("http://127.0.0.1:1")
        val out = Llm.stream(p, "m", "k", JSONObject(), Llm.Call()) {}
        assertEquals("No connection.", out.error)
    }
}
