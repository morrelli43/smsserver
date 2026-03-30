package com.smsserver

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.smsserver.model.Message
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object MmsHelper {

    private const val TAG = "MmsHelper"

    private val MMS_URI = Uri.parse("content://mms/")
    private val MMS_INBOX_URI = Uri.parse("content://mms/inbox")
    private val MMS_SENT_URI = Uri.parse("content://mms/sent")

    /**
     * Returns MMS messages for a given thread ID, newest first.
     * Each message may include a base64-encoded attachment.
     */
    fun getMessages(
        context: Context,
        threadId: Long,
        limit: Int = 50,
        offset: Int = 0
    ): List<Message> {
        val messages = mutableListOf<Message>()

        // threadId < 0 means "no filter" — used by MmsReceiver to get the latest inbound MMS
        val selection     = if (threadId >= 0) "thread_id = ?" else null
        val selectionArgs = if (threadId >= 0) arrayOf(threadId.toString()) else null

        val cursor: Cursor? = context.contentResolver.query(
            MMS_URI,
            arrayOf("_id", "thread_id", "date", "msg_box", "read", "sub"),
            selection,
            selectionArgs,
            "date DESC LIMIT $limit OFFSET $offset"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val date = it.getLong(2) * 1000L  // MMS dates are in seconds
                val msgBox = it.getInt(3)
                val isRead = it.getInt(4) == 1
                val subject = it.getString(5) ?: ""

                val address = getMmsAddress(context, id)
                val textBody = getMmsTextPart(context, id)
                val attachment = getMmsAttachment(context, id)

                messages.add(
                    Message(
                        id = id,
                        threadId = threadId,
                        address = address,
                        body = textBody.ifEmpty { subject },
                        timestamp = date,
                        // msg_box: 1=inbox, 2=sent
                        type = if (msgBox == 1) 1 else 2,
                        isRead = isRead,
                        isMms = true,
                        attachmentBase64 = attachment?.first,
                        attachmentMimeType = attachment?.second,
                        attachmentName = attachment?.third
                    )
                )
            }
        }
        return messages
    }

    /**
     * Returns a single MMS attachment by part ID as a byte array.
     */
    fun getAttachmentBytes(context: Context, partId: Long): Pair<ByteArray, String>? {
        val partUri = Uri.parse("content://mms/part/$partId")
        val cursor: Cursor? = context.contentResolver.query(
            partUri,
            arrayOf("ct", "_data", "text"),
            null, null, null
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                val mimeType = it.getString(0) ?: "application/octet-stream"
                val dataPath = it.getString(1)
                if (dataPath != null) {
                    val bytes = readDataFromPath(context, partUri)
                    if (bytes != null) Pair(bytes, mimeType) else null
                } else null
            } else null
        }
    }

    /**
     * Sends an MMS message.
     * [attachmentData] is the raw bytes of the attachment; [attachmentMimeType] is its MIME type.
     */
    fun sendMms(
        context: Context,
        address: String,
        body: String,
        attachmentData: ByteArray?,
        attachmentMimeType: String?,
        attachmentName: String?
    ): Boolean {
        return try {
            val pduBytes = MmsPduBuilder.buildSendRequest(
                to = address,
                text = body.ifEmpty { null },
                imageData = attachmentData,
                imageMimeType = attachmentMimeType
            )

            // Write PDU to a cache file and share via FileProvider
            val cacheDir = File(context.cacheDir, "mms_pdu").also { it.mkdirs() }
            val pduFile = File(cacheDir, "send_${System.currentTimeMillis()}.mms")
            FileOutputStream(pduFile).use { it.write(pduBytes) }

            val pduUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pduFile
            )

            // Delete any stale PDU files from previous sends before proceeding
            cacheDir.listFiles()?.forEach { file ->
                if (file != pduFile && file.name.endsWith(".mms")) {
                    file.delete()
                }
            }

            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendMultimediaMessage(
                context,
                pduUri,
                null,   // use default MMS APN
                null,   // configOverrides
                null    // sentIntent - fire-and-forget for now
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send MMS to $address", e)
            false
        }
    }

    // --- Private helpers ---

    private fun getMmsAddress(context: Context, mmsId: Long): String {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        val cursor: Cursor? = context.contentResolver.query(
            addrUri,
            arrayOf("address", "type"),
            null, null, null
        )
        return cursor?.use {
            // type 137 = FROM, type 151 = TO
            var from = ""
            while (it.moveToNext()) {
                val type = it.getInt(1)
                val addr = it.getString(0) ?: continue
                if (type == 137) {
                    from = addr
                    break
                }
            }
            if (from.isEmpty()) {
                it.moveToFirst()
                it.getString(0) ?: ""
            } else from
        } ?: ""
    }

    private fun getMmsTextPart(context: Context, mmsId: Long): String {
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val cursor: Cursor? = context.contentResolver.query(
            partUri,
            arrayOf("_id", "ct", "text"),
            "ct = ?",
            arrayOf("text/plain"),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(2) ?: "" else ""
        } ?: ""
    }

    private fun getMmsAttachment(context: Context, mmsId: Long): Triple<String, String, String>? {
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val cursor: Cursor? = context.contentResolver.query(
            partUri,
            arrayOf("_id", "ct", "name", "_data"),
            "ct != ? AND ct != ?",
            arrayOf("text/plain", "application/smil"),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                val partId = it.getLong(0)
                val mimeType = it.getString(1) ?: "application/octet-stream"
                val name = it.getString(2) ?: "attachment"
                val partDataUri = Uri.parse("content://mms/part/$partId")
                val bytes = readDataFromPath(context, partDataUri)
                if (bytes != null) {
                    Triple(Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType, name)
                } else null
            } else null
        }
    }

    private fun readDataFromPath(context: Context, partUri: Uri): ByteArray? {
        return try {
            val stream: InputStream? = context.contentResolver.openInputStream(partUri)
            stream?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read part data from $partUri", e)
            null
        }
    }
}
