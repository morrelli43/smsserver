package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver that listens for incoming SMS messages and forwards them
 * to the Operations Dashboard webhook derived from the configured relay URL.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "Received SMS from $sender")

        val prefsManager = PrefsManager(context)
<<<<<<< HEAD
        val rawUrl = prefsManager.webhookUrl

        if (rawUrl.isNullOrBlank()) {
            Log.d(TAG, "No webhook URL configured, skipping forward")
=======
        val rawUrl = prefsManager.relayUrl

        if (rawUrl.isNullOrBlank()) {
            Log.w(TAG, "No relay URL configured, skipping forward")
>>>>>>> ac10961 (fix: Forward inbound SMS to Operations Dashboard via HTTP POST)
            return
        }

        // The UI stores the relay URL as wss:// (e.g. wss://portal.onyascoot.com/sms-relay/)
<<<<<<< HEAD
        // HttpURLConnection cannot handle the wss:// scheme — convert to https:// and fix path.
=======
        // HttpURLConnection cannot handle wss:// - convert to https:// and fix the path.
>>>>>>> ac10961 (fix: Forward inbound SMS to Operations Dashboard via HTTP POST)
        val webhookUrl = rawUrl
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")
<<<<<<< HEAD
        val apiKey = prefsManager.apiKey

        // Forward to webhook in background thread.
        // goAsync() keeps the BroadcastReceiver alive until pendingResult.finish() is called,
        // preventing the system from killing the process before the HTTP request completes.
        val pendingResult = goAsync()
        val payload = mapOf(
            "event" to "incoming_sms",
            "device_id" to prefsManager.deviceId,
            "data" to mapOf(
                "type" to "sms",
                "address" to sender,
                "body" to body,
                "timestamp" to timestamp
            )
        )
        val jsonPayload = gson.toJson(payload)
=======
>>>>>>> ac10961 (fix: Forward inbound SMS to Operations Dashboard via HTTP POST)

        Log.d(TAG, "Forwarding inbound SMS to: $webhookUrl")

        val apiKey = prefsManager.apiKey
        val deviceId = prefsManager.deviceId

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

        // goAsync() keeps the BroadcastReceiver alive until pendingResult.finish() is called
        val pendingResult = goAsync()
        Thread {
            try {
                postWebhook(webhookUrl, payload.toString(), apiKey)
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
            Log.d(TAG, "Inbound SMS forwarded to $url → HTTP $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward inbound SMS to $url", e)
        } finally {
            connection?.disconnect()
        }
    }
}
