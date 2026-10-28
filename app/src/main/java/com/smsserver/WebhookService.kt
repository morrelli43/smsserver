package com.smsserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.net.URLEncoder

/**
 * Foreground service that hosts:
 *  1. The embedded NanoHTTPD HTTP server (REST API for SMS/MMS)
 *  2. The WebSocket RelayClient (connects to remote relay for outbound SMS)
 *  
 *  Forces the entire application process to bind exclusively to the cellular network.
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

        @Volatile
        var activeRelayClient: RelayClient? = null

        fun buildStopIntent(context: Context): Intent =
            Intent(context, WebhookService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var server: SmsHttpServer? = null
    private var relayClient: RelayClient? = null
    private var connectionChecker: ConnectionChecker? = null
    private var mmsObserver: android.database.ContentObserver? = null
    private val forwardedMmsIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private var lastKnownMaxMmsId: Long = -1L

    private var connectivityManager: ConnectivityManager? = null
    private var cellularNetwork: Network? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "Cellular network available. Binding process...")
            cellularNetwork = network
            try {
                connectivityManager?.bindProcessToNetwork(network)
                PrefsManager(applicationContext).isCellularBound = true
                
                // If the relay client was already running, restart it with the new network object
                mainHandler.post {
                    val prefs = PrefsManager(applicationContext)
                    if (prefs.isServerEnabled && relayClient != null) {
                        Log.i(TAG, "Restarting relay to use new cellular interface")
                        startAll(prefs.apiKey ?: "", prefs.port, prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind to cellular", e)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(TAG, "Cellular network lost.")
            if (cellularNetwork == network) {
                cellularNetwork = null
            }
            try {
                connectivityManager?.bindProcessToNetwork(null)
                PrefsManager(applicationContext).isCellularBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear binding", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        
        requestCellularBinding()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = PrefsManager(applicationContext)
        val port = intent?.getIntExtra(EXTRA_PORT, prefs.port) ?: prefs.port

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: prefs.apiKey ?: ""
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL) ?: prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
                startAll(apiKey, port, relayUrl)
            }
            else -> {
                startAll(prefs.apiKey ?: "", prefs.port, prefs.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterCellularBinding()
        stopAll()
        super.onDestroy()
    }

    private fun requestCellularBinding() {
        try {
            val cellularRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            connectivityManager?.requestNetwork(cellularRequest, cellularCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Request cellular failed", e)
        }
    }

    private fun unregisterCellularBinding() {
        try {
            connectivityManager?.bindProcessToNetwork(null)
            connectivityManager?.unregisterNetworkCallback(cellularCallback)
            PrefsManager(applicationContext).isCellularBound = false
        } catch (e: Exception) {}
    }

    private fun startAll(apiKey: String, port: Int, relayUrl: String) {
        stopAll()

        server = SmsHttpServer(applicationContext, apiKey, port)
        try {
            server!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "Server start fail", e)
        }

        if (relayUrl.isNotBlank()) {
            relayClient = RelayClient(
                context = applicationContext, 
                relayUrl = relayUrl, 
                apiKey = apiKey,
                network = cellularNetwork,
                onSmsRequest = { address, body -> SmsHelper.sendSms(applicationContext, address, body) },
                onMmsRequest = { address, body, mediaUrl ->
                    var mimeType = "image/jpeg"
                    var base64Data = mediaUrl
                    if (mediaUrl.startsWith("data:")) {
                        val semiIdx = mediaUrl.indexOf(';')
                        val commaIdx = mediaUrl.indexOf(',')
                        if (semiIdx > 0 && commaIdx > semiIdx) {
                            mimeType = mediaUrl.substring(5, semiIdx)
                            base64Data = mediaUrl.substring(commaIdx + 1)
                        }
                    }
                    val imageBytes = try { Base64.decode(base64Data, Base64.DEFAULT) } catch (e: Exception) { null }
                    MmsHelper.sendMms(applicationContext, address, body, imageBytes, mimeType, "attachment")
                },
                onDialRequest = { address ->
                    try {
                        val intent = Intent(Intent.ACTION_CALL).apply {
                            data = "tel:$address".toUri()
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                            data = "tel:$address".toUri()
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(dialIntent)
                    }
                },
                onAddressRequest = { address ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${URLEncoder.encode(address, "UTF-8")}".toUri()).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    } catch (e: Exception) {}
                },
                onRestartRequest = {
                    stopHttpServer()
                    startHttpServer()
                }
            )
            activeRelayClient = relayClient
            relayClient?.connect()
        }

        connectionChecker = ConnectionChecker(this)
        connectionChecker?.start()

        registerMmsContentObserver()
    }

    private fun stopAll() {
        unregisterMmsContentObserver()
        server?.stop()
        server = null
        relayClient?.disconnect()
        relayClient = null
        activeRelayClient = null
        connectionChecker?.stop()
        connectionChecker = null
    }

    private fun registerMmsContentObserver() {
        unregisterMmsContentObserver()
        try {
            val latest = MmsHelper.getMessages(applicationContext, threadId = -1L, limit = 1, offset = 0).firstOrNull()
            if (latest != null && lastKnownMaxMmsId < 0) {
                lastKnownMaxMmsId = latest.id
                forwardedMmsIds.add(latest.id)
            }
            val observer = object : android.database.ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                    super.onChange(selfChange, uri)
                    checkForNewInboundMms()
                }
            }
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://mms-sms"),
                true,
                observer
            )
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://mms"),
                true,
                observer
            )
            mmsObserver = observer
            Log.i(TAG, "Registered ContentObserver for inbound MMS (baseline id=$lastKnownMaxMmsId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MMS ContentObserver", e)
        }
    }

    private fun unregisterMmsContentObserver() {
        mmsObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        mmsObserver = null
    }

    private fun checkForNewInboundMms() {
        Thread {
            try {
                // Poll briefly to allow carrier MMSC attachment write to finish
                for (attempt in 1..4) {
                    val recent = MmsHelper.getMessages(applicationContext, threadId = -1L, limit = 3, offset = 0)
                    for (mms in recent.sortedBy { it.id }) {
                        if (mms.id <= lastKnownMaxMmsId || forwardedMmsIds.contains(mms.id)) continue
                        if (mms.attachmentBase64 == null && attempt < 4) continue

                        forwardedMmsIds.add(mms.id)
                        if (mms.id > lastKnownMaxMmsId) lastKnownMaxMmsId = mms.id

                        val deviceId = getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
                            .getString(PrefsManager.KEY_DEVICE_ID, "unknown-device") ?: "unknown-device"

                        val payload = org.json.JSONObject().apply {
                            put("id", mms.id.toString())
                            put("event", "incoming_sms")
                            put("device_id", deviceId)
                            put("data", org.json.JSONObject().apply {
                                put("id", mms.id.toString())
                                put("type", "mms")
                                put("address", mms.address)
                                put("body", mms.body)
                                put("timestamp", mms.timestamp)
                                put("threadId", mms.threadId)
                                put("hasMmsAttachment", mms.attachmentBase64 != null)
                                if (mms.attachmentBase64 != null) {
                                    put("attachmentBase64", mms.attachmentBase64)
                                    put("attachmentMimeType", mms.attachmentMimeType ?: "image/jpeg")
                                    put("attachmentName", mms.attachmentName ?: "photo.jpg")
                                }
                            })
                        }

                        val sent = activeRelayClient?.sendInboundMessage(payload) ?: false
                        Log.i(TAG, "ContentObserver forwarded new inbound MMS id=${mms.id} from=${mms.address} wsSent=$sent")
                    }
                    Thread.sleep(2000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking for new inbound MMS: ${e.message}")
            }
        }.start()
    }

    fun stopHttpServer() {
        server?.stop()
        server = null
    }

    fun startHttpServer() {
        if (server != null) return
        val prefs = PrefsManager(applicationContext)
        server = SmsHttpServer(applicationContext, prefs.apiKey ?: "", prefs.port)
        try { server!!.start() } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "SMS Webhook Server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SMS Webhook Server")
            .setContentText("Listening on port $port")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
