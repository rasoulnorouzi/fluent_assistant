package app.tonefixer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ceil

/**
 * Everything about talking to the LLM: config from the shared tones/providers JSON, request bodies,
 * streaming (SSE) and error messages. Follows shared/API.md exactly.
 */
object Llm {

    class Tone(val id: String, val label: String, val letter: String, val style: String)
    class Model(val id: String, val label: String, val extra: JSONObject)
    class Provider(
        val id: String, val label: String, val format: String, val url: String, val warmUrl: String,
        val keyHeader: String, val headers: Map<String, String>, val keyHelp: String, val models: List<Model>,
    )
    class Config(
        val system: String, val tones: List<Tone>, val defaultTone: String,
        /** Wraps the user's text, e.g. "<text>\n{text}\n</text>", so it's never taken as an instruction. */
        val userTemplate: String,
        val providers: Map<String, Provider>, val defaultProvider: String, val maxTokens: JSONObject,
        /** Translate to Persian (tones.json → translate): its own instructions and modes (روان / رسمی). */
        val translateSystem: String = "", val translateModes: List<Tone> = emptyList(), val translateDefault: String = "",
    ) {
        fun tone(id: String?) = tones.firstOrNull { it.id == id } ?: tones.first { it.id == defaultTone }
        fun translateMode(id: String?) = translateModes.firstOrNull { it.id == id }
            ?: translateModes.firstOrNull { it.id == translateDefault } ?: translateModes.first()
        fun provider(id: String?) = providers[id] ?: providers.getValue(defaultProvider)
    }

    // ---------- Config ----------

    @Volatile private var cached: Config? = null

    fun config(ctx: Context): Config = cached ?: parseConfig(
        ctx.assets.open("tones.json").reader().readText(),
        ctx.assets.open("providers.json").reader().readText(),
    ).also { cached = it }

    fun parseConfig(tonesJson: String, providersJson: String): Config {
        val t = JSONObject(tonesJson)
        val p = JSONObject(providersJson)
        val tones = t.getJSONArray("tones").objects().map {
            Tone(it.getString("id"), it.getString("label"), it.getString("letter"), it.getString("style"))
        }
        val providersObj = p.getJSONObject("providers")
        val providers = providersObj.keys().asSequence().associateWith { id ->
            val o = providersObj.getJSONObject(id)
            Provider(
                id = id, label = o.getString("label"), format = o.getString("format"),
                url = o.getString("url"), warmUrl = o.getString("warmUrl"), keyHeader = o.getString("keyHeader"),
                headers = o.optJSONObject("headers")?.toStringMap() ?: emptyMap(),
                keyHelp = o.optString("keyHelp"),
                models = o.getJSONArray("models").objects().map {
                    Model(it.getString("id"), it.getString("label"), it.optJSONObject("extra") ?: JSONObject())
                },
            )
        }
        val tr = t.optJSONObject("translate")
        val trModes = tr?.optJSONArray("modes")?.objects()?.map {
            Tone(it.getString("id"), it.getString("label"), it.optString("letter"), it.getString("style"))
        }.orEmpty()
        return Config(t.getString("system"), tones, t.getString("defaultTone"), t.optString("userTemplate", "{text}"), providers,
            p.getString("defaultProvider"), p.getJSONObject("maxTokens"),
            tr?.optString("system").orEmpty(), trModes, tr?.optString("defaultMode").orEmpty())
    }

    // ---------- Request ----------

    fun maxTokens(cfg: Config, chars: Int): Int {
        val m = cfg.maxTokens
        val n = ceil(chars / m.getDouble("charsPerToken")).toInt() * m.getInt("multiplier") + m.getInt("base")
        return n.coerceIn(m.getInt("min"), m.getInt("cap"))
    }

