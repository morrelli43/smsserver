package com.smsserver

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to a remote relay server.
 * Automatically reconnects on disconnection or failure with exponential backoff.
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
        private const val INITIAL_RETRY_DELAY_S = 5L
        private const val MAX_RETRY_DELAY_S = 60L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep-alive ping every 30s
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var isClosing = false
    private var retryDelaySecs = INITIAL_RETRY_DELAY_S

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var retryFuture: ScheduledFuture<*>? = null

    fun connect() {
        if (isClosing) return
        val prefs = PrefsManager(context)
        val request = Request.Builder()
            .url(relayUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("X-Device-ID", prefs.deviceId)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Relay connected: $relayUrl")
                retryDelaySecs = INITIAL_RETRY_DELAY_S // Reset backoff on success
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay closing: $reason")
                webSocket.close(NORMAL_CLOSURE_STATUS, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay closed: $reason")
                if (!isClosing) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Relay connection failed: ${t.message}")
                if (!isClosing) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        retryFuture?.cancel(false)
        Log.i(TAG, "Reconnecting in ${retryDelaySecs}s...")
        retryFuture = scheduler.schedule({
            if (!isClosing) connect()
        }, retryDelaySecs, TimeUnit.SECONDS)
        // Exponential backoff, capped at MAX_RETRY_DELAY_S
        retryDelaySecs = minOf(retryDelaySecs * 2, MAX_RETRY_DELAY_S)
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
        retryFuture?.cancel(false)
        webSocket?.close(NORMAL_CLOSURE_STATUS, "App stopping server")
        scheduler.shutdownNow()
    }
}
