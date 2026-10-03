package com.smsserver

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to a remote relay server.
 * Automatically reconnects on disconnection or failure with exponential backoff.
 * 
 * Supports forced network routing (e.g. Cellular only) via the provided Network object.
 */
class RelayClient(
    private val context: Context,
    private val relayUrl: String,
    private val apiKey: String,
    private val network: Network? = null,
    private val onSmsRequest: (address: String, body: String) -> Unit,
    private val onMmsRequest: (address: String, body: String, mediaUrl: String) -> Unit,
    private val onDialRequest: (address: String) -> Unit,
    private val onAddressRequest: (address: String) -> Unit,
    private val onRestartRequest: () -> Unit
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val NORMAL_CLOSURE_STATUS = 1000
        private const val INITIAL_RETRY_DELAY_S = 5L
        private const val MAX_RETRY_DELAY_S = 60L
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // Keep-alive ping every 30s
        
        // Force the client to use the specific network (e.g. Cellular) if provided
        network?.let {
            Log.i(TAG, "Configuring OkHttpClient to use specific network factory")
            builder.socketFactory(it.socketFactory)
        }
        
        builder.build()
    }

    private var webSocket: WebSocket? = null
    @Volatile private var isClosing = false
    private var retryDelaySecs = INITIAL_RETRY_DELAY_S

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var retryFuture: ScheduledFuture<*>? = null

    fun connect() {
        if (isClosing) return
        val prefs = PrefsManager(context)
        val localIp = getWifiIpAddress()
        
        Log.i(TAG, "Connecting to relay: $relayUrl (Forced Network: ${network != null})")
        
        val request = Request.Builder()
            .url(relayUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("X-Device-ID", prefs.deviceId)
            .addHeader("X-Device-Name", prefs.deviceName)
            .addHeader("X-Local-IP", localIp)   // phone's LAN IP for reverse proxy discovery
            .addHeader("X-Local-Port", prefs.port.toString())
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Relay connected: $relayUrl")
                retryDelaySecs = INITIAL_RETRY_DELAY_S // Reset backoff on success
                PrefsManager(context).connectionStatus = "connected"

                // Announce device identity & friendly name immediately to relay
                try {
                    val reg = JSONObject().apply {
                        put("action", "device_register")
                        put("deviceId", prefs.deviceId)
                        put("deviceName", prefs.deviceName)
                    }
                    webSocket.send(reg.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send device registration payload", e)
                }
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
                PrefsManager(context).connectionStatus = "offline"
                if (!isClosing) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Relay connection failed: ${t.message}")
                PrefsManager(context).connectionStatus = "error"
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
            when (action) {
                "send_sms" -> {
                    val data = json.getJSONObject("data")
                    val address = data.getString("address")
                    val body = data.getString("body")
                    onSmsRequest(address, body)
                }
                "send_mms" -> {
                    val data = json.getJSONObject("data")
                    val address = data.getString("address")
                    val body = data.getString("body")
                    val mediaUrl = data.getString("mediaUrl")
                    onMmsRequest(address, body, mediaUrl)
                }
                "dial" -> {
                    val data = json.getJSONObject("data")
                    val address = data.getString("address")
                    onDialRequest(address)
                }
                "address" -> {
                    val addressValue = json.optString("address")
                    if (addressValue.isNotEmpty()) {
                        onAddressRequest(addressValue)
                    } else {
                        val data = json.optJSONObject("data")
                        val fallbackAddress = data?.optString("address") ?: ""
                        if (fallbackAddress.isNotEmpty()) {
                            onAddressRequest(fallbackAddress)
                        }
                    }
                }
                "restart_server" -> {
                    Log.i(TAG, "Relay request: restart_server")
                    onRestartRequest()
                }
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

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) "0.0.0.0" else {
                "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get WiFi IP: ${e.message}")
            "0.0.0.0"
        }
    }
}
