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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

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
        private const val EXTRA_API_KEY = "api_key"
        private const val EXTRA_PORT = "port"

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
                val prefsManager = PrefsManager(applicationContext)
                val apiKey = intent.getStringExtra(EXTRA_API_KEY)
                    ?: prefsManager.apiKey ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, SmsHttpServer.DEFAULT_PORT)

                // Persist the settings so BootReceiver can restart the service
                if (intent.hasExtra(EXTRA_API_KEY)) {
                    prefsManager.apiKey = apiKey
                }
                if (intent.hasExtra(EXTRA_PORT)) {
                    prefsManager.port = port
                }

                startForeground(NOTIFICATION_ID, buildNotification(port))
                startServer(apiKey, port)
            }
            else -> {
                // Restarted by system: try to read saved settings
                val prefsManager = PrefsManager(applicationContext)
                val apiKey = prefsManager.apiKey ?: ""
                val port = prefsManager.port
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

            // Start periodic heartbeat
            scheduleHeartbeatWorker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server on port $port", e)
            updateNotification(port, running = false)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        Log.i(TAG, "HTTP server stopped")

        // Stop periodic heartbeat
        WorkManager.getInstance(applicationContext).cancelUniqueWork(HeartbeatWorker.WORK_NAME)
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

    private fun scheduleHeartbeatWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            HeartbeatWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
        Log.i(TAG, "Scheduled periodic heartbeat worker")
    }

    /** Expose the running server instance for MainActivity to query its state. */
    fun getServer(): SmsHttpServer? = server
}
