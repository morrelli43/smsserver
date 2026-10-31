package com.smsserver

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationListenerService that intercepts Google Phone's ("com.google.android.dialer")
 * "Take a Message" / Call Screen transcribed voicemails and automatically forwards the
 * transcript and caller details to the OnyaScoot Operations Message Centre.
 */
class VoicemailNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "VoicemailListener"
        private const val DEBOUNCE_DELAY_MS = 3500L
        private const val HASH_EXPIRY_MS = 2 * 60 * 60 * 1000L // 2 hours
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val PLAIN_PREFS = "smsserver_prefs"

        // Deduplication cache: signature -> timestamp
        private val sentSignatures = ConcurrentHashMap<String, Long>()

        // Debounce storage: callerNumber -> pending job
        private val pendingTranscripts = ConcurrentHashMap<String, PendingTranscript>()
        private val pendingRunnables = ConcurrentHashMap<String, Runnable>()
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    data class PendingTranscript(
        val callerNumber: String,
        val transcript: String,
        val timestamp: Long,
        val contactName: String? = null
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "VoicemailNotificationListener connected and active")
        try {
            val active = activeNotifications
            if (!active.isNullOrEmpty()) {
                Log.i(TAG, "Scanning ${active.size} active notifications on connect...")
                for (sbn in active) {
                    onNotificationPosted(sbn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active notifications on connect: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        // We target Google Phone dialer ("com.google.android.dialer") and standard dialer packages
        if (pkg != "com.google.android.dialer" && !pkg.endsWith(".dialer") && !pkg.contains("incallui")) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim() ?: ""

        val rawBody = when {
            bigText.isNotBlank() -> bigText
            text.isNotBlank() -> text
            else -> ""
        }

        if (rawBody.isBlank()) return

        // 1. Filter out standard missed call / call status notifications that do NOT contain transcripts
        if (isIgnoredCallStatus(rawBody)) {
            return
        }

        // Clean candidate transcript
        val cleanTranscript = cleanTranscriptText(rawBody)
        if (cleanTranscript.length < 8) {
            return
        }

        // 2. Extract caller phone number and optional contact name
        val (callerNumber, contactName) = resolveCallerDetails(title, sbn.postTime)
        if (callerNumber.isNullOrBlank() || isUnknownOrPrivate(callerNumber)) {
            Log.d(TAG, "Ignoring notification: unable to resolve valid caller number (title='$title')")
            return
        }

        val signature = "$callerNumber:${cleanTranscript.lowercase()}"

        // 3. Deduplication check
        val lastSent = sentSignatures[signature]
        if (lastSent != null && (System.currentTimeMillis() - lastSent) < HASH_EXPIRY_MS) {
            Log.d(TAG, "Duplicate transcript already dispatched for $callerNumber. Skipping.")
            return
        }

        Log.i(TAG, "Candidate 'Take a Message' transcript detected from $callerNumber: '${cleanTranscript.take(50)}...'")

        // 4. Debounce handling: Google Phone streams/updates the transcript as the caller speaks.
        // We buffer and schedule dispatch after 3.5s of silence/completion.
        synchronized(pendingTranscripts) {
            val existing = pendingTranscripts[callerNumber]
            // If new transcript is longer or superset of existing, update it
            if (existing == null || cleanTranscript.length >= existing.transcript.length || cleanTranscript != existing.transcript) {
                pendingTranscripts[callerNumber] = PendingTranscript(
                    callerNumber = callerNumber,
                    transcript = cleanTranscript,
                    timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    contactName = contactName
                )
            }

            // Cancel any pending runnable for this caller
            pendingRunnables[callerNumber]?.let { mainHandler.removeCallbacks(it) }

            val runnable = Runnable {
                val toDispatch = synchronized(pendingTranscripts) {
                    pendingRunnables.remove(callerNumber)
                    pendingTranscripts.remove(callerNumber)
                }
                if (toDispatch != null) {
                    dispatchVoicemailTranscript(toDispatch)
                }
            }

            pendingRunnables[callerNumber] = runnable
            mainHandler.postDelayed(runnable, DEBOUNCE_DELAY_MS)
        }
    }

    private fun dispatchVoicemailTranscript(pt: PendingTranscript) {
        val signature = "${pt.callerNumber}:${pt.transcript.lowercase()}"
        sentSignatures[signature] = System.currentTimeMillis()

        // Clean up expired signatures
        val now = System.currentTimeMillis()
        sentSignatures.entries.removeIf { (now - it.value) > HASH_EXPIRY_MS }

        Thread {
            try {
                val prefs = getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
                val rawRelayUrl = prefs.getString(PrefsManager.KEY_RELAY_URL, PrefsManager.DEFAULT_RELAY_URL)
                    ?: PrefsManager.DEFAULT_RELAY_URL

                val webhookUrl = rawRelayUrl
                    .replace(Regex("^wss://"), "https://")
                    .replace(Regex("^ws://"), "http://")
                    .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")

                val deviceId = prefs.getString(PrefsManager.KEY_DEVICE_ID, "unknown-device") ?: "unknown-device"

                val apiKey: String? = try {
                    PrefsManager(this).apiKey
                } catch (e: Exception) {
                    null
                }

                val payload = JSONObject().apply {
                    put("event", "incoming_sms")
                    put("device_id", deviceId)
                    put("data", JSONObject().apply {
                        put("type", "voicemail_transcript")
                        put("source", "google_take_a_message")
                        put("address", pt.callerNumber)
                        put("body", pt.transcript)
                        put("timestamp", pt.timestamp)
                        if (!pt.contactName.isNullOrBlank()) {
                            put("contact_name", pt.contactName)
                        }
                    })
                }

                Log.i(TAG, "Dispatching voicemail transcript for ${pt.callerNumber}...")

                // 1. Try sending over persistent WebSocket first
                var delivered = false
                val relay = WebhookService.activeRelayClient
                if (relay != null && relay.isConnected()) {
                    delivered = relay.sendInboundMessage(payload)
                    if (delivered) {
                        Log.i(TAG, "Voicemail transcript delivered immediately via active WebSocket")
                    }
                }

                // 2. If WebSocket offline or failed, enqueue to outbox and attempt HTTP webhook
                if (!delivered) {
                    val outboxId = OutboxManager.enqueue(this, payload.toString())
                    val httpSuccess = postWebhook(webhookUrl, payload.toString(), apiKey)
                    if (httpSuccess) {
                        OutboxManager.remove(this, outboxId)
                        Log.i(TAG, "Voicemail transcript delivered via HTTP webhook and cleared from outbox")
                    } else {
                        Log.w(TAG, "Voicemail transcript enqueued in Outbox; will retry automatically")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching voicemail transcript", e)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun postWebhook(url: String, jsonBody: String, apiKey: String?): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
            }
            connection.outputStream.use { out ->
                out.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(response)
                val connStatus = jsonObj.optJSONObject("connection")?.optString("status")
                if (connStatus != null) {
                    PrefsManager(this).connectionStatus = connStatus
                }
            }
            Log.d(TAG, "Webhook response: HTTP $responseCode")
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST to $url: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun isIgnoredCallStatus(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower == "missed call" ||
                lower.matches(Regex("^(?:\\d+\\s+)?missed\\s+call.*")) ||
                lower.matches(Regex("^incoming\\s+call.*")) ||
                lower.matches(Regex("^ongoing\\s+call.*")) ||
                lower.matches(Regex("^calling.*")) ||
                lower.matches(Regex("^call\\s+ended.*")) ||
                lower.matches(Regex("^call\\s+in\\s+progress.*")) ||
                lower.matches(Regex("^return\\s+call.*")) ||
                lower.matches(Regex("^caller\\s+left\\s+no\\s+message.*")) ||
                lower.matches(Regex("^no\\s+message\\s+was\\s+left.*"))
    }

    private fun cleanTranscriptText(text: String): String {
        var result = text.trim()
        // Strip common prefixes like "Message:" or "Transcript:"
        result = result.replace(Regex("^(?:Message|Transcript):\\s*", RegexOption.IGNORE_CASE), "")
        // Strip leading/trailing quotation marks if whole message is quoted
        if ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("“") && result.endsWith("”"))) {
            result = result.substring(1, result.length - 1).trim()
        }
        return result
    }

    private fun resolveCallerDetails(title: String, notificationTime: Long): Pair<String?, String?> {
        // 1. Try finding phone digits in the title (e.g. "0423 264 231" or "+61 423 264 231")
        val phoneRegex = Regex("""(\+?[0-9][0-9\s-]{7,}[0-9])""")
        val match = phoneRegex.find(title)
        if (match != null) {
            val rawNum = match.value.replace(Regex("[^0-9+]"), "")
            return Pair(rawNum, null)
        }

        // 2. Title may be a Contact Name (e.g. "Mark Smith")
        val contactName = if (title.isNotBlank() && !isUnknownOrPrivate(title)) title else null

        // Query CallLog.Calls for the most recent call within the last 3 minutes
        val callLogNumber = queryRecentCallLogNumber(notificationTime)
        if (!callLogNumber.isNullOrBlank()) {
            return Pair(callLogNumber, contactName)
        }

        // Fallback: if title looks like a phone number even without strict formatting
        val digitsOnly = title.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length in 8..15) {
            return Pair(title, null)
        }

        return Pair(null, contactName)
    }

    private fun queryRecentCallLogNumber(postTime: Long): String? {
        return try {
            val sinceTime = if (postTime > 0) postTime - 180_000 else System.currentTimeMillis() - 180_000
            val selection = "${CallLog.Calls.DATE} >= ?"
            val selectionArgs = arrayOf(sinceTime.toString())

            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val num = cursor.getString(0)
                    if (!num.isNullOrBlank() && !isUnknownOrPrivate(num)) {
                        return num
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG permission not granted: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying CallLog for caller number: ${e.message}")
            null
        }
    }

    private fun isUnknownOrPrivate(number: String): Boolean {
        val clean = number.trim().lowercase()
        return clean.isEmpty() ||
                clean == "-1" ||
                clean == "-2" ||
                clean == "private" ||
                clean == "unknown" ||
                clean == "withheld" ||
                clean == "anonymous"
    }
}
