package com.hoshino.wenku8reader.data.local.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class WebDavConfiguration(
    val serverUrl: String = "",
    val remoteDirectory: String = "Wenku8Reader/backups",
    val username: String = "",
    val passwordSaved: Boolean = false,
    val automaticBackupEnabled: Boolean = false,
    val lastBackupAt: Long? = null,
    val lastBackupError: String? = null,
    val lastBackupWarning: String? = null,
)

/** App-private WebDAV settings with the password encrypted by a dedicated Android Keystore key. */
internal class WebDavConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): WebDavConfiguration = WebDavConfiguration(
        serverUrl = preferences.getString(KEY_SERVER_URL, "").orEmpty(),
        remoteDirectory = preferences.getString(KEY_REMOTE_DIRECTORY, DEFAULT_REMOTE_DIRECTORY).orEmpty(),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        passwordSaved = preferences.contains(KEY_PASSWORD),
        automaticBackupEnabled = preferences.getBoolean(KEY_AUTO_BACKUP, false),
        lastBackupAt = preferences.getLong(KEY_LAST_BACKUP_AT, 0L).takeIf { it > 0L },
        lastBackupError = preferences.getString(KEY_LAST_ERROR, null),
        lastBackupWarning = preferences.getString(KEY_LAST_WARNING, null),
    )

    @Synchronized
    fun credentials(): WebDavCredentials? {
        val settings = read()
        if (settings.serverUrl.isBlank() || settings.username.isBlank()) return null
        val encryptedPassword = preferences.getString(KEY_PASSWORD, null) ?: return null
        val password = try {
            decrypt(encryptedPassword)
        } catch (_: Exception) {
            preferences.edit().remove(KEY_PASSWORD).apply()
            throw IllegalStateException("WebDAV 密码无法解密，请重新保存密码")
        }
        return WebDavCredentials(
            serverUrl = settings.serverUrl,
            remoteDirectory = settings.remoteDirectory,
            username = settings.username,
            password = password,
        )
    }

    @Synchronized
    fun save(
        serverUrl: String,
        remoteDirectory: String,
        username: String,
        password: String,
    ): WebDavConfiguration {
        val normalizedUrl = normalizeServerUrl(serverUrl)
        val normalizedDirectory = normalizeRemoteDirectory(remoteDirectory)
        require(username.isNotBlank()) { "请输入 WebDAV 用户名" }
        val configChanged = preferences.getString(KEY_SERVER_URL, "") != normalizedUrl ||
            preferences.getString(KEY_REMOTE_DIRECTORY, DEFAULT_REMOTE_DIRECTORY) != normalizedDirectory ||
            preferences.getString(KEY_USERNAME, "") != username.trim() ||
            password.isNotEmpty()
        val editor = preferences.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_REMOTE_DIRECTORY, normalizedDirectory)
            .putString(KEY_USERNAME, username.trim())
        if (password.isNotEmpty()) {
            editor.putString(KEY_PASSWORD, encrypt(password))
        } else {
            require(preferences.contains(KEY_PASSWORD)) { "请输入 WebDAV 密码" }
        }
        if (configChanged) {
            editor.remove(KEY_LAST_BACKUP_AT)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_LAST_WARNING)
        }
        require(editor.commit()) { "无法保存 WebDAV 设置" }
        return read()
    }

    @Synchronized
    fun setAutomaticBackupEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_BACKUP, enabled).commit()
    }

    @Synchronized
    fun recordBackupSuccess(completedAt: Long, warning: String? = null) {
        preferences.edit()
            .putLong(KEY_LAST_BACKUP_AT, completedAt)
            .remove(KEY_LAST_ERROR)
            .apply {
                if (warning.isNullOrBlank()) remove(KEY_LAST_WARNING)
                else putString(KEY_LAST_WARNING, warning)
            }
            .commit()
    }

    @Synchronized
    fun recordBackupFailure(message: String) {
        preferences.edit().putString(KEY_LAST_ERROR, message).commit()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun normalizeServerUrl(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的 HTTPS 地址")
        require(url.isHttps) { "WebDAV 只支持 HTTPS 地址" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "请将用户名和密码填入对应字段，不要写在地址中" }
        require(url.query == null && url.fragment == null) { "WebDAV 地址不能包含查询参数或片段" }
        return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
    }

    private fun normalizeRemoteDirectory(value: String): String {
        val path = value.trim().trim('/')
        require(path.isNotBlank()) { "请输入 WebDAV 远端目录" }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "远端目录不能包含空白路径段或 .."
        }
        return path
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Invalid WebDAV credential" }
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
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "webdav_config"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_REMOTE_DIRECTORY = "remote_directory"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password_enc"
        const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        const val KEY_LAST_BACKUP_AT = "last_backup_at"
        const val KEY_LAST_ERROR = "last_backup_error"
        const val KEY_LAST_WARNING = "last_backup_warning"
        const val DEFAULT_REMOTE_DIRECTORY = "Wenku8Reader/backups"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wenku8_webdav_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

internal data class WebDavCredentials(
    val serverUrl: String,
    val remoteDirectory: String,
    val username: String,
    val password: String,
)
