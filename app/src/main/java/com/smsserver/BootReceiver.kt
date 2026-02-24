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

        val prefs = context.getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", null)

        // Only auto-start if the user has previously started the server (api_key is set)
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No API key stored, skipping auto-start")
            return
        }

        val port = prefs.getInt("port", SmsHttpServer.DEFAULT_PORT)
        val serviceIntent = WebhookService.buildStartIntent(context, apiKey, port)
        context.startForegroundService(serviceIntent)
    }
}
