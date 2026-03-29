package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that automatically starts the WebhookService when the device boots.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.i(TAG, "Device booted, starting WebhookService")

        val prefsManager = PrefsManager(context)
        val apiKey = prefsManager.apiKey

        // Only auto-start if the user has previously started the server
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No API key stored, skipping auto-start")
            return
        }

        val port = prefsManager.port
        val relayUrl = prefsManager.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
        val serviceIntent = WebhookService.buildStartIntent(context, apiKey, port, relayUrl)
        context.startForegroundService(serviceIntent)
    }
}
