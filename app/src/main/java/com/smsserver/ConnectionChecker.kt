package com.smsserver

import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.io.OutputStreamWriter

class ConnectionChecker(
    private val webhookService: WebhookService
) {
    private val TAG = "ConnectionChecker"
    private var job: Job? = null
    private var failureCount = 0
    private val maxFailures = 3
    private val checkIntervalMs = 10000L // 10 seconds
    private val healthCheckUrl = "https://portal.onyascoot.com/api/status"
    private val alertUrl = "https://hooks.morrelli43media.com/webhook/message-center"

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Connection checker started")
            while (isActive) {
                delay(checkIntervalMs) // Wait 10 seconds before pinging
                
                if (checkHealth()) {
                    if (failureCount > 0) {
                        Log.i(TAG, "Health check succeeded. Connection restored.")
                    }
                    failureCount = 0
                } else {
                    failureCount++
                    Log.w(TAG, "Health check failed. Failure count: $failureCount")
                    
                    if (failureCount >= maxFailures) {
                        sendAlert()
                        failureCount = 0 // Reset after alerting, allowing it to alert again if it continues failing
                    } else {
                        recoverServer()
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "Connection checker stopped")
    }

    private fun checkHealth(): Boolean {
        return try {
            val url = URL(healthCheckUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error checking health: ${e.message}")
            false
        }
    }

    private suspend fun recoverServer() {
        Log.i(TAG, "Entering recovery mode: stopping server")
        withContext(Dispatchers.Main) {
            webhookService.stopHttpServer()
        }
        delay(3000) // turn off for 3 seconds
        Log.i(TAG, "Recovery mode: starting server")
        withContext(Dispatchers.Main) {
            webhookService.startHttpServer()
        }
    }

    private fun sendAlert() {
        Log.i(TAG, "Sending alert to telegram via n8n webhook")
        try {
            val url = URL(alertUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("app", "telegram")
                put("target", "webserver")
                put("title", "SMS Gateway Failure")
                put("body", "The SMS Gateway app has failed to ping portal.onyascoot.com 3 times in a row. Recovery mode was unsuccessful.")
                put("subject", "sms_gateway_alert")
            }
            
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            Log.i(TAG, "Alert sent, response code: $responseCode")
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send alert: ${e.message}")
        }
    }
}
