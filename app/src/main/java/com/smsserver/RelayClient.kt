package com.smsserver

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to a remote relay server.
 * This allows the n8n/web server to send SMS through the phone
 * even when the phone is behind a firewall (CGNAT).
 */
class RelayClient(
    private val context: Context,
    private val relayUrl: String,
    private val apiKey: String,
    private val onSmsRequest: (address: String, body: String) -> Unit
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val NORMAL_CLOSURE_STATUS = 1000
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isClosing = false

    fun connect() {
        val request = Request.Builder()
            .url(relayUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("X-Device-ID", PrefsManager(context).deviceId)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Relay Connected: $relayUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay Closing: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Relay Connection Error", t)
                if (!isClosing) {
                    // Simple retry logic could be added here
                }
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val action = json.optString("action")
            if (action == "send_sms") {
                val data = json.getJSONObject("data")
                val address = data.getString("address")
                val body = data.getString("body")
                onSmsRequest(address, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing relay message", e)
        }
    }

    fun disconnect() {
        isClosing = true
        webSocket?.close(NORMAL_CLOSURE_STATUS, "App stopping server")
    }
}