    /** Body for [provider] + [modelId]. A model id that isn't in providers.json gets no `extra`. */
    /**
     * [translate] = use the translate-to-Persian instructions ([tone] is then a translate mode). Persian
     * needs about twice the tokens of English for the same text, so translations get twice the room.
     */
    fun buildBody(cfg: Config, provider: Provider, modelId: String, tone: Tone, text: String, translate: Boolean = false): JSONObject {
        val system = (if (translate) cfg.translateSystem else cfg.system).replace("{style}", tone.style)
        val max = if (translate) (maxTokens(cfg, text.length) * 2).coerceAtMost(cfg.maxTokens.getInt("cap")) else maxTokens(cfg, text.length)
        @Suppress("NAME_SHADOWING") val text = cfg.userTemplate.replace("{text}", text)
        val body = when (provider.format) {
            "gemini" -> JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject().put("maxOutputTokens", max))
            "claude" -> JSONObject()
                .put("model", modelId).put("max_tokens", max).put("stream", true).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
            else -> error("Unknown format ${provider.format}")
        }
        provider.models.firstOrNull { it.id == modelId }?.let { deepMerge(body, it.extra) }
        return body
    }

    /** Objects merge key by key; anything else replaces. Mutates and returns [base]. */
    fun deepMerge(base: JSONObject, extra: JSONObject): JSONObject {
        for (k in extra.keys()) {
            val v = extra.get(k)
            val b = base.opt(k)
            if (v is JSONObject && b is JSONObject) deepMerge(b, v)
            else base.put(k, if (v is JSONObject) JSONObject(v.toString()) else if (v is JSONArray) JSONArray(v.toString()) else v)
        }
        return base
    }

    fun requestUrl(provider: Provider, modelId: String) =
        provider.url.replace("{model}", URLEncoder.encode(modelId, "UTF-8"))

    // ---------- Streaming response ----------

    /** One parsed SSE `data:` object. [stop] is "done", "cut" (hit max tokens) or "blocked". */
    class Chunk(val text: String = "", val stop: String? = null, val error: String? = null)

    /** Returns the JSON after `data:` or null for any other SSE line. */
    fun sseData(line: String): String? {
        val l = line.trimEnd('\r')
        if (!l.startsWith("data:")) return null
        val d = l.substring(5).trim()
        return if (d.isEmpty() || d == "[DONE]") null else d
    }

    fun parseChunk(format: String, data: JSONObject): Chunk = when (format) {
        "gemini" -> {
            val err = data.optJSONObject("error")
            if (err != null) Chunk(error = err.optString("message", "Error ${err.optInt("code")}"))
            else if (data.optJSONObject("promptFeedback")?.has("blockReason") == true) Chunk(stop = "blocked")
            else {
                val c = data.optJSONArray("candidates")?.optJSONObject(0)
                val text = buildString {
                    c?.optJSONObject("content")?.optJSONArray("parts")?.objects()?.forEach {
                        if (!it.optBoolean("thought")) append(it.optString("text"))
                    }
                }
                val stop = when (val r = c?.optString("finishReason").orEmpty()) {
                    "", "FINISH_REASON_UNSPECIFIED" -> null
                    "STOP" -> "done"
                    "MAX_TOKENS" -> "cut"
                    else -> "blocked" // SAFETY, RECITATION, ...
                }
                Chunk(text, stop)
            }
        }
        "claude" -> when (data.optString("type")) {
            "content_block_delta" -> {
                val d = data.optJSONObject("delta")
                Chunk(if (d?.optString("type") == "text_delta") d.optString("text") else "")
            }
            "message_delta" -> Chunk(stop = when (data.optJSONObject("delta")?.optString("stop_reason")) {
                "max_tokens" -> "cut"
                "refusal" -> "blocked"
                else -> null // end_turn etc.: wait for message_stop
            })
            "message_stop" -> Chunk(stop = "done")
            "error" -> Chunk(error = data.optJSONObject("error")?.optString("message") ?: "Unknown error")
            else -> Chunk()
        }
        else -> error("Unknown format $format")
    }

    /** User-facing message for a non-200 response (shared/API.md §5). */
    fun errorMessage(status: Int, body: String?): String {
        val detail = runCatching { JSONObject(body!!).getJSONObject("error").getString("message") }.getOrNull()
        return when {
            status == 400 -> "The provider rejected the request" + (detail?.let { ": $it" } ?: ".")
            status == 401 || status == 403 -> "API key not accepted. Check it in Settings."
            status == 404 -> "Model not found. Pick another in Settings."
            // Free keys: e.g. 500 requests per day per model. Another model has its own quota.
            status == 429 && body != null && Regex("per ?day", RegexOption.IGNORE_CASE).containsMatchIn(body) ->
                "Daily free limit reached for this model. Pick another model in Settings, or try again tomorrow."
            status == 429 -> "Too many requests or quota used up. Try again in a moment."
            status >= 500 -> "The provider is having trouble. Try again."
            else -> "Request failed ($status)" + (detail?.let { ": $it" } ?: ".")
        }
    }

    /** Final result of a [stream] call. [stop] as in [Chunk]; "cancelled" if [Call.cancel] was used. */
    class Outcome(val text: String, val stop: String?, val error: String?)

    /** Handle for one running request, so the UI can cancel it from another thread. */
    class Call {
        @Volatile var cancelled = false; private set
        @Volatile internal var conn: HttpURLConnection? = null
        fun cancel() { cancelled = true; conn?.disconnect() }
    }

    /**
     * Blocking: run on a background thread. Calls [onText] with each new piece of text as it
     * arrives (on the calling thread).
     */
    fun stream(provider: Provider, modelId: String, key: String, body: JSONObject, call: Call, onText: (String) -> Unit): Outcome {
        val sb = StringBuilder()
        try {
            val conn = URL(requestUrl(provider, modelId)).openConnection() as HttpURLConnection
            call.conn = conn
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty(provider.keyHeader, key)
            provider.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = conn.responseCode
            if (status != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                return Outcome("", null, errorMessage(status, err))
            }
            var stop: String? = null
            conn.inputStream.bufferedReader().use { r ->
                while (!call.cancelled) {
                    val line = r.readLine() ?: break
                    val data = sseData(line) ?: continue
                    val chunk = parseChunk(provider.format, JSONObject(data))
                    if (chunk.error != null) return Outcome(sb.toString(), null, chunk.error)
                    if (chunk.text.isNotEmpty()) { sb.append(chunk.text); onText(chunk.text) }
                    // Claude sends max_tokens/refusal in message_delta, then message_stop ("done"): keep the first.
                    if (chunk.stop != null && stop != "cut" && stop != "blocked") stop = chunk.stop
                    if (chunk.stop == "done") break
                }
            }
            if (call.cancelled) return Outcome(sb.toString(), "cancelled", null)
            if (stop == "blocked") return Outcome(sb.toString(), stop, "The provider blocked this text.")
            return Outcome(sb.toString(), stop ?: "done", null)
        } catch (e: IOException) {
            if (call.cancelled) return Outcome(sb.toString(), "cancelled", null)
            val msg = if (e is SocketTimeoutException) "The provider took too long. Try again." else "No connection."
            return Outcome(sb.toString(), null, msg)
        } catch (e: Exception) {
            // Bad header value (odd character in a pasted key), non-JSON data line, ... Never crash the service.
            if (call.cancelled) return Outcome(sb.toString(), "cancelled", null)
            val msg = if (e is IllegalArgumentException) "The API key has an invalid character. Paste it again in Settings."
                      else "Unexpected response from the provider."
            return Outcome(sb.toString(), null, msg)
        } finally {
            call.conn = null
        }
    }

    // ---------- Pre-warm ----------

    @Volatile private var lastWarm = 0L
    @Volatile private var lastWarmUrl = ""

    /** Opens DNS + TCP + TLS to the provider so the real request reuses the pooled connection. */
    fun warm(provider: Provider) {
        val now = System.currentTimeMillis()
        if (provider.warmUrl == lastWarmUrl && now - lastWarm < 60_000) return
        lastWarm = now
        lastWarmUrl = provider.warmUrl
        Thread {
            runCatching {
                val c = URL(provider.warmUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 5_000
                c.readTimeout = 5_000
                (if (c.responseCode < 400) c.inputStream else c.errorStream)?.use { it.readBytes() }
            }
        }.start()
    }

    // ---------- small JSON helpers ----------

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
}
