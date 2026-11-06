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

    /** Bubble's vertical position in px from the top, or -1 for "not moved yet". */
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    fun model(provider: String): String? = sp.getString("model_$provider", null)
    fun setModel(provider: String, model: String) = sp.edit().putString("model_$provider", model).apply()

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
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) sp.edit().remove("key_$provider").apply()
        else sp.edit().putString("key_$provider", encrypt(k)).apply()
    }

    private companion object {
        const val ALIAS = "tonefixer-api-keys"
        val cache = mutableMapOf<String, String>() // decrypted keys, process memory only

        fun secretKey(): SecretKey {
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
        fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }

        fun decrypt(enc: String): String {
            val all = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all, 0, 12))
            return String(c.doFinal(all, 12, all.size - 12))
        }
    }
}
