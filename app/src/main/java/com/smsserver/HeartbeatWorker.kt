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
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodically fetches the WAN IP and posts device health metrics to the backend.
 */
class HeartbeatWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        const val WORK_NAME = "SMSHeartbeatWork"

        // Production heartbeat URL
        private const val HEARTBEAT_URL_PROD = "https://hooks.morrelli43media.com/webhook/sms-heartbeat"
        // Test heartbeat URL
        private const val HEARTBEAT_URL_TEST = "https://hooks.morrelli43media.com/webhook-test/sms-heartbeat"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = PrefsManager(context)
        val apiKey = prefs.apiKey
        val deviceId = prefs.deviceId

        // If the server isn't enabled, simply succeed (no-op)
        if (!prefs.isServerEnabled) {
            return@withContext Result.success()
        }

        try {
            // 1. Fetch public IP
            val wanIp = fetchWanIp() ?: return@withContext Result.retry()

            // 2. Gather device status
            val batteryLevel = getBatteryLevel()
            val carrierName = getCarrierName()

            // 3. Construct payload
            val payload = mapOf(
                "device_id" to deviceId,
                "wan_ip" to wanIp,
                "port" to prefs.port,
                "battery" to batteryLevel,
                "carrier" to carrierName,
                "timestamp" to (System.currentTimeMillis() / 1000)
            )
            val jsonPayload = Gson().toJson(payload)

            // 4. Post to both Production and Test endpoints
            val successProd = postHeartbeat(HEARTBEAT_URL_PROD, jsonPayload, apiKey)
            val successTest = postHeartbeat(HEARTBEAT_URL_TEST, jsonPayload, apiKey)

            if (successProd || successTest) {
                Result.success()
            } else {
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat work failed", e)
            Result.retry()
        }
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

            val out: OutputStream = connection.outputStream
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()

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
