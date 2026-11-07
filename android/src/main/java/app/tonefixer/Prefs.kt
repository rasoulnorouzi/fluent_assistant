package app.tonefixer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings in SharedPreferences. API keys are encrypted with an AES-GCM key that lives in
 * the Android Keystore (it never leaves secure hardware), so the prefs file only holds ciphertext.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tonefixer", Context.MODE_PRIVATE)

    var provider: String?
        get() = sp.getString("provider", null)
        set(v) = sp.edit().putString("provider", v).apply()

    /** Tone used by a tap on the bubble: the last one picked anywhere. */
    var tone: String?
        get() = sp.getString("tone", null)
        set(v) = sp.edit().putString("tone", v).apply()

    /** False after the bubble was dropped on ✕; opening the app sets it back to true. */
    var bubbleOn: Boolean
        get() = sp.getBoolean("bubbleOn", true)
        set(v) = sp.edit().putBoolean("bubbleOn", v).apply()

    /** Which screen edge the bubble sits on (it snaps to the nearer one after a drag). */
    var bubbleRight: Boolean
        get() = sp.getBoolean("bubbleRight", true)
        set(v) = sp.edit().putBoolean("bubbleRight", v).apply()

    /** Last translate mode used (روان / رسمی); null = the default (fluent). */
    var translateMode: String?
        get() = sp.getString("translateMode", null)
        set(v) = sp.edit().putString("translateMode", v).apply()

    /** Bubble's vertical position in px from the top, or -1 for "not moved yet". */
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    /** Set while the user is off allowing "Install unknown apps"; the update continues when they come back,
     *  even if Android restarted the app in between. */
    var updateAfterAllow: Boolean
        get() = sp.getBoolean("updateAfterAllow", false)
        set(v) = sp.edit().putBoolean("updateAfterAllow", v).apply()

    /** When Settings last looked for an update (ms), so the automatic check doesn't run on every open. */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    fun model(provider: String): String? = sp.getString("model_$provider", null)
    fun setModel(provider: String, model: String) = sp.edit().putString("model_$provider", model).apply()

    /**
     * The models shown for [p]: the built-in ones until the user adds or removes some. Never empty.
     * (A model picked with the old "Custom…" field is kept in the list.)
     */
    fun models(p: Llm.Provider): List<String> {
        val saved = sp.getString("models_${p.id}", null)?.let { s ->
            runCatching { org.json.JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
        }
        val list = saved ?: (p.models.map { it.id } + listOfNotNull(model(p.id))).distinct()
        return list.ifEmpty { p.models.map { it.id } }
    }

    fun setModels(p: Llm.Provider, ids: List<String>?) {
        if (ids == null) sp.edit().remove("models_${p.id}").remove("model_${p.id}").apply() // restore defaults
        else sp.edit().putString("models_${p.id}", org.json.JSONArray(ids).toString()).apply()
    }

    /** The model a rewrite uses: the picked one if it's still in the list, else the first. */
    fun currentModel(p: Llm.Provider): String = models(p).let { l -> model(p.id)?.takeIf { it in l } ?: l.first() }

    fun key(provider: String): String? = cache.getOrPut(provider) {
        val enc = sp.getString("key_$provider", null) ?: return null
        runCatching { decrypt(enc) }.getOrNull() ?: return null
    }

    /** True after the Test button got an answer with the current key. */
    fun keyOk(provider: String) = sp.getBoolean("keyOk_$provider", false)
    fun setKeyOk(provider: String, ok: Boolean) = sp.edit().putBoolean("keyOk_$provider", ok).apply()

    fun setKey(provider: String, key: String?) {
        cache.remove(provider)
        setKeyOk(provider, false)
        val k = clean(key)
        if (k.isEmpty()) sp.edit().remove("key_$provider").apply()
        else sp.edit().putString("key_$provider", encrypt(k)).apply()
    }

    companion object {
        /** Keys are plain ASCII; drop spaces, zero-width and other invisible characters picked up when pasting. */
        fun clean(key: String?) = key.orEmpty().filter { it.code in 0x21..0x7E }

        private const val ALIAS = "tonefixer-api-keys"
        private val cache = mutableMapOf<String, String>() // decrypted keys, process memory only

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
        }

        /** Returns base64(iv + ciphertext). */
        private fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }

        private fun decrypt(enc: String): String {
            val all = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all, 0, 12))
            return String(c.doFinal(all, 12, all.size - 12))
        }
    }
}
