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

        val prefs = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
        val rawRelayUrl = prefs.getString(PrefsManager.KEY_RELAY_URL, PrefsManager.DEFAULT_RELAY_URL)
            ?: PrefsManager.DEFAULT_RELAY_URL

        val webhookUrl = rawRelayUrl
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")

        val deviceId = prefs.getString(PrefsManager.KEY_DEVICE_ID, "unknown-device") ?: "unknown-device"

        val apiKey: String? = try {
            PrefsManager(context).apiKey
        } catch (e: Exception) {
            Log.w(TAG, "Could not read API key: ${e.message}")
            null
        }

        val threadId = SmsHelper.getOrCreateThreadId(context, sender)

        val payload = JSONObject().apply {
            put("event", "incoming_sms")
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("type", "sms")
                put("address", sender)
                put("body", body)
                put("timestamp", timestamp)
                if (threadId > 0) put("threadId", threadId)
            })
        }

        val pendingResult = goAsync()
        Thread {
            try {
                // 1. Try sending over persistent WebSocket first (real-time, zero-latency)
                var delivered = false
                val relay = WebhookService.activeRelayClient
                if (relay != null && relay.isConnected()) {
                    delivered = relay.sendInboundMessage(payload)
                    if (delivered) {
                        Log.i(TAG, "SMS delivered immediately via active WebSocket")
                    }
                }

                // 2. If WebSocket offline or failed, enqueue to outbox and attempt HTTP webhook
                if (!delivered) {
                    val outboxId = OutboxManager.enqueue(context, payload.toString())
                    val httpSuccess = postWebhook(context, webhookUrl, payload.toString(), apiKey)
                    if (httpSuccess) {
                        OutboxManager.remove(context, outboxId)
                        Log.i(TAG, "SMS delivered via HTTP webhook and cleared from outbox")
                    } else {
                        Log.w(TAG, "SMS enqueued in Outbox; will retry automatically when connection restores")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error forwarding SMS", e)
            } finally {
                pendingResult.finish()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun postWebhook(context: Context, url: String, jsonBody: String, apiKey: String?): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
            }
            connection.outputStream.use { out ->
                out.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(response)
                val connStatus = jsonObj.optJSONObject("connection")?.optString("status")
                if (connStatus != null) {
                    PrefsManager(context).connectionStatus = connStatus
                    Log.d(TAG, "Piggybacked connection status: $connStatus")
                }
            }
            Log.d(TAG, "Webhook response: HTTP $responseCode")
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST to $url: ${e.message}")
            PrefsManager(context).connectionStatus = "offline"
            false
        } finally {
            connection?.disconnect()
        }
    }
}
