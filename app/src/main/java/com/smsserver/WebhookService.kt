package com.smsserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts the embedded NanoHTTPD HTTP server.
 *
 * The service keeps the server alive even when the MainActivity is closed.
 * A persistent notification allows the user to see the server is running
 * and provides a tap target to return to the main screen.
 */
class WebhookService : Service() {

    companion object {
        private const val TAG = "WebhookService"
        private const val NOTIFICATION_CHANNEL_ID = "smsserver_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.smsserver.ACTION_START"
        const val ACTION_STOP = "com.smsserver.ACTION_STOP"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_PORT = "port"

        private const val PREF_API_KEY = "api_key"
        private const val PREF_PORT = "port"

        fun buildStartIntent(context: Context, apiKey: String, port: Int): Intent =
            Intent(context, WebhookService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_PORT, port)
            }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, WebhookService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var server: SmsHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val prefs = getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
                val apiKey = intent.getStringExtra(EXTRA_API_KEY)
                    ?: prefs.getString(PREF_API_KEY, "") ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, SmsHttpServer.DEFAULT_PORT)

                // Persist the settings so BootReceiver can restart the service
                prefs.edit()
                    .putString(PREF_API_KEY, apiKey)
                    .putInt(PREF_PORT, port)
                    .apply()

                startForeground(NOTIFICATION_ID, buildNotification(port))
                startServer(apiKey, port)
            }
            else -> {
                // Restarted by system: try to read saved settings
                val prefs = getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
                val port = prefs.getInt(PREF_PORT, SmsHttpServer.DEFAULT_PORT)
                startForeground(NOTIFICATION_ID, buildNotification(port))
                startServer(apiKey, port)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer(apiKey: String, port: Int) {
        stopServer()
        server = SmsHttpServer(applicationContext, apiKey, port)
        try {
            server!!.start()
            Log.i(TAG, "HTTP server started on port $port")
            updateNotification(port, running = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server on port $port", e)
            updateNotification(port, running = false)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        Log.i(TAG, "HTTP server stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "SMS Webhook Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the webhook server status"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int, running: Boolean = true): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (running) "Listening on port $port" else "Server error – tap to restart"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SMS Webhook Server")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(port: Int, running: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(port, running))
    }

    /** Expose the running server instance for MainActivity to query its state. */
    fun getServer(): SmsHttpServer? = server
}
