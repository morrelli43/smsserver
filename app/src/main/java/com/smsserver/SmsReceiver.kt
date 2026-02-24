package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver that listens for incoming SMS messages and forwards them
 * to the configured webhook URL via an HTTP POST request.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val gson = Gson()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Combine multi-part SMS into a single logical message
        val sender = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "Received SMS from $sender")

        val prefsManager = PrefsManager(context)
        val webhookUrl = prefsManager.webhookUrl
        val apiKey = prefsManager.apiKey

        if (webhookUrl.isNullOrBlank()) {
            Log.d(TAG, "No webhook URL configured, skipping forward")
            return
        }

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

        Thread {
            try {
                postWebhook(webhookUrl, jsonPayload, apiKey)
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

            val out: OutputStream = connection.outputStream
            out.write(jsonBody.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "Webhook response: $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post to webhook $url", e)
        } finally {
            connection?.disconnect()
        }
    }
}
