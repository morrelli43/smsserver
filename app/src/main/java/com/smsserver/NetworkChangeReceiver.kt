package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Listens for network connectivity changes and immediately triggers a one-off Heartbeat 
 * so the backend gets the new IP address without waiting for the next periodic cycle.
 * It also ensures the WebhookService is running when a network change occurs, 
 * providing an additional restart mechanism for reliability.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private var lastNetworkType = -1
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        val prefs = PrefsManager(context)
        if (!prefs.isServerEnabled) {
            return
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetworkInfo
        val isConnected = activeNetwork?.isConnectedOrConnecting == true

        if (isConnected) {
            val currentType = activeNetwork?.type ?: -1
            if (currentType != lastNetworkType) {
                lastNetworkType = currentType
                Log.d(TAG, "Network changed to type $currentType")

                // 1. Ensure WebhookService is running
                Log.d(TAG, "Attempting to start WebhookService for network change...")
                val apiKey = prefs.apiKey ?: ""
                val port = prefs.port
                val relayUrl = prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
                val startIntent = WebhookService.buildStartIntent(context, apiKey, port, relayUrl)
                // Always use startForegroundService as minSdk is 26 (Android O)
                context.startForegroundService(startIntent)

                // 2. Enqueue an immediate heartbeat
                Log.d(TAG, "Enqueueing immediate heartbeat for network change")
                val workRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "Heartbeat_Network_Change",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }
        }
    }
}
