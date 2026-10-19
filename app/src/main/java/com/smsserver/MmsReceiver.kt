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
                // Poll every 2s for up to 14s to allow carrier MMSC download to complete
                var mms: com.smsserver.model.Message? = null
                for (attempt in 1..7) {
                    Thread.sleep(if (attempt == 1) 2000L else 2000L)
                    val messages = MmsHelper.getMessages(context, threadId = -1, limit = 1, offset = 0)
                    if (messages.isNotEmpty()) {
                        val candidate = messages.first()
                        if (candidate.attachmentBase64 != null || candidate.body.isNotBlank() || attempt >= 6) {
                            mms = candidate
                            Log.d(TAG, "MMS detected on attempt $attempt: from=${mms.address} hasAttachment=${mms.attachmentBase64 != null}")
                            break
                        }
                    }
                }

                if (mms == null) {
                    Log.w(TAG, "MMS push received but no MMS found after polling")
                    return@Thread
                }

                val payload = JSONObject().apply {
                    put("event", "incoming_sms")
                    put("device_id", deviceId)
                    put("data", JSONObject().apply {
                        put("type", "mms")
                        put("address", mms.address)
                        put("body", mms.body)
                        put("timestamp", mms.timestamp)
                        put("threadId", mms.threadId)
                        put("hasMmsAttachment", mms.attachmentBase64 != null)
                        if (mms.attachmentBase64 != null) {
                            put("attachmentBase64", mms.attachmentBase64)
                            put("attachmentMimeType", mms.attachmentMimeType ?: "image/jpeg")
                            put("attachmentName", mms.attachmentName ?: "photo.jpg")

                            val mediaArray = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("mimeType", mms.attachmentMimeType ?: "image/jpeg")
                                    put("dataBase64", mms.attachmentBase64)
                                    put("name", mms.attachmentName ?: "photo.jpg")
                                })
                            }
                            put("media", mediaArray)
                        }
                    })
                }

                // 1. Try sending over persistent WebSocket first (real-time, zero-latency)
                var delivered = false
                val relay = WebhookService.activeRelayClient
                if (relay != null && relay.isConnected()) {
                    delivered = relay.sendInboundMessage(payload)
                    if (delivered) {
                        Log.i(TAG, "MMS delivered immediately via active WebSocket")
                    }
                }

                // 2. If WebSocket offline or failed, enqueue to outbox and attempt HTTP webhook
                if (!delivered) {
                    val outboxId = OutboxManager.enqueue(context, payload.toString())
                    val httpSuccess = postWebhook(context, webhookUrl, payload.toString(), apiKey)
                    if (httpSuccess) {
                        OutboxManager.remove(context, outboxId)
                        Log.i(TAG, "MMS delivered via HTTP webhook and cleared from outbox")
                    } else {
                        Log.w(TAG, "MMS enqueued in Outbox; will retry automatically when connection restores")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming MMS", e)
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
                    Log.d(TAG, "Piggybacked connection status from MMS: $connStatus")
                }
            }
            Log.d(TAG, "MMS webhook response: HTTP $responseCode")
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST MMS to $url: ${e.message}")
            PrefsManager(context).connectionStatus = "offline"
            false
        } finally {
            connection?.disconnect()
        }
    }
}
