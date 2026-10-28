package com.hoshino.wenku8reader.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Application-scoped account identity shared by settings and bookshelf ViewModels.
 * Credentials remain in [AppPreferences]' encrypted credential store.
 */
class AccountSession(
    private val preferences: AppPreferences,
    scope: CoroutineScope,
) {
    private val _username = MutableStateFlow(preferences.customUsername)
    val username: StateFlow<String?> = _username.asStateFlow()
    val isCustomAccount: StateFlow<Boolean> = _username
        .map { !it.isNullOrBlank() }
        .stateIn(scope, SharingStarted.Eagerly, !_username.value.isNullOrBlank())

    /** Persist credentials and broadcast the new identity to every observing screen. */
    fun saveCustomAccount(username: String, password: String): Boolean {
        val saved = preferences.saveCustomAccount(username, password)
        _username.value = username.trim().takeIf { it.isNotEmpty() }
        return saved
    }

    /** Clear the persisted account and immediately return all screens to guest mode. */
    fun clearCustomAccount() {
        preferences.clearCustomAccount()
        _username.value = null
    }
}
