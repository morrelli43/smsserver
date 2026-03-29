package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver that listens for incoming MMS messages (WAP PUSH) and forwards them
 * to the configured webhook URL via an HTTP POST request.
 */
class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val gson = Gson()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION) return
        if (intent.type != "application/vnd.wap.mms-message") return

        val prefsManager = PrefsManager(context)
        val rawUrl = prefsManager.relayUrl
        val apiKey = prefsManager.apiKey
        val deviceId = prefsManager.deviceId

        if (rawUrl.isNullOrBlank()) {
            Log.d(TAG, "No relay URL configured, skipping forward")
            return
        }

        // Convert wss:// relay URL to https:// webhook URL
        val webhookUrl = rawUrl
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")

        Log.d(TAG, "Received MMS push notification, will forward to: $webhookUrl")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(3000)

                val messages = MmsHelper.getMessages(context, threadId = -1, limit = 1, offset = 0)
                if (messages.isNotEmpty()) {
                    val mms = messages.first()

                    val payload = mapOf(
                        "event" to "incoming_sms",
                        "device_id" to deviceId,
                        "data" to mapOf(
                            "type" to "mms",
                            "address" to mms.address,
                            "body" to mms.body,
                            "timestamp" to mms.timestamp,
                            "media" to listOfNotNull(
                                mms.attachmentBase64?.let { base64 ->
                                    mapOf(
                                        "mimeType" to mms.attachmentMimeType,
                                        "name" to mms.attachmentName,
                                        "dataBase64" to base64
                                    )
                                }
                            )
                        )
                    )

                    postWebhook(webhookUrl, gson.toJson(payload), apiKey)
                } else {
                    Log.w(TAG, "MMS push received but no MMS found in database.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming MMS", e)
            } finally {
                pendingResult.finish()
            }
        }
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
            Log.d(TAG, "MMS webhook response: $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post MMS to webhook $url", e)
        } finally {
            connection?.disconnect()
        }
    }
}
