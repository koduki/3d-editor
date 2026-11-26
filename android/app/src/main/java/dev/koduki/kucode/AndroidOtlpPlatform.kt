package dev.koduki.kucode

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [KeyValueStorage] backed by private [SharedPreferences] (`kucode_otlp_prefs`).
 */
class SharedPreferencesKeyValueStorage(
    private val prefs: SharedPreferences,
) : KeyValueStorage {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val PREFS_NAME = "kucode_otlp_prefs"

        fun create(context: Context): SharedPreferencesKeyValueStorage =
            SharedPreferencesKeyValueStorage(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}

/**
 * Android Keystore AES-256-GCM implementation of [OtlpSecretCipher].
 *
 * Ciphertext wire format: `v1:<b64iv>:<b64ct>` using standard `java.util.Base64`
 * and 128-bit GCM authentication tag.
 */
class AndroidKeyStoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : OtlpSecretCipher {

    private val lock = Any()

    override fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64Iv = Base64.getEncoder().encodeToString(iv)
        val b64Ct = Base64.getEncoder().encodeToString(ciphertext)
        return "$FORMAT_PREFIX:$b64Iv:$b64Ct"
    }

    override fun decrypt(ciphertext: String): String? {
        val parts = ciphertext.split(':')
        if (parts.size != 3 || parts[0] != FORMAT_PREFIX) return null
        return runCatching {
            val iv = Base64.getDecoder().decode(parts[1])
            val encryptedBytes = Base64.getDecoder().decode(parts[2])
            if (iv.isEmpty() || encryptedBytes.isEmpty()) return null
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(keyAlias, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintextBytes = cipher.doFinal(encryptedBytes)
            String(plaintextBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "kucode_otlp_auth_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_PREFIX = "v1"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

/**
 * Checks whether the active Android network is metered using the non-deprecated
 * [ConnectivityManager.isActiveNetworkMetered] API. Returns `false` safely on error.
 */
fun isActiveNetworkMetered(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    cm.isActiveNetworkMetered
}.getOrDefault(false)
