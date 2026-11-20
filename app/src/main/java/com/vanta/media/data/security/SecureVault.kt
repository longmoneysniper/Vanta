package com.vanta.media.data.security

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vantaDataStore by preferencesDataStore(name = "vanta_preferences")

@Singleton
class SecureVault @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vanta_secure_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        val KEY_KIDS_MODE = booleanPreferencesKey("kids_mode_enabled")
        val KEY_SPOILER_FREE = booleanPreferencesKey("spoiler_free_enabled")
        val KEY_AUTO_24HZ = booleanPreferencesKey("auto_24hz_lock")
    }

    val kidsModeFlow: Flow<Boolean> = context.vantaDataStore.data.map { it[KEY_KIDS_MODE] ?: false }
    val spoilerFreeFlow: Flow<Boolean> = context.vantaDataStore.data.map { it[KEY_SPOILER_FREE] ?: false }

    suspend fun setKidsMode(enabled: Boolean) {
        context.vantaDataStore.edit { it[KEY_KIDS_MODE] = enabled }
    }

    suspend fun setSpoilerFree(enabled: Boolean) {
        context.vantaDataStore.edit { it[KEY_SPOILER_FREE] = enabled }
    }

    fun putSecret(key: String, value: String) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    fun getSecret(key: String): String? = encryptedPrefs.getString(key, null)

    fun removeSecret(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }
}
