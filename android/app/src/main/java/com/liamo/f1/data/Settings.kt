package com.liamo.f1.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSnapshot(
    val watchAddress: String? = null,
    val owner: String = "",
    val repo: String = "",
    val hasToken: Boolean = false,
    /** Try SpO2 and history sync in each background snapshot (experimental features). */
    val experimentalInSnapshot: Boolean = true,
    val schedulesOn: Boolean = true,
) {
    val gitHubReady get() = hasToken && owner.isNotBlank() && repo.isNotBlank()
}

/**
 * Small key/value settings, encrypted with a key from the Android Keystore. The GitHub
 * token never leaves this class except into an Authorization header; it is never logged.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = open(context)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setWatchAddress(address: String?) = save { putString(KEY_ADDRESS, address) }

    fun setGitHub(owner: String, repo: String, token: String?) = save {
        putString(KEY_OWNER, owner.trim())
        putString(KEY_REPO, repo.trim())
        // A blank token field means "keep the saved one".
        if (!token.isNullOrBlank()) putString(KEY_TOKEN, token.trim())
    }

    fun clearToken() = save { remove(KEY_TOKEN) }

    fun setExperimentalInSnapshot(on: Boolean) = save { putBoolean(KEY_EXPERIMENTAL, on) }

    fun setSchedulesOn(on: Boolean) = save { putBoolean(KEY_SCHEDULES, on) }

    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true, action = block)
        _state.value = read()
    }

    private fun read() = SettingsSnapshot(
        watchAddress = prefs.getString(KEY_ADDRESS, null),
        owner = prefs.getString(KEY_OWNER, "") ?: "",
        repo = prefs.getString(KEY_REPO, "") ?: "",
        hasToken = !prefs.getString(KEY_TOKEN, null).isNullOrBlank(),
        experimentalInSnapshot = prefs.getBoolean(KEY_EXPERIMENTAL, true),
        schedulesOn = prefs.getBoolean(KEY_SCHEDULES, true),
    )

    private companion object {
        const val FILE = "f1_settings"
        const val KEY_ADDRESS = "watch_address"
        const val KEY_OWNER = "gh_owner"
        const val KEY_REPO = "gh_repo"
        const val KEY_TOKEN = "gh_token"
        const val KEY_EXPERIMENTAL = "experimental_in_snapshot"
        const val KEY_SCHEDULES = "schedules_on"

        fun open(context: Context): SharedPreferences {
            fun create(): SharedPreferences = EncryptedSharedPreferences.create(
                context,
                FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                create()
            } catch (e: Exception) {
                // Keystore entry lost (e.g. after a backup restore): start over rather than crash.
                Log.w("Settings", "encrypted settings unreadable, resetting: ${e.javaClass.simpleName}")
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
