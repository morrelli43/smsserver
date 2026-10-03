package com.smsserver

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Manages access to both standard SharedPreferences (for non-sensitive data)
 * and EncryptedSharedPreferences (for sensitive data like API keys).
 * 
 * Updated for Android 15 (Pixel 9a) to prevent crashes during hardware keystore 
 * initialization delays.
 */
class PrefsManager(private val context: Context) {

    companion object {
        private const val TAG = "PrefsManager"
        private const val PREF_FILE_STANDARD = "smsserver_prefs"
        private const val PREF_FILE_ENCRYPTED = "smsserver_secure_prefs"

        const val KEY_API_KEY = "api_key"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_PORT = "port"
        const val KEY_SERVER_ENABLED = "server_enabled"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_CONNECTION_STATUS = "connection_status"
        const val KEY_CELLULAR_STATUS = "cellular_status"
        const val KEY_RETRY_COUNT = "retry_count"
        const val KEY_LAST_RETRY_TIME = "last_retry_time"
        const val KEY_LAST_WAN_IP = "last_wan_ip"
        
        const val DEFAULT_API_KEY = "uOmguphOiY4DfUqXJgAaqTFwctXwll68"
        const val DEFAULT_RELAY_URL = "wss://portal.onyascoot.com/sms-relay/"
    }

    private val standardPrefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE_STANDARD, Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Hardware Keystore restricted, using standard fallback to prevent crash", e)
            standardPrefs
        }
    }

    // --- Critical Config (Stored in Standard Prefs for background reliability) ---

    var isServerEnabled: Boolean
        get() = standardPrefs.getBoolean(KEY_SERVER_ENABLED, false)
        set(value) = standardPrefs.edit().putBoolean(KEY_SERVER_ENABLED, value).apply()

    var deviceId: String
        get() {
            var id = standardPrefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = "Device-${UUID.randomUUID().toString().take(8)}"
                standardPrefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id!!
        }
        set(value) = standardPrefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceName: String
        get() = standardPrefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = standardPrefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var isCellularBound: Boolean
        get() = standardPrefs.getBoolean(KEY_CELLULAR_STATUS, false)
        set(value) = standardPrefs.edit().putBoolean(KEY_CELLULAR_STATUS, value).apply()

    var connectionStatus: String?
        get() = standardPrefs.getString(KEY_CONNECTION_STATUS, "unknown")
        set(value) = standardPrefs.edit().putString(KEY_CONNECTION_STATUS, value).apply()

    var port: Int
        get() = standardPrefs.getInt(KEY_PORT, SmsHttpServer.DEFAULT_PORT)
        set(value) = standardPrefs.edit().putInt(KEY_PORT, value).apply()

    var lastWanIp: String?
        get() = standardPrefs.getString(KEY_LAST_WAN_IP, null)
        set(value) = standardPrefs.edit().putString(KEY_LAST_WAN_IP, value).apply()

    var retryCount: Int
        get() = standardPrefs.getInt(KEY_RETRY_COUNT, 0)
        set(value) = standardPrefs.edit().putInt(KEY_RETRY_COUNT, value).apply()

    var lastRetryTime: Long
        get() = standardPrefs.getLong(KEY_LAST_RETRY_TIME, 0L)
        set(value) = standardPrefs.edit().putLong(KEY_LAST_RETRY_TIME, value).apply()

    // --- Best-effort Encrypted (Sensitive) ---

    var apiKey: String?
        get() = encryptedPrefs.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
        set(value) = encryptedPrefs.edit().putString(KEY_API_KEY, value).apply()

    var relayUrl: String?
        get() = standardPrefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL)
        set(value) = standardPrefs.edit().putString(KEY_RELAY_URL, value).apply()

    var webhookUrl: String?
        get() = standardPrefs.getString(KEY_WEBHOOK_URL, null)
        set(value) = standardPrefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    fun migrateIfNeeded() {
        val oldApiKey = standardPrefs.getString(KEY_API_KEY, null)
        if (oldApiKey != null) {
            apiKey = oldApiKey
            standardPrefs.edit().remove(KEY_API_KEY).apply()
        }
    }
}
