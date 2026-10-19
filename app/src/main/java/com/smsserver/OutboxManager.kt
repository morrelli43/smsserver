package com.smsserver

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Manages an on-device persistent queue for inbound SMS and MMS messages.
 * If messages arrive while offline or during connection drops, they are enqueued
 * and automatically flushed when the WebSocket reconnects or internet is restored.
 */
object OutboxManager {
    private const val TAG = "OutboxManager"
    private const val PREF_OUTBOX = "smsserver_outbox"
    private const val KEY_PENDING = "pending_messages"

    @Synchronized
    fun enqueue(context: Context, payloadJson: String): String {
        val id = UUID.randomUUID().toString()
        val prefs = context.getSharedPreferences(PREF_OUTBOX, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        try {
            val array = JSONArray(existing)
            val item = JSONObject().apply {
                put("id", id)
                put("payload", payloadJson)
                put("timestamp", System.currentTimeMillis())
            }
            array.put(item)
            prefs.edit().putString(KEY_PENDING, array.toString()).apply()
            Log.d(TAG, "Enqueued message $id to outbox (total pending: ${array.length()})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue to outbox", e)
        }
        return id
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREF_OUTBOX, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        try {
            val array = JSONArray(existing)
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.optString("id") != id) {
                    newArray.put(item)
                }
            }
            prefs.edit().putString(KEY_PENDING, newArray.toString()).apply()
            Log.d(TAG, "Removed message $id from outbox (remaining: ${newArray.length()})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove from outbox", e)
        }
    }

    @Synchronized
    fun getPending(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREF_OUTBOX, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        val list = mutableListOf<Pair<String, String>>()
        try {
            val array = JSONArray(existing)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id")
                val payload = item.optString("payload")
                if (id.isNotBlank() && payload.isNotBlank()) {
                    list.add(Pair(id, payload))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read outbox", e)
        }
        return list
    }

    fun flush(context: Context) {
        Thread {
            val pending = getPending(context)
            if (pending.isEmpty()) return@Thread

            Log.i(TAG, "Flushing outbox: ${pending.size} pending message(s)")
            val relayClient = WebhookService.activeRelayClient

            for ((id, payloadStr) in pending) {
                var delivered = false

                // 1. Try active WebSocket first (fastest, zero-latency)
                if (relayClient != null && relayClient.isConnected()) {
                    try {
                        val json = JSONObject(payloadStr)
                        delivered = relayClient.sendInboundMessage(json)
                        if (delivered) {
                            Log.i(TAG, "Delivered outbox message $id via WebSocket")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket outbox delivery failed: ${e.message}")
                    }
                }

                // 2. If not delivered via WS, try HTTP webhook
                if (!delivered) {
                    val prefs = context.getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
                    val rawRelayUrl = prefs.getString(PrefsManager.KEY_RELAY_URL, PrefsManager.DEFAULT_RELAY_URL)
                        ?: PrefsManager.DEFAULT_RELAY_URL
                    val webhookUrl = rawRelayUrl
                        .replace(Regex("^wss://"), "https://")
                        .replace(Regex("^ws://"), "http://")
                        .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")
                    val apiKey = try { PrefsManager(context).apiKey } catch (e: Exception) { null }

                    delivered = postWebhookDirect(context, webhookUrl, payloadStr, apiKey)
                    if (delivered) {
                        Log.i(TAG, "Delivered outbox message $id via HTTP webhook")
                    }
                }

                if (delivered) {
                    remove(context, id)
                } else {
                    Log.w(TAG, "Could not deliver outbox message $id; will retry on next connection")
                    break // Stop if offline to avoid hammering
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun postWebhookDirect(context: Context, url: String, jsonBody: String, apiKey: String?): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                doInput = true
            }
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            code in 200..299
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
