package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver that listens for incoming MMS messages (WAP PUSH) and forwards them
 * to the Operations Dashboard webhook.
 *
 * Uses plain SharedPreferences for all config reads — EncryptedSharedPreferences can
 * fail silently when initialised from a BroadcastReceiver context.
 */
class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val PLAIN_PREFS = "smsserver_prefs"
        // Delay to let Android write the MMS to the content provider before we read it
        private const val MMS_READ_DELAY_MS = 3_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION) return
        if (intent.type != "application/vnd.wap.mms-message") return

        Log.d(TAG, "MMS WAP push received — preparing to forward")

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

        val apiKey: String? = try {
            PrefsManager(context).apiKey
        } catch (e: Exception) {
            Log.w(TAG, "Could not read API key from encrypted prefs: ${e.message}")
            null
        }

        Log.d(TAG, "Forwarding MMS to: $webhookUrl (device: $deviceId)")

        val pendingResult = goAsync()
        Thread {
            try {
                // Wait for Android to write the MMS to the content provider
                Thread.sleep(MMS_READ_DELAY_MS)

                val messages = MmsHelper.getMessages(context, threadId = -1, limit = 1, offset = 0)
                if (messages.isEmpty()) {
                    Log.w(TAG, "MMS push received but no MMS found in content provider after ${MMS_READ_DELAY_MS}ms delay")
                    return@Thread
                }

                val mms = messages.first()
                Log.d(TAG, "MMS found: from=${mms.address} body='${mms.body}' hasAttachment=${mms.attachmentBase64 != null} mimeType=${mms.attachmentMimeType}")

                val mediaArray = JSONArray()
                if (!mms.attachmentBase64.isNullOrBlank()) {
                    mediaArray.put(JSONObject().apply {
                        put("mimeType", mms.attachmentMimeType ?: "application/octet-stream")
                        put("name", mms.attachmentName ?: "attachment")
                        put("dataBase64", mms.attachmentBase64)
                    })
                }

                val payload = JSONObject().apply {
                    put("event", "incoming_sms")
                    put("device_id", deviceId)
                    put("data", JSONObject().apply {
                        put("type", "mms")
                        put("address", mms.address)
                        put("body", mms.body ?: "")
                        put("timestamp", mms.timestamp)
                        put("media", mediaArray)
                    })
                }

                postWebhook(webhookUrl, payload.toString(), apiKey)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming MMS", e)
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
            Log.d(TAG, "MMS webhook response: HTTP $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST MMS to $url: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
