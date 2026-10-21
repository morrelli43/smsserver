package com.smsserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * BroadcastReceiver that listens for incoming phone call state changes.
 * When an incoming call finishes or is missed, it verifies:
 * 1. The caller's name is NOT in the phone's address book.
 * 2. It is the very first time an incoming call from that number has been received.
 *
 * If qualified, it dispatches a "cold_call" event to the Operations Message Centre.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val PLAIN_PREFS = "smsserver_prefs"

        // Track telephony states across broadcast deliveries
        @Volatile
        private var isRinging = false

        @Volatile
        private var wasAnswered = false

        @Volatile
        private var incomingNumber: String? = null

        @Volatile
        private var ringStartTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state changed: $stateStr, rawNumber: ${rawNumber ?: "none"}")

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isRinging = true
                wasAnswered = false
                ringStartTime = System.currentTimeMillis()
                if (!rawNumber.isNullOrBlank()) {
                    incomingNumber = rawNumber
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isRinging) {
                    wasAnswered = true
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (isRinging) {
                    // Call has finished or was missed
                    val ringingNumber = incomingNumber
                    val answered = wasAnswered

                    // Reset state
                    isRinging = false
                    wasAnswered = false
                    incomingNumber = null

                    val pendingResult = goAsync()
                    Thread {
                        try {
                            // Give Android telephony stack 1.2s to write the call record to CallLog
                            Thread.sleep(1200)
                            processCallFinished(context, ringingNumber, answered)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing finished call", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }.also { it.isDaemon = true }.start()
                }
            }
        }
    }

    private fun processCallFinished(context: Context, fallbackNumber: String?, wasAnswered: Boolean) {
        var callerNumber = fallbackNumber
        var callDate = System.currentTimeMillis()
        var duration = 0
        var callStatus = if (wasAnswered) "answered" else "missed"

        // 1. Query latest incoming / missed call from CallLog.Calls (within last 45s)
        try {
            val selection = "${CallLog.Calls.TYPE} IN (?, ?, ?)"
            val selectionArgs = arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                CallLog.Calls.REJECTED_TYPE.toString()
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE
                ),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val logNumber = cursor.getString(0)
                    val logDate = cursor.getLong(1)
                    val logDuration = cursor.getInt(2)
                    val logType = cursor.getInt(3)

                    // Ensure this log entry occurred recently (within last 45 seconds)
                    if (System.currentTimeMillis() - logDate < 45_000) {
                        if (!logNumber.isNullOrBlank()) {
                            callerNumber = logNumber
                        }
                        callDate = logDate
                        duration = logDuration
                        callStatus = when (logType) {
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            else -> if (logDuration > 0 || wasAnswered) "answered" else "missed"
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CallLog", e)
        }

        if (callerNumber.isNullOrBlank() || isUnknownOrPrivate(callerNumber)) {
            Log.d(TAG, "No valid caller number detected (${callerNumber ?: "null"}), ignoring call.")
            return
        }

        val finalNumber = callerNumber!!

        // 2. Qualifier 1: Check if caller's name is in phone's address book
        if (isNumberInContacts(context, finalNumber)) {
            Log.d(TAG, "Qualifier failed: $finalNumber is in phone contacts. Skipping cold call.")
            return
        }

        // 3. Qualifier 2: Check if this is the very first time an incoming call from this number was received
        if (!isFirstTimeCaller(context, finalNumber, callDate)) {
            Log.d(TAG, "Qualifier failed: $finalNumber has previously called this device. Skipping cold call.")
            return
        }

        Log.i(TAG, "Cold Call Qualified! Number: $finalNumber, Status: $callStatus, Time: $callDate")

        // 4. Dispatch cold_call event to gateway & operations portal
        dispatchColdCallEvent(context, finalNumber, callDate, callStatus, duration)
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

    /**
     * Checks if a phone number matches any contact with a non-blank display name.
     */
    private fun isNumberInContacts(context: Context, number: String): Boolean {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        val name = cursor.getString(nameIdx)
                        if (!name.isNullOrBlank()) {
                            Log.d(TAG, "Found contact name '$name' for number $number")
                            return true
                        }
                    }
                }
                false
            } ?: false
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact for $number", e)
            false
        }
    }

    /**
     * Checks if there are any prior incoming, missed, or rejected calls from this number in the CallLog.
     */
    private fun isFirstTimeCaller(context: Context, number: String, currentCallDate: Long): Boolean {
        return try {
            val selection = "${CallLog.Calls.TYPE} IN (?, ?, ?) AND ${CallLog.Calls.DATE} < ?"
            val selectionArgs = arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                CallLog.Calls.REJECTED_TYPE.toString(),
                (currentCallDate - 3000).toString() // exclude this current call
            )

            val cleanTarget = number.replace(Regex("[^0-9]"), "").takeLast(9)

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val prevNum = cursor.getString(0) ?: continue
                    val cleanPrev = prevNum.replace(Regex("[^0-9]"), "").takeLast(9)
                    if (cleanTarget.isNotEmpty() && cleanPrev == cleanTarget) {
                        Log.d(TAG, "Prior call found: '$prevNum' matched '$number'")
                        return false
                    }
                }
                true
            } ?: true
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG permission not granted: ${e.message}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error querying prior calls for $number", e)
            true
        }
    }

    private fun dispatchColdCallEvent(
        context: Context,
        number: String,
        timestamp: Long,
        status: String,
        duration: Int
    ) {
        val prefs = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
        val rawRelayUrl = prefs.getString(PrefsManager.KEY_RELAY_URL, PrefsManager.DEFAULT_RELAY_URL)
            ?: PrefsManager.DEFAULT_RELAY_URL

        val webhookUrl = rawRelayUrl
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/sms-relay/?.*$"), "/api/webhooks/sms")

        val deviceId = prefs.getString(PrefsManager.KEY_DEVICE_ID, "unknown-device") ?: "unknown-device"

        val apiKey: String? = try {
            PrefsManager(context).apiKey
        } catch (e: Exception) {
            null
        }

        val payload = JSONObject().apply {
            put("event", "cold_call")
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("type", "cold_call")
                put("address", number)
                put("timestamp", timestamp)
                put("status", status)
                put("duration", duration)
            })
        }

        // 1. Try sending over persistent WebSocket first (real-time, zero-latency)
        var delivered = false
        val relay = WebhookService.activeRelayClient
        if (relay != null && relay.isConnected()) {
            delivered = relay.sendInboundMessage(payload)
            if (delivered) {
                Log.i(TAG, "Cold call event delivered immediately via active WebSocket")
            }
        }

        // 2. If WebSocket offline or failed, enqueue to outbox and attempt HTTP webhook
        if (!delivered) {
            val outboxId = OutboxManager.enqueue(context, payload.toString())
            val httpSuccess = postWebhook(context, webhookUrl, payload.toString(), apiKey)
            if (httpSuccess) {
                OutboxManager.remove(context, outboxId)
                Log.i(TAG, "Cold call delivered via HTTP webhook and cleared from outbox")
            } else {
                Log.w(TAG, "Cold call enqueued in Outbox; will retry automatically")
            }
        }
    }

    private fun postWebhook(context: Context, url: String, jsonBody: String, apiKey: String?): Boolean {
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
                    PrefsManager(context).connectionStatus = connStatus
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
}
