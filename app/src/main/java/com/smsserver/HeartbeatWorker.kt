package com.smsserver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodically fetches the WAN IP and posts device health metrics to the backend.
 * Also performs connection health checks and automatic server restarts if the 
 * operations site link is lost.
 */
class HeartbeatWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        const val WORK_NAME = "SMSHeartbeatWork"
        private const val MAX_RETRIES = 3
        private const val LOCKOUT_DURATION_MS = 3600_000L // 1 hour
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = PrefsManager(context)
        
        // If the server isn't enabled, simply succeed (no-op)
        if (!prefs.isServerEnabled) {
            return@withContext Result.success()
        }

        // Check for lockout
        val currentTime = System.currentTimeMillis()
        if (prefs.retryCount >= MAX_RETRIES && (currentTime - prefs.lastRetryTime) < LOCKOUT_DURATION_MS) {
            Log.w(TAG, "Heartbeat lockout in effect. Skipping.")
            return@withContext Result.success()
        }

        try {
            // 1. Fetch public IP
            val wanIp = fetchWanIp() ?: return@withContext Result.retry()

            // 2. Gather device status
            val batteryLevel = getBatteryLevel()
            val carrierName = getCarrierName()

            // 3. Construct payload for standard heartbeat
            val targetUrls = listOf(
                "https://hooks.morrelli43media.com/webhook/sms-heartbeat",
                "https://hooks.morrelli43media.com/webhook-test/sms-heartbeat"
            )

            val payload = mapOf(
                "device_id" to prefs.deviceId,
                "wan_ip" to wanIp,
                "port" to prefs.port,
                "battery" to batteryLevel,
                "carrier" to carrierName,
                "timestamp" to (System.currentTimeMillis() / 1000)
            )
            val jsonPayload = Gson().toJson(payload)

            // 4. Post standard heartbeats
            for (url in targetUrls) {
                postHeartbeat(url, jsonPayload, prefs.apiKey)
            }

            // 5. Connection Health Check (Ping Mechanism)
            performConnectionCheck(prefs)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat work failed", e)
            Result.retry()
        }
    }

    private suspend fun performConnectionCheck(prefs: PrefsManager) {
        val rawRelayUrl = prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
        val webhookUrl = rawRelayUrl
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")

        val pingPayload = JSONObject().apply {
            put("event", "ping")
            put("device_id", prefs.deviceId)
        }.toString()

        // checkPing returns: 
        // true = Connected (ok)
        // false = Received reply, but status was NOT ok
        // null = No response at all (failsafe: site down)
        val initialStatus = checkPing(webhookUrl, pingPayload, prefs.apiKey)

        if (initialStatus == true) {
            prefs.retryCount = 0
            prefs.connectionStatus = "connected"
            Log.d(TAG, "Connection check: OK")
        } else if (initialStatus == false) {
            // Failsafe condition met: We got a reply, but it says NOT ok.
            // This suggests the app/link needs a reset.
            for (i in 1..MAX_RETRIES) {
                Log.w(TAG, "Status NOT 'connected'. Attempt $i of $MAX_RETRIES: Reconnecting...")
                
                toggleServer()
                delay(3000)
                
                val retryStatus = checkPing(webhookUrl, pingPayload, prefs.apiKey)
                if (retryStatus == true) {
                    prefs.retryCount = 0
                    prefs.connectionStatus = "connected"
                    Log.i(TAG, "Connection restored after toggle.")
                    return
                } else if (retryStatus == null) {
                    // If it stops responding during retries, site likely went down.
                    Log.w(TAG, "Site stopped responding during retries. Aborting reconnection.")
                    prefs.connectionStatus = "offline"
                    return
                }
            }
            
            // Retries failed (status remained false)
            prefs.retryCount = MAX_RETRIES
            prefs.lastRetryTime = System.currentTimeMillis()
            prefs.connectionStatus = "error"
            Log.e(TAG, "Failed to restore 'connected' status after $MAX_RETRIES retries.")
        } else {
            // initialStatus == null (No data received from webhook)
            // Failsafe: Don't try to reconnect if the site is simply unreachable.
            Log.w(TAG, "No response from site. Failsafe: Skipping reconnection attempts.")
            prefs.connectionStatus = "offline"
            prefs.retryCount = 0 // Don't penalize the app for site downtime
        }
    }

    /**
     * Returns true if status is "connected", 
     * false if we got a valid JSON reply but status is NOT "connected",
     * null if the connection failed or timed out (no reply).
     */
    private fun checkPing(urlStr: String, json: String, apiKey: String?): Boolean? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                doInput = true
            }

            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(response)
                // If we got a JSON reply with a status field, it's a "reply"
                if (jsonObj.has("status")) {
                    jsonObj.optString("status") == "connected"
                } else {
                    false // Valid JSON but missing status? Treat as not ok.
                }
            } else {
                // HTTP error (4xx/5xx) or no response
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed (no response): ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun toggleServer() {
        // Stop the service
        context.startService(WebhookService.buildStopIntent(context))
        
        // Start the service
        val prefs = PrefsManager(context)
        val apiKey = prefs.apiKey ?: ""
        val port = prefs.port
        val relayUrl = prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
        context.startForegroundService(WebhookService.buildStartIntent(context, apiKey, port, relayUrl))
    }

    private fun fetchWanIp(): String? {
        return try {
            val conn = (URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch WAN IP", e)
            null
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1
        }
    }

    private fun getCarrierName(): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephonyManager?.networkOperatorName ?: "unknown"
    }

    private fun postHeartbeat(urlStr: String, json: String, apiKey: String?): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }

            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            Log.d(TAG, "Heartbeat sent to $url, response: $code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat POST failed to $urlStr", e)
            false
        } finally {
            connection?.disconnect()
        }
    }
}
