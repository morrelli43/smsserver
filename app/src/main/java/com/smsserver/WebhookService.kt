package com.smsserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Foreground service that hosts:
 *  1. The embedded NanoHTTPD HTTP server (REST API for SMS/MMS)
 *  2. The WebSocket RelayClient (connects to remote relay for outbound SMS)
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
        private const val EXTRA_RELAY_URL = "relay_url"

        fun buildStartIntent(context: Context, apiKey: String, port: Int, relayUrl: String): Intent =
            Intent(context, WebhookService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_RELAY_URL, relayUrl)
            }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, WebhookService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var server: SmsHttpServer? = null
    private var relayClient: RelayClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val prefs = PrefsManager(applicationContext)
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: prefs.apiKey ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, SmsHttpServer.DEFAULT_PORT)
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL) ?: prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL

                if (intent.hasExtra(EXTRA_API_KEY)) prefs.apiKey = apiKey
                if (intent.hasExtra(EXTRA_PORT)) prefs.port = port
                if (intent.hasExtra(EXTRA_RELAY_URL)) prefs.relayUrl = relayUrl

                startForeground(NOTIFICATION_ID, buildNotification(port))
                startAll(apiKey, port, relayUrl)
            }
            else -> {
                val prefs = PrefsManager(applicationContext)
                val apiKey = prefs.apiKey ?: ""
                val port = prefs.port
                val relayUrl = prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
                startForeground(NOTIFICATION_ID, buildNotification(port))
                startAll(apiKey, port, relayUrl)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun startAll(apiKey: String, port: Int, relayUrl: String) {
        stopAll()

        // 1. Start HTTP server
        server = SmsHttpServer(applicationContext, apiKey, port)
        try {
            server!!.start()
            Log.i(TAG, "HTTP server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }

        // 2. Start WebSocket relay client
        if (relayUrl.isNotBlank()) {
            relayClient = RelayClient(
                applicationContext, relayUrl, apiKey,
                onSmsRequest = { address, body ->
                    Log.i(TAG, "Relay request: send SMS to $address")
                    SmsHelper.sendSms(applicationContext, address, body)
                },
                onMmsRequest = { address, body, mediaUrl ->
                    Log.i(TAG, "Relay request: send MMS to $address")
                    var mimeType = "image/jpeg"
                    var base64Data = mediaUrl

                    // Expect format: data:image/jpeg;base64,.....
                    if (mediaUrl.startsWith("data:")) {
                        val semiIdx = mediaUrl.indexOf(';')
                        val commaIdx = mediaUrl.indexOf(',')
                        if (semiIdx > 0 && commaIdx > semiIdx) {
                            mimeType = mediaUrl.substring(5, semiIdx)
                            base64Data = mediaUrl.substring(commaIdx + 1)
                        }
                    }

                    val imageBytes = try {
                        Base64.decode(base64Data, Base64.DEFAULT)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode base64 MMS attachment", e)
                        null
                    }

                    MmsHelper.sendMms(
                        applicationContext,
                        address,
                        body,
                        imageBytes,
                        mimeType,
                        "attachment"
                    )
                }
            )
            relayClient?.connect()
            Log.i(TAG, "Relay client connecting to $relayUrl")
        }

        updateNotification(port, running = true)
        scheduleHeartbeatWorker()
    }

    private fun stopAll() {
        server?.stop()
        server = null
        relayClient?.disconnect()
        relayClient = null
        WorkManager.getInstance(applicationContext).cancelUniqueWork(HeartbeatWorker.WORK_NAME)
        Log.i(TAG, "Server and relay stopped")
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
    }

    fun getServer(): SmsHttpServer? = server
}
