package com.smsserver

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Manages access to both standard SharedPreferences (for non-sensitive data)
 * and EncryptedSharedPreferences (for sensitive data like API keys).
 */
class PrefsManager(private val context: Context) {

    companion object {
        private const val PREF_FILE_STANDARD = "smsserver_prefs"
        private const val PREF_FILE_ENCRYPTED = "smsserver_secure_prefs"

        // Keys
        const val KEY_API_KEY = "api_key"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_PORT = "port"
        const val KEY_SERVER_ENABLED = "server_enabled"
        const val KEY_DEVICE_ID = "device_id"
        
        const val DEFAULT_RELAY_URL = "wss://portal.onyascoot.com/sms-relay/"
    }

    private val standardPrefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE_STANDARD, Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- Standard Preferences ---

    var port: Int
        get() = standardPrefs.getInt(KEY_PORT, SmsHttpServer.DEFAULT_PORT)
        set(value) = standardPrefs.edit().putInt(KEY_PORT, value).apply()

    var isServerEnabled: Boolean
        get() = standardPrefs.getBoolean(KEY_SERVER_ENABLED, false)
        set(value) = standardPrefs.edit().putBoolean(KEY_SERVER_ENABLED, value).apply()

    // --- Encrypted Preferences ---

    var apiKey: String?
        get() = encryptedPrefs.getString(KEY_API_KEY, null)
        set(value) = encryptedPrefs.edit().putString(KEY_API_KEY, value).apply()

    // relayUrl stored in plain prefs (not sensitive - it's just a URL)
    // Using standard prefs ensures it's readable from BroadcastReceiver contexts
    var relayUrl: String?
        get() = standardPrefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL)
        set(value) = standardPrefs.edit().putString(KEY_RELAY_URL, value).apply()

    var webhookUrl: String?
        get() = encryptedPrefs.getString(KEY_WEBHOOK_URL, null)
        set(value) = encryptedPrefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    var deviceId: String
        get() {
            var id = encryptedPrefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = "Device-${UUID.randomUUID().toString().take(8)}"
                encryptedPrefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }
        set(value) = encryptedPrefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /**
     * Helper to migrate data from standard prefs to encrypted prefs.
     */
    fun migrateIfNeeded() {
        val oldApiKey = standardPrefs.getString(KEY_API_KEY, null)
        if (oldApiKey != null) {
            apiKey = oldApiKey
            standardPrefs.edit().remove(KEY_API_KEY).apply()
        }
    }
}
