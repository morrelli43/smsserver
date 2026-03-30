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
     * Returns a list of all SMS/MMS conversations (threads) sorted by most recent first.
     * Uses content://mms-sms/conversations?simple=true which has proper 'date' and 'address'
     * columns for both SMS and MMS threads.
     */
    fun getConversations(context: Context): List<Conversation> {
        val conversations = mutableListOf<Conversation>()

        // mms-sms combined URI — has date, snippet, recipient_ids on all Android versions
        val uri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")

        val cursor: Cursor? = try {
            context.contentResolver.query(uri, null, null, null, "date DESC")
        } catch (e: Exception) {
            Log.w(TAG, "mms-sms conversations query failed, falling back: ${e.message}")
            null
        }

        cursor?.use {
            val threadIdIdx  = it.getColumnIndex("_id").takeIf { i -> i >= 0 }
                ?: it.getColumnIndex("thread_id").takeIf { i -> i >= 0 } ?: return@use
            val snippetIdx   = it.getColumnIndex("snippet")
            val countIdx     = it.getColumnIndex("msg_count")
            val dateIdx      = it.getColumnIndex("date")

            while (it.moveToNext()) {
                val threadId     = it.getLong(threadIdIdx)
                val snippet      = if (snippetIdx  >= 0) it.getString(snippetIdx)  ?: "" else ""
                val messageCount = if (countIdx     >= 0) it.getInt(countIdx)       else 0
                val date         = if (dateIdx      >= 0) it.getLong(dateIdx)       else 0L

                val address      = getAddressForThread(context, threadId)
                val unreadCount  = getUnreadCountForThread(context, threadId)

                conversations.add(
                    Conversation(
                        threadId     = threadId,
                        address      = address,
                        snippet      = snippet,
                        timestamp    = date,
                        messageCount = messageCount,
                        unreadCount  = unreadCount,
                        hasMms       = false
                    )
                )
            }
        }
        return conversations
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
