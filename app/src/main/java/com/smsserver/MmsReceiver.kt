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

        val pendingResult = goAsync()
        Thread {
            try {
                Thread.sleep(MMS_READ_DELAY_MS)

                val messages = MmsHelper.getMessages(context, threadId = -1, limit = 1, offset = 0)
                if (messages.isEmpty()) {
                    Log.w(TAG, "MMS push received but no MMS found")
                    return@Thread
                }

                val mms = messages.first()
                val payload = JSONObject().apply {
                    put("event", "incoming_sms")
                    put("device_id", deviceId)
                    put("data", JSONObject().apply {
                        put("type", "mms")
                        put("address", mms.address)
                        put("body", mms.body ?: "")
                        put("timestamp", mms.timestamp)
                        put("hasMmsAttachment", mms.attachmentBase64 != null)
                        put("attachmentMimeType", mms.attachmentMimeType ?: "image/jpeg")
                    })
                }

                postWebhook(context, webhookUrl, payload.toString(), apiKey)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming MMS", e)
            } finally {
                pendingResult.finish()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun postWebhook(context: Context, url: String, jsonBody: String, apiKey: String?) {
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
                    Log.d(TAG, "Piggybacked connection status from MMS: $connStatus")
                }
            }
            Log.d(TAG, "MMS webhook response: HTTP $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST MMS to $url: ${e.message}")
            PrefsManager(context).connectionStatus = "offline"
        } finally {
            connection?.disconnect()
        }
    }
}
