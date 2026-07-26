package com.techwombat.liberates

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only the Ollama password encrypted by the device's Android Keystore key. */
object OllamaCredentialsStore {
    private const val PREFERENCES_NAME = "ollama_connection"
    private const val KEY_ALIAS = "wombat_liberates_ollama_credentials"
    private const val BASE_URL = "base_url"
    private const val MODEL = "model"
    private const val USERNAME = "username"
    private const val ENCRYPTED_PASSWORD = "encrypted_password"

    data class Credentials(val baseUrl: String, val model: String, val username: String, val password: String)

    fun load(context: Context): Credentials? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val password = preferences.getString(ENCRYPTED_PASSWORD, null)?.let(::decrypt) ?: return null
        return Credentials(
            baseUrl = preferences.getString(BASE_URL, "").orEmpty(),
            model = preferences.getString(MODEL, "mistral-nemo:latest").orEmpty(),
            username = preferences.getString(USERNAME, "").orEmpty(),
            password = password,
        )
    }

    fun save(context: Context, credentials: Credentials) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(BASE_URL, credentials.baseUrl)
            .putString(MODEL, credentials.model)
            .putString(USERNAME, credentials.username)
            .putString(ENCRYPTED_PASSWORD, encrypt(credentials.password))
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2) { "Saved Ollama credentials are invalid." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
}
