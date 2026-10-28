package com.smsserver

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.smsserver.model.Conversation
import com.smsserver.model.Message

object SmsHelper {

    private const val TAG = "SmsHelper"

    /**
     * Returns a list of all SMS/MMS conversations sorted by most recent first.
     * Uses content://mms-sms/conversations?simple=true + canonical-addresses for fast address lookup.
     */
    fun getConversations(context: Context): List<Conversation> {
        val conversations = mutableListOf<Conversation>()

        // Preload all canonical addresses in a single query to avoid N+1 (800+ sequential queries taking 25s)
        val canonicalMap = mutableMapOf<Long, String>()
        try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("_id", "address"),
                null,
                null,
                null
            )?.use { cur ->
                val idIdx = cur.getColumnIndex("_id")
                val addrIdx = cur.getColumnIndex("address")
                if (idIdx >= 0 && addrIdx >= 0) {
                    while (cur.moveToNext()) {
                        canonicalMap[cur.getLong(idIdx)] = cur.getString(addrIdx) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preload canonical-addresses: ${e.message}")
        }

        val seenThreads = mutableSetOf<Long>()
        val uri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
        val cursor = try {
            context.contentResolver.query(uri, null, null, null, "date DESC")
        } catch (e: Exception) {
            Log.w(TAG, "mms-sms conversations query failed: ${e.message}")
            null
        }

        cursor?.use {
            val threadIdIdx     = it.getColumnIndex("_id").takeIf { i -> i >= 0 } ?: return@use
            val snippetIdx      = it.getColumnIndex("snippet")
            val countIdx        = it.getColumnIndex("msg_count")
            val dateIdx         = it.getColumnIndex("date")
            val recipientIdsIdx = it.getColumnIndex("recipient_ids")

            while (it.moveToNext()) {
                val threadId     = it.getLong(threadIdIdx)
                val snippet      = if (snippetIdx      >= 0) it.getString(snippetIdx)  ?: "" else ""
                val messageCount = if (countIdx         >= 0) it.getInt(countIdx)       else 0
                val date         = if (dateIdx          >= 0) it.getLong(dateIdx)       else 0L
                val recipientIds = if (recipientIdsIdx  >= 0) it.getString(recipientIdsIdx) ?: "" else ""

                val firstRecipientId = recipientIds.trim().split(" ").firstOrNull()?.toLongOrNull()
                val address = if (firstRecipientId != null) {
                    canonicalMap[firstRecipientId] ?: resolveCanonicalAddress(context, firstRecipientId)
                } else {
                    getAddressForThread(context, threadId)
                }

                seenThreads.add(threadId)
                conversations.add(
                    Conversation(
                        threadId     = threadId,
                        address      = address,
                        snippet      = snippet,
                        timestamp    = date,
                        messageCount = messageCount,
                        unreadCount  = 0,
                        hasMms       = false
                    )
                )
            }
        }

        // Also include recent threads from Telephony.Sms and Telephony.Mms that simple=true may omit
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT 300"
            )?.use { smsCur ->
                while (smsCur.moveToNext()) {
                    val tid = smsCur.getLong(0)
                    if (tid > 0 && seenThreads.add(tid)) {
                        conversations.add(
                            Conversation(
                                threadId     = tid,
                                address      = smsCur.getString(1) ?: "",
                                snippet      = smsCur.getString(2) ?: "",
                                timestamp    = smsCur.getLong(3),
                                messageCount = 1,
                                unreadCount  = 0,
                                hasMms       = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback SMS threads scan failed: ${e.message}")
        }

        return conversations.sortedByDescending { it.timestamp }
    }

    private fun resolveCanonicalAddress(context: Context, recipientId: Long): String {
        val cur = try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/canonical-addresses"),
                null,
                "_id = ?",
                arrayOf(recipientId.toString()),
                null
            )
        } catch (e: Exception) { null }
        return cur?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndex("address")) ?: "" else ""
        } ?: ""
    }

    /**
     * Returns SMS messages for a specific thread, newest first.
     */
    fun getMessages(
        context: Context,
        threadId: Long,
        limit: Int = 50,
        offset: Int = 0
    ): List<Message> {
        val messages = mutableListOf<Message>()
        val uri = Telephony.Sms.CONTENT_URI
        val selection = "${Telephony.Sms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())

        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            ),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addrIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readIdx = it.getColumnIndexOrThrow(Telephony.Sms.READ)

            while (it.moveToNext()) {
                messages.add(
                    Message(
                        id = it.getLong(idIdx),
                        threadId = it.getLong(threadIdx),
                        address = it.getString(addrIdx) ?: "",
                        body = it.getString(bodyIdx) ?: "",
                        timestamp = it.getLong(dateIdx),
                        type = it.getInt(typeIdx),
                        isRead = it.getInt(readIdx) == 1,
                        isMms = false
                    )
                )
            }
        }
        return messages
    }

    /**
     * Sends an SMS message to the given address.
     * Returns true if dispatched successfully (delivery is asynchronous).
     */
    fun sendSms(context: Context, address: String, body: String): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val sentIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent("SMS_SENT"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(address, null, body, sentIntent, null)
            } else {
                val sentIntents = ArrayList<PendingIntent>(parts.size).also { list ->
                    repeat(parts.size) { list.add(sentIntent) }
                }
                smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $address", e)
            false
        }
    }

    /**
     * Marks all messages in the given thread as read.
     */
    fun markThreadAsRead(context: Context, threadId: Long) {
        try {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark thread $threadId as read", e)
        }
    }

    /**
     * Resolves a thread ID for the given phone number without throwing SecurityException when non-default SMS app.
     */
    fun getOrCreateThreadId(context: Context, address: String): Long {
        if (address.isBlank()) return -1L
        val digits = address.filter { it.isDigit() }
        val variants = mutableSetOf(address.trim())
        if (digits.length == 10 && digits.startsWith("04")) {
            variants.add(digits)
            variants.add("61" + digits.substring(1))
            variants.add("+61" + digits.substring(1))
        } else if (digits.length == 11 && digits.startsWith("614")) {
            variants.add("0" + digits.substring(2))
            variants.add(digits)
            variants.add("+$digits")
        }

        // 1. Query Telephony.Sms directly by address variants first (fast, read-only, never throws SecurityException)
        for (v in variants) {
            try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.THREAD_ID),
                    "${Telephony.Sms.ADDRESS} = ?",
                    arrayOf(v),
                    "${Telephony.Sms.DATE} DESC LIMIT 1"
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        val tid = cur.getLong(0)
                        if (tid > 0) return tid
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback to Telephony.Threads.getOrCreateThreadId trying +61 / 61 / 04 variants
        for (v in variants.sortedBy { if (it.startsWith("+61")) 0 else if (it.startsWith("61")) 1 else 2 }) {
            try {
                val tid = Telephony.Threads.getOrCreateThreadId(context, v)
                if (tid > 0) return tid
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get threadId for $v: ${e.message}")
            }
        }
        return -1L
    }

    // --- Private helpers ---

    private fun getAddressForThread(context: Context, threadId: Long): String {
        val cursor: Cursor? = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        } ?: ""
    }

    private fun getUnreadCountForThread(context: Context, threadId: Long): Int {
        val cursor: Cursor? = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString()),
            null
        )
        return cursor?.use { it.count } ?: 0
    }
}
