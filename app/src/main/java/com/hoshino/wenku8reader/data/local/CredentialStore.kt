package com.hoshino.wenku8reader.data.local

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

/** Stores the optional account password encrypted with a key held by Android Keystore. */
internal class CredentialStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun username(): String? = preferences.getString(USERNAME_KEY, null)

    @Synchronized
    fun password(): String? {
        val ciphertext = preferences.getString(PASSWORD_KEY, null) ?: return null
        return try {
            decrypt(ciphertext)
        } catch (_: Exception) {
            // A restored or invalidated Keystore key cannot decrypt the old value.
            preferences.edit().remove(PASSWORD_KEY).apply()
            null
        }
    }

    @Synchronized
    fun saveAccount(username: String, password: String): Boolean {
        val ciphertext = try {
            encrypt(password)
        } catch (_: Exception) {
            preferences.edit().putString(USERNAME_KEY, username).remove(PASSWORD_KEY).commit()
            return false
        }
        return preferences.edit()
            .putString(USERNAME_KEY, username)
            .putString(PASSWORD_KEY, ciphertext)
            .commit()
    }

    @Synchronized
    fun migrateLegacy(legacyUsername: String?, legacyPassword: String?) {
        val existingPassword = this.password()
        val migratedPassword = if (existingPassword == null && legacyPassword != null) {
            runCatching { encrypt(legacyPassword) }.getOrNull()
        } else {
            null
        }
        val editor = preferences.edit()
        if (username().isNullOrBlank() && !legacyUsername.isNullOrBlank()) {
            editor.putString(USERNAME_KEY, legacyUsername)
        }
        if (migratedPassword != null) {
            editor.putString(PASSWORD_KEY, migratedPassword)
        }
        editor.commit()
    }

    @Synchronized
    fun clearAccount() {
        preferences.edit().remove(USERNAME_KEY).remove(PASSWORD_KEY).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Invalid encrypted credential" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "credentials"
        const val USERNAME_KEY = "custom_username"
        const val PASSWORD_KEY = "custom_password"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wenku8_custom_account_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
