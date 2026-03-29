package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver that listens for incoming SMS messages and forwards them
 * to the Operations Dashboard webhook derived from the configured relay URL.
 *
 * Uses plain SharedPreferences (not EncryptedSharedPreferences) for all reads
 * because EncryptedSharedPreferences can fail to initialise in BroadcastReceiver
 * contexts due to Keystore access restrictions.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val PLAIN_PREFS = "smsserver_prefs"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "SMS received from $sender — preparing to forward")

        // Use plain SharedPreferences — safe to call from BroadcastReceiver context
        val prefs = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
        val rawRelayUrl = prefs.getString(PrefsManager.KEY_RELAY_URL, PrefsManager.DEFAULT_RELAY_URL)
            ?: PrefsManager.DEFAULT_RELAY_URL

        // Convert wss://host/sms-relay/ → https://host/api/webhooks/sms
        val webhookUrl = rawRelayUrl
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")

        val deviceId = prefs.getString(PrefsManager.KEY_DEVICE_ID, "unknown-device") ?: "unknown-device"

        // API key is in encrypted prefs — try it, but proceed without auth if unavailable
        val apiKey: String? = try {
            PrefsManager(context).apiKey
        } catch (e: Exception) {
            Log.w(TAG, "Could not read API key from encrypted prefs: ${e.message}")
            null
        }

        Log.d(TAG, "Forwarding to: $webhookUrl (device: $deviceId)")

        val payload = JSONObject().apply {
            put("event", "incoming_sms")
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("type", "sms")
                put("address", sender)
                put("body", body)
                put("timestamp", timestamp)
            })
        }

        val pendingResult = goAsync()
        Thread {
            try {
                postWebhook(webhookUrl, payload.toString(), apiKey)
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error forwarding SMS", e)
            } finally {
                pendingResult.finish()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun postWebhook(url: String, jsonBody: String, apiKey: String?) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = false
            }
            connection.outputStream.use { out ->
                out.write(jsonBody.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val responseCode = connection.responseCode
            Log.d(TAG, "Webhook response: HTTP $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST to $url: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
