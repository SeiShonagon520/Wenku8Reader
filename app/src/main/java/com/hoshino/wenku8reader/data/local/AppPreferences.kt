package com.hoshino.wenku8reader.data.local

import android.content.Context

/**
 * 轻量界面偏好（`ui` 偏好文件）：书架排序、更新检查节流等。
 *
 * 这里**只剩不构成用户数据的东西**：
 * - 书架与阅读进度已迁到 Room（见 [LibraryStore] / [ReadingProgressStore]）；
 * - 阅读外观与主题设置由 [ReaderSettings] 承担。
 *
 * 密码不保存在 `ui` 偏好中；可选的个人账号密码单独由 [CredentialStore] 加密，
 * 且其偏好文件从 Android 备份与设备迁移中排除。旧版明文密码会在初始化时迁移并清除。
 */
class AppPreferences(context: Context) {

    private val ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val credentialStore = CredentialStore(context)

    init {
        // Older builds kept the password as plaintext in ui.xml. Migrate it before deleting
        // the legacy value; if Keystore is unavailable, discard the password and require a
        // fresh login after the current session expires rather than keep backing up plaintext.
        val legacyUsername = ui.getString("custom_username", null)
        val legacyPassword = ui.getString("custom_password", null)
        if (legacyUsername != null || legacyPassword != null) {
            credentialStore.migrateLegacy(legacyUsername, legacyPassword)
            ui.edit().remove("custom_username").remove("custom_password").commit()
        }
    }

    var bookcaseSortType: String
        get() = ui.getString("bookcase_sort", "default") ?: "default"
        set(value) {
            ui.edit().putString("bookcase_sort", value).apply()
        }

    var bookcaseSortReversed: Boolean
        get() = ui.getBoolean("bookcase_sort_reversed", false)
        set(value) {
            ui.edit().putBoolean("bookcase_sort_reversed", value).apply()
        }

    var bookcaseViewMode: String
        get() = ui.getString("bookcase_view_mode", "grid") ?: "grid"
        set(value) {
            ui.edit().putString("bookcase_view_mode", value).apply()
        }

    // ---- 更新检查 ----
    /** 用户选择「跳过该版本」的 release tag（如 v0.2.0）；启动/手动检查时不再提示。 */
    var skippedUpdateVersion: String?
        get() = ui.getString("skipped_update_version", null)
        set(value) {
            ui.edit().putString("skipped_update_version", value).apply()
        }

    /** 上次启动自动检查更新的时间戳（毫秒）；用于节流，降低网络无线电功耗。 */
    var lastUpdateCheckAt: Long
        get() = ui.getLong("last_update_check_at", 0L)
        set(value) {
            ui.edit().putLong("last_update_check_at", value).apply()
        }

    // ---- 用户个人账号 ----
    /** 用户自定义文库8账号用户名（为空时使用内置共享账号） */
    val customUsername: String?
        get() = credentialStore.username()

    /** 用户自定义文库8账号密码；仅在 Android Keystore 加密后落盘。 */
    val customPassword: String?
        get() = credentialStore.password()

    /** 当前是否处于个人账号登录状态 */
    val isCustomAccount: Boolean
        get() = !customUsername.isNullOrBlank()

    /** 保存账号名并加密保存密码；返回 false 表示密码未能安全持久化。 */
    fun saveCustomAccount(username: String, password: String): Boolean {
        return credentialStore.saveAccount(username, password)
    }

    /** 清理个人凭据，退出登录并恢复内置共享账号 */
    fun clearCustomAccount() {
        ui.edit().remove("custom_username").remove("custom_password").apply()
        credentialStore.clearAccount()
    }
}
